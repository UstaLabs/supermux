import SwiftUI
import Shared
import Observation

@Observable
@MainActor
final class SidebarArchiveRevealState {
    static let maximumHeight: CGFloat = 52
    static let latchThreshold: CGFloat = 46

    var visibleHeight: CGFloat = 0
    var isLatched = false

    func track(top: CGFloat) {
        guard !isLatched else { return }
        visibleHeight = min(Self.maximumHeight, max(0, -top))
    }

    func latch() {
        isLatched = true
        visibleHeight = Self.maximumHeight
    }

    func close() {
        isLatched = false
        visibleHeight = 0
    }
}

/// Sidebar / root list — the merged multi-host fleet (spec §5): grouped (PA + project) sessions
/// across every paired host, a per-row host badge (All filter only) + an `All · <host…> · +` filter
/// chip row (both when ≥2 hosts are paired), and offline hosts as greyed "last seen" groups.
/// Per-row reads (preview/agent state) and actions (settle/rename/mute) route to the session's OWNING
/// `BrokerSession` via `Fleet`. Single-host is the unchanged path: no chips, no badges.
///
/// Interaction: whole-row free drag reorder (Android / desktop / web parity) via onDrag + drop
/// with a live working order — not List.onMove/edit mode. macOS: press+drag; iOS: long-press
/// drag. Settled / PA / offline rows stay fixed.
struct SessionsListView: View {
    let fleet: Fleet
    @Binding var selected: String?
    var onNewSession: () -> Void
    var onArchived: () -> Void
    var onAddHost: () -> Void = {}
    var onSessionSelected: (String) -> Void = { _ in }
    var onOpenDraft: (String) -> Void = { _ in }
    var onReorder: ([String]) -> Void = { _ in }

    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    @State private var collapsed: Set<String> = SessionsListView.loadCollapsed()
    // Keep high-frequency overscroll state out of this view's own observation graph. On macOS,
    // rebuilding the AppKit-backed List on every trackpad tick makes its rows visibly shudder.
    @State private var archiveReveal = SidebarArchiveRevealState()
    @State private var renameTarget: SessionInfo?
    @State private var renameText = ""
    @State private var killTarget: SessionInfo?
    /// Source session for "Continue in new conversation" (web SessionContextMenu parity).
    @State private var continueTarget: SessionInfo?
    @State private var groupByProject = UserDefaults.standard.object(forKey: "cmux:group-by-project") as? Bool ?? false
    @State private var settledExpanded: Set<String> = []
    @State private var flatSettledExpanded = false
    /// Live whole-row drag reorder (section-scoped). See `SessionSectionReorderState`.
    @State private var reorderState = SessionSectionReorderState()

    var body: some View {
        let owner = fleet.sessionHost
        let hostViews = fleet.hostViews
        let hostByRecord = Dictionary(hostViews.map { ($0.recordId, $0) }, uniquingKeysWith: { a, _ in a })
        let multiHost = fleet.multiHost
        // When a specific host pill is selected, every row is already that host — hide the redundant badge.
        let showRowHostBadge = multiHost && fleet.filter == nil

        return VStack(spacing: 0) {
            #if os(macOS)
            AppUpdateBanner()
            #endif
            if multiHost {
                HostFilterChips(
                    hosts: hostViews,
                    selected: fleet.filter,
                    count: { rid in owner.values.reduce(0) { $0 + ($1 == rid ? 1 : 0) } },
                    onSelect: { fleet.setFilter($0) },
                    onAddHost: onAddHost,
                    onForgetHost: { recordId in
                        // Tear the detail down before Fleet closes the owning BrokerSession. The
                        // remaining host becomes active inside Fleet.refresh().
                        if let sessionId = selected, owner[sessionId] == recordId { selected = nil }
                        fleet.forgetHost(recordId: recordId)
                    }
                )
                .background(.bar)
                Divider()
            }
            list(owner: owner, hostByRecord: hostByRecord, multiHost: multiHost, showRowHostBadge: showRowHostBadge)
        }
    }

    private func list(owner: [String: String], hostByRecord: [String: HostView], multiHost: Bool, showRowHostBadge: Bool) -> some View {
        List(selection: $selected) {
            Section {
                Button(action: onNewSession) {
                    HStack(spacing: 12) {
                        Image(systemName: "plus")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Theme.teal)
                            .frame(width: 28, height: 28)
                            .background(Theme.teal.opacity(0.14), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("New session")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                            Text("Pick a project and start chatting")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .smSessionRowSurface(selected: false, accented: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(TestIds.newSession)
                .moveDisabled(true)
                .deleteDisabled(true)
                .smSessionListRowChrome(spacing: EdgeInsets(top: 6, leading: 0, bottom: 4, trailing: 0))
            }

            Section {
                HStack(spacing: 10) {
                    Label("Group by project", systemImage: "folder")
                        .font(.subheadline.weight(.medium))
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    Toggle("", isOn: Binding(
                        get: { groupByProject },
                        set: {
                            groupByProject = $0
                            UserDefaults.standard.set($0, forKey: "cmux:group-by-project")
                        }
                    ))
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .controlSize(.small)
                    .accessibilityLabel("Group by project")
                    .accessibilityIdentifier("group-by-project")
                }
                .moveDisabled(true)
                .deleteDisabled(true)
                .smSessionListRowChrome(spacing: EdgeInsets(top: 2, leading: 4, bottom: 2, trailing: 4))
            }

            if groupByProject {
                ForEach(fleet.onlineGroups(), id: \.workdir) { group in
                    onlineSection(group, owner: owner, hostByRecord: hostByRecord, showRowHostBadge: showRowHostBadge)
                }
            } else {
                flatSectionsView(owner: owner, hostByRecord: hostByRecord, multiHost: multiHost, showRowHostBadge: showRowHostBadge)
            }

            // Offline hosts: greyed group per host with a "last seen" header (multi-host only).
            ForEach(fleet.offlineHostGroups(), id: \.host.recordId) { entry in
                offlineSection(host: entry.host, sessions: entry.sessions, hostByRecord: hostByRecord, showRowHostBadge: showRowHostBadge)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.smGroupedBackground)
        .accessibilityIdentifier(TestIds.sessionList)
        #if os(macOS)
        .contentMargins(.horizontal, 10, for: .scrollContent)
        // The reveal must not participate in scroll layout on AppKit. The overlay also keeps a
        // fixed frame while its contents translate into view, so rubber-banding never changes
        // the List's geometry.
        .overlay(alignment: .top) {
            SidebarArchiveRevealBar(state: archiveReveal, onArchived: onArchived)
        }
        #else
        .contentMargins(.horizontal, 12, for: .scrollContent)
        .safeAreaInset(edge: .top, spacing: 0) {
            SidebarArchiveRevealBar(state: archiveReveal, onArchived: onArchived)
        }
        #endif
        .onScrollGeometryChange(for: CGFloat.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top
        } action: { _, top in
            let pull = max(0, -top)
            if archiveReveal.isLatched {
                if top > 24 {
                    withAnimation(.snappy(duration: 0.25)) { archiveReveal.close() }
                }
            } else if pull >= SidebarArchiveRevealState.latchThreshold {
                withAnimation(.snappy(duration: 0.2)) { archiveReveal.latch() }
            } else {
                archiveReveal.track(top: top)
            }
        }
        .onAppear { fleet.refreshArchived() }
        .onChange(of: selected) { _, id in
            if reorderState.isDragging { reorderState.cancel() }
            guard let id,
                  let s = fleet.sessions.first(where: { $0.id == id }),
                  (s.userStatus ?? "") == "draft"
            else { return }
            selected = nil
            onOpenDraft(id)
        }
        .navigationTitle("supermux")
        .smInlineNavigationTitle()
        .overlay {
            if !fleet.synced && fleet.sessions.isEmpty {
                ProgressView("Connecting…").tint(Theme.teal)
            }
        }
        .alert("Rename session", isPresented: Binding(get: { renameTarget != nil },
                                                      set: { if !$0 { renameTarget = nil } })) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Rename") {
                if let t = renameTarget { fleet.broker(for: t.id)?.rename(t.id, to: renameText) }
                renameTarget = nil
            }
        }
        .confirmationDialog("Settle \u{201C}\(killTarget?.name ?? "")\u{201D}?",
                            isPresented: Binding(get: { killTarget != nil },
                                                 set: { if !$0 { killTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Settle session", role: .destructive) {
                if let t = killTarget { fleet.broker(for: t.id)?.kill(t.id) }
                killTarget = nil
            }
            Button("Cancel", role: .cancel) { killTarget = nil }
        }
        .sheet(item: Binding(
            get: { continueTarget.map { ContinueSheetItem(session: $0) } },
            set: { continueTarget = $0?.session }
        )) { item in
            if let b = fleet.broker(for: item.session.id) {
                ContinueConversationSheet(
                    broker: b,
                    source: item.session,
                    onStarted: { id in
                        continueTarget = nil
                        selected = id
                        onSessionSelected(id)
                    },
                    onCancel: { continueTarget = nil }
                )
            }
        }
    }

    private func commitReorder(_ orderedIds: [String]) {
        guard !orderedIds.isEmpty else { return }
        onReorder(orderedIds)
    }

    /// Identifiable wrapper so `.sheet(item:)` can present continue for a session row.
    private struct ContinueSheetItem: Identifiable {
        let session: SessionInfo
        var id: String { session.id }
    }

    /// Flat task list (web !groupByProject): In Progress / Drafts / Settled across all projects.
    @ViewBuilder private func flatSectionsView(
        owner: [String: String],
        hostByRecord: [String: HostView],
        multiHost: Bool,
        showRowHostBadge: Bool
    ) -> some View {
        let online = flatOnlineSessions(owner: owner, multiHost: multiHost)
        // Built once per body evaluation, not once per lookup: `buildTaskSections` asks for the
        // Settled recency key of every row (hundreds, once archived sessions are folded in), and
        // the old closure ran a full fleet scan each time. See `Fleet.lastMessageTsBySession`.
        let ts = fleet.lastMessageTsBySession()
        let lastTs: (SessionInfo) -> String = { ts[$0.id] ?? "" }
        let sections = buildTaskSections(
            list: combinedTaskSessions(live: online, archived: fleet.archivedForList),
            lastTs: lastTs
        )
        // PA pin (web SessionTaskList paSection) — not in task sections.
        // Stable sortOrder only; new messages must not reshuffle rows.
        let pas = sessionsByUserOrder(sessions: online.filter { $0.role == "personal_assistant" })
        if !pas.isEmpty {
            Section {
                ForEach(pas, id: \.id) { session in
                    let recordId = owner[session.id] ?? ""
                    let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                    selectableRow(session, host: rowHost, reorderable: false)
                }
                .moveDisabled(true)
                .deleteDisabled(true)
            } header: {
                sessionSectionHeader("Personal Assistants")
            }
        }
        ForEach(sections, id: \.key) { section in
            if section.key == .settled {
                Section {
                    Button {
                        withAnimation(.snappy(duration: 0.2)) { flatSettledExpanded.toggle() }
                    } label: {
                        Text(flatSettledExpanded
                             ? "Hide \(section.sessions.count) settled"
                             : "Show \(section.sessions.count) settled")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .moveDisabled(true)
                    .deleteDisabled(true)
                    if flatSettledExpanded {
                        ForEach(section.sessions, id: \.id) { session in
                            let recordId = owner[session.id] ?? ""
                            let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                            selectableRow(session, host: rowHost, reorderable: false,
                                          projectTag: projectLabel(session: session, home: inferHomeDir(workdir: session.workdir)))
                        }
                        .moveDisabled(true)
                        .deleteDisabled(true)
                    }
                }
            } else {
                // Section key string is stable for live-order scope (flat: task status).
                let sectionKey = "flat:\(section.key.wire)"
                let base = section.sessions
                let rows = reorderState.displaySessions(sectionKey: sectionKey, fallback: base)
                let ids = base.map(\.id)
                Section {
                    ForEach(rows, id: \.id) { session in
                        let recordId = owner[session.id] ?? ""
                        let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                        selectableRow(
                            session,
                            host: rowHost,
                            reorderable: true,
                            projectTag: projectLabel(session: session, home: inferHomeDir(workdir: session.workdir)),
                            sectionKey: sectionKey,
                            sectionIds: ids
                        )
                    }
                    .deleteDisabled(true)
                    .moveDisabled(true)
                } header: {
                    sessionSectionHeader(section.label)
                }
            }
        }
    }

    private func flatOnlineSessions(owner: [String: String], multiHost: Bool) -> [SessionInfo] {
        let offline = Set(fleet.hostViews.filter { !$0.online }.map { $0.recordId })
        let shown = fleet.filteredSessions
        if multiHost {
            return shown.filter { !offline.contains(owner[$0.id] ?? "") }
        }
        return shown
    }

    @ViewBuilder private func onlineSection(
        _ group: SessionGroup,
        owner: [String: String],
        hostByRecord: [String: HostView],
        showRowHostBadge: Bool
    ) -> some View {
        let openSessions: [SessionInfo] = {
            if group.workdir == PA_GROUP_KEY { return group.sessions }
            let secs = group.sections
            if secs.isEmpty { return group.sessions }
            return secs.filter { $0.key != .settled }.flatMap { $0.sessions }
        }()
        let settled = group.sections.first(where: { $0.key == .settled })?.sessions ?? []
        let activeCount = group.workdir == PA_GROUP_KEY ? group.sessions.count : openSessions.count
        let canReorderGroup = group.workdir != PA_GROUP_KEY
        let sectionKey = "group:\(group.workdir)"
        let baseOpen = openSessions
        let rows = canReorderGroup
            ? reorderState.displaySessions(sectionKey: sectionKey, fallback: baseOpen)
            : baseOpen
        let ids = baseOpen.map(\.id)
        Section {
            if !collapsed.contains(group.workdir) {
                ForEach(rows, id: \.id) { session in
                    let recordId = owner[session.id] ?? ""
                    let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                    selectableRow(
                        session,
                        host: rowHost,
                        reorderable: canReorderGroup,
                        sectionKey: sectionKey,
                        sectionIds: ids
                    )
                }
                .deleteDisabled(true)
                .moveDisabled(true)
                if !settled.isEmpty {
                    Button {
                        withAnimation(.snappy(duration: 0.2)) {
                            if settledExpanded.contains(group.workdir) { settledExpanded.remove(group.workdir) }
                            else { settledExpanded.insert(group.workdir) }
                        }
                    } label: {
                        Text(settledExpanded.contains(group.workdir)
                             ? "Hide \(settled.count) settled"
                             : "Show \(settled.count) settled")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .moveDisabled(true)
                    .deleteDisabled(true)
                    if settledExpanded.contains(group.workdir) {
                        ForEach(settled, id: \.id) { session in
                            let recordId = owner[session.id] ?? ""
                            let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                            selectableRow(session, host: rowHost, reorderable: false)
                        }
                        .moveDisabled(true)
                        .deleteDisabled(true)
                    }
                }
            }
        } header: {
            header(group, count: activeCount)
        }
    }

    @ViewBuilder private func offlineSection(
        host: HostView,
        sessions: [SessionInfo],
        hostByRecord: [String: HostView],
        showRowHostBadge: Bool
    ) -> some View {
        Section {
            ForEach(sessions, id: \.id) { session in
                let rowHost: HostView? = showRowHostBadge ? hostByRecord[host.recordId] : nil
                selectableRow(session, host: rowHost, reorderable: false)
                    .opacity(0.55)
            }
            .moveDisabled(true)
            .deleteDisabled(true)
        } header: {
            offlineHeader(host)
        }
    }

    private func sessionSectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.secondary)
            .tracking(0.4)
            .textCase(nil)
    }

    private func header(_ group: SessionGroup, count: Int? = nil) -> some View {
        Button { toggle(group.workdir) } label: {
            HStack(spacing: 8) {
                Image(systemName: collapsed.contains(group.workdir) ? "chevron.right" : "chevron.down")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.tertiary)
                    .frame(width: 10)
                Text(group.label)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .tracking(0.3)
                    .textCase(nil)
                Spacer(minLength: 0)
                Text("\(count ?? group.sessions.count)")
                    .font(.system(size: 11, weight: .medium, design: .rounded))
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Color.primary.opacity(0.05), in: Capsule())
            }
            .contentShape(Rectangle())
            .padding(.vertical, 2)
        }
        .buttonStyle(.plain)
    }

    /// Greyed header for an offline host group: dot + name + a relative "last seen" (spec §5).
    private func offlineHeader(_ host: HostView) -> some View {
        HStack(spacing: 8) {
            HostDot(colorIndex: host.colorIndex, size: 8)
            Text(host.displayLabel)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
                .textCase(nil)
            let seen = FleetModelKt.formatLastSeen(nowMs: Int64(Date().timeIntervalSince1970 * 1000),
                                                   lastSeenAt: host.lastSeenAt)
            Text(seen.isEmpty ? "offline" : "offline · \(seen)")
                .font(.caption2)
                .foregroundStyle(.tertiary)
            Spacer(minLength: 0)
        }
        .opacity(0.9)
    }

    @ViewBuilder private func row(
        _ s: SessionInfo,
        host: HostView?,
        projectTag: String? = nil,
        reorderable: Bool = false
    ) -> some View {
        let b = fleet.broker(for: s.id)
        let muted = s.mute?.boolValue ?? false
        let previewEntry = b?.messages[s.id]?.last
        let working = b?.agentWorking[s.id] == true
        // Spinner wins while working; green rail only when idle + unread (native rail parity).
        let unread = selected != s.id && !working && isSessionUnread(
            lastMessageTs: previewEntry?.ts,
            lastReadAt: b?.lastRead[s.id]
        )
        SessionRow(
            session: s,
            preview: previewEntry?.text,
            previewTs: previewEntry?.ts,
            phase: b?.agentPhase[s.id],
            working: working,
            bgOpen: b?.agentBgOpen[s.id] ?? 0,
            muted: muted,
            host: host,
            projectTag: projectTag,
            selected: selected == s.id,
            unread: unread,
            showsDragHint: reorderable,
            isDragging: reorderState.draggingId == s.id
        )
        .contextMenu {
            #if os(macOS)
            if (s.userStatus ?? "in_progress") != "draft" {
                Button { openWindow(id: "session", value: s.id) } label: {
                    Label("Open in New Window", systemImage: "macwindow.badge.plus")
                }
                Divider()
            }
            #endif
            if (s.status ?? "") == "archived" || (s.userStatus ?? "") == "settled" {
                Button { b?.resume(s.id); fleet.refreshArchived() } label: {
                    Label("Resume", systemImage: "arrow.uturn.backward")
                }
                Button { continueTarget = s } label: {
                    Label("Continue in new conversation", systemImage: "bubble.left.and.text.bubble.right")
                }
            } else if (s.userStatus ?? "") == "draft" {
                Button { onOpenDraft(s.id) } label: {
                    Label("Open draft", systemImage: "pencil")
                }
                Button(role: .destructive) { killTarget = s } label: {
                    Label("Discard", systemImage: "trash")
                }
            } else {
                Button { b?.toggleMute(s) } label: {
                    Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
                }
                Button { renameText = s.name; renameTarget = s } label: { Label("Rename", systemImage: "pencil") }
                Button { continueTarget = s } label: {
                    Label("Continue in new conversation", systemImage: "bubble.left.and.text.bubble.right")
                }
                Button(role: .destructive) { killTarget = s } label: { Label("Settle", systemImage: "checkmark.circle") }
            }
        }
    }

    /// Selection without a full-row Button (that blocked drag). Free drag uses onDrag +
    /// DropDelegate for Android/desktop/web-style whole-row reorder within a section.
    @ViewBuilder private func selectableRow(
        _ s: SessionInfo,
        host: HostView?,
        reorderable: Bool,
        projectTag: String? = nil,
        sectionKey: String = "",
        sectionIds: [String] = []
    ) -> some View {
        let ids = sectionIds.isEmpty ? [s.id] : sectionIds
        row(s, host: host, projectTag: projectTag, reorderable: reorderable)
            .contentShape(Rectangle())
            .opacity(reorderState.draggingId == s.id ? 0.35 : 1)
            .simultaneousGesture(TapGesture().onEnded {
                guard !reorderState.isDragging else { return }
                if (s.userStatus ?? "") == "draft" {
                    onOpenDraft(s.id)
                } else {
                    selected = s.id
                    onSessionSelected(s.id)
                }
            })
            .modifier(SessionRowDragReorderModifier(
                enabled: reorderable && !sectionKey.isEmpty,
                sessionId: s.id,
                sessionName: s.name,
                sectionKey: sectionKey,
                sectionIds: ids,
                state: reorderState,
                onCommit: commitReorder
            ))
            .tag(s.id)
            .moveDisabled(true)
            .accessibilityIdentifier(TestIds.sessionRow(s.id))
            .smSessionListRowChrome()
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                swipeButtons(for: s)
            }
    }

    /// Keep swipe actions on the actual List row. Putting them inside the macOS row Button's
    /// label causes SwiftUI to size the reveal controls against the whole button host, producing
    /// the enormous icons seen in the sidebar.
    @ViewBuilder private func swipeButtons(for s: SessionInfo) -> some View {
        let b = fleet.broker(for: s.id)
        let muted = s.mute?.boolValue ?? false
        Button(role: .destructive) { killTarget = s } label: {
            Label("Settle", systemImage: "checkmark.circle")
        }
        Button { renameText = s.name; renameTarget = s } label: {
            Label("Rename", systemImage: "pencil")
        }
        .tint(.gray)
        Button { b?.toggleMute(s) } label: {
            Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
        }
        .tint(Theme.teal)
    }

    private func toggle(_ wd: String) {
        if collapsed.contains(wd) { collapsed.remove(wd) } else { collapsed.insert(wd) }
        SessionsListView.saveCollapsed(collapsed)
    }
    private static let collapsedKey = "cmux:collapsed-paths"
    private static func loadCollapsed() -> Set<String> {
        Set((UserDefaults.standard.array(forKey: collapsedKey) as? [String]) ?? [])
    }
    private static func saveCollapsed(_ s: Set<String>) {
        UserDefaults.standard.set(Array(s), forKey: collapsedKey)
    }
}

private struct SidebarArchiveRevealBar: View {
    let state: SidebarArchiveRevealState
    let onArchived: () -> Void

    var body: some View {
        #if os(macOS)
        ZStack(alignment: .top) {
            button
                .frame(height: SidebarArchiveRevealState.maximumHeight)
                .offset(y: visibleHeight - SidebarArchiveRevealState.maximumHeight)
        }
        .frame(height: SidebarArchiveRevealState.maximumHeight)
        .clipped()
        // Do not replace the List's scroll hit target while a trackpad gesture is in progress.
        .allowsHitTesting(state.isLatched)
        #else
        if visibleHeight > 0.5 {
            button
                .frame(height: visibleHeight)
                .clipped()
        }
        #endif
    }

    private var visibleHeight: CGFloat {
        min(SidebarArchiveRevealState.maximumHeight, max(0, state.visibleHeight))
    }

    private var button: some View {
        Button {
            withAnimation(.snappy(duration: 0.2)) { state.close() }
            onArchived()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "archivebox").font(.title3).foregroundStyle(.secondary).frame(width: 26)
                Text("Archived").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .opacity(min(1, visibleHeight / SidebarArchiveRevealState.latchThreshold))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(.bar)
    }
}

/// Server-authoritative unread: last message newer than last_read_at (or no pointer).
/// Mirrors shared KMP `isSessionUnread` / web `stores/unread.ts` (ISO string compare).
func isSessionUnread(lastMessageTs: String?, lastReadAt: String?) -> Bool {
    guard let lastMessageTs, !lastMessageTs.isEmpty else { return false }
    guard let lastReadAt, !lastReadAt.isEmpty else { return true }
    return lastMessageTs > lastReadAt
}

struct SessionRow: View {
    let session: SessionInfo
    var preview: String?
    /// ISO timestamp of the last message (relative time on the trailing edge).
    var previewTs: String? = nil
    var phase: String?
    // `working`/`bgOpen` are passed IN from the parent (which reads them in its own `body`)
    // instead of read from `broker` here. A child View's own @Observable read inside a `List`
    // row can go stale (e.g. while the row is off-screen behind the pushed chat on iPhone) and
    // miss the re-invalidation — so the spinner never appeared even though the flag was true,
    // while the chat view (reading the same value in its own body) updated fine. Hoisting the
    // read to SessionsListView.body — like `preview`/`phase`, and like the collapsed rail —
    // keeps the row a pure value view that always repaints with live state.
    var working: Bool = false
    var bgOpen: Int = 0
    var muted: Bool = false
    /// The owning host, when ≥2 hosts are paired — renders the per-row badge (nil = single-host).
    var host: HostView? = nil
    /// Flat-mode project leaf tag (web projectLabel).
    var projectTag: String? = nil
    var selected: Bool = false
    /// Server-authoritative unread (last message newer than last_read_at); bold title when true.
    var unread: Bool = false
    /// Subtle grab affordance for reorderable rows (macOS hover).
    var showsDragHint: Bool = false
    /// Dimmed list slot while this row's free-drag ghost is active.
    var isDragging: Bool = false

    #if os(macOS)
    @State private var hovered = false
    #endif

    private var isDraft: Bool { (session.userStatus ?? "") == "draft" }
    private var timeLabel: String {
        guard let previewTs, !previewTs.isEmpty else { return "" }
        let full = relTime(previewTs)
        // Compact list form: drop the trailing " ago" ("3m ago" → "3m").
        if full.hasSuffix(" ago") { return String(full.dropLast(4)) }
        if full == "just now" { return "now" }
        if full == "never" { return "" }
        return full
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            leadingIndicator
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(session.name)
                        .font(titleFont)
                        .fontWeight(selected || unread ? .semibold : .medium)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if muted {
                        Image(systemName: "bell.slash.fill")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    if let projectTag {
                        Text(projectTag)
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.primary.opacity(0.05), in: Capsule())
                    }
                    Spacer(minLength: 4)
                    if let host { HostBadge(host: host) }
                    if !timeLabel.isEmpty {
                        Text(timeLabel)
                            .font(.system(size: 11, weight: .medium, design: .rounded))
                            .foregroundStyle(.tertiary)
                            .monospacedDigit()
                    }
                }
                Text(previewLine)
                    .font(previewFont)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            #if os(macOS)
            if showsDragHint {
                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.tertiary.opacity(hovered || selected ? 0.9 : 0.35))
                    .frame(width: 12)
                    .accessibilityHidden(true)
            }
            #endif
        }
        .smSessionRowSurface(selected: selected, accented: false, hovered: isHovered && !isDragging)
        .scaleEffect(isDragging ? 0.98 : 1)
        .animation(.easeOut(duration: 0.12), value: isDragging)
        #if os(macOS)
        .onHover { hovered = $0 }
        #endif
    }

    private var isHovered: Bool {
        #if os(macOS)
        hovered
        #else
        false
        #endif
    }

    private var previewLine: String {
        if let preview, !preview.isEmpty {
            return preview.replacingOccurrences(of: "\n", with: " ")
        }
        if isDraft { return "draft" }
        return session.agent
    }

    @ViewBuilder private var leadingIndicator: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(selected ? Theme.teal.opacity(0.16) : Color.primary.opacity(0.045))
                .frame(width: 28, height: 28)
            if isDraft {
                Image(systemName: "pencil")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.teal.opacity(0.9))
            } else {
                // Unread lives in the rail (green dot when idle); spinner wins while working.
                SessionStatusRail(git: nil, working: working, bgOpen: bgOpen, unread: unread)
            }
        }
        .frame(width: 28, height: 28)
    }

    private var titleFont: Font {
        #if os(macOS)
        .system(size: 13.5, weight: .medium)
        #else
        .subheadline
        #endif
    }

    private var previewFont: Font {
        #if os(macOS)
        .system(size: 11.5)
        #else
        .caption
        #endif
    }
}

private extension View {
    /// Independent soft rows (not joined Settings-style cards): hover wash, teal selection,
    /// no hairline dividers. Used on macOS and the plain iOS/iPad session list.
    @ViewBuilder func smSessionRowSurface(
        selected: Bool,
        accented: Bool = false,
        hovered: Bool = false
    ) -> some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        let fill: Color = {
            if selected { return Theme.teal.opacity(0.14) }
            if accented { return Theme.teal.opacity(0.08) }
            if hovered { return Color.primary.opacity(0.05) }
            return Color.primary.opacity(0.028)
        }()
        self
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(fill, in: shape)
            .overlay(alignment: .leading) {
                if selected {
                    Capsule()
                        .fill(Theme.teal)
                        .frame(width: 3, height: 22)
                        .padding(.leading, 3)
                }
            }
            .overlay {
                if accented {
                    shape.strokeBorder(Theme.teal.opacity(0.18), lineWidth: 1)
                }
            }
            .animation(.easeOut(duration: 0.12), value: selected)
            .animation(.easeOut(duration: 0.1), value: hovered)
    }

    /// List row chrome: clear backgrounds, hidden separators, tight vertical rhythm.
    @ViewBuilder func smSessionListRowChrome(
        spacing: EdgeInsets = EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0)
    ) -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(spacing)
    }
}
