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

private enum SidebarRowPosition {
    case only, first, middle, last

    static func at(_ index: Int, count: Int) -> SidebarRowPosition {
        if count == 1 { return .only }
        if index == 0 { return .first }
        if index == count - 1 { return .last }
        return .middle
    }

    var roundsTop: Bool { self == .only || self == .first }
    var roundsBottom: Bool { self == .only || self == .last }
    var hasDivider: Bool { self == .first || self == .middle }
}

/// Sidebar / root list — the merged multi-host fleet (spec §5): grouped (PA + project) sessions
/// across every paired host, a per-row host badge + an `All · <host…> · +` filter chip row (both
/// shown only when ≥2 hosts are paired), and offline hosts rendered as greyed "last seen" groups.
/// Per-row reads (preview/agent state) and actions (settle/rename/mute) route to the session's OWNING
/// `BrokerSession` via `Fleet`. Single-host is the unchanged path: no chips, no badges.
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
    @State private var groupByProject = UserDefaults.standard.object(forKey: "cmux:group-by-project") as? Bool ?? false
    @State private var settledExpanded: Set<String> = []
    @State private var flatSettledExpanded = false
    #if os(iOS)
    @State private var listEditMode: EditMode = .inactive
    #endif

    var body: some View {
        let owner = fleet.sessionHost
        let hostViews = fleet.hostViews
        let hostByRecord = Dictionary(hostViews.map { ($0.recordId, $0) }, uniquingKeysWith: { a, _ in a })
        let multiHost = fleet.multiHost

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
            list(owner: owner, hostByRecord: hostByRecord, multiHost: multiHost)
        }
    }

    private func list(owner: [String: String], hostByRecord: [String: HostView], multiHost: Bool) -> some View {
        List(selection: $selected) {
            Section {
                Button(action: onNewSession) {
                    HStack(spacing: 12) {
                        Image(systemName: "plus.circle.fill").font(.title2).foregroundStyle(Theme.teal)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Start a new session").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                            Text("Start a project and send your first message")
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .smMacSidebarCard(position: .only, accented: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("new-session")
                .moveDisabled(true)
                .deleteDisabled(true)
                #if os(macOS)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 8, trailing: 0))
                #endif
            }

            Section {
                Toggle(isOn: Binding(
                    get: { groupByProject },
                    set: {
                        groupByProject = $0
                        UserDefaults.standard.set($0, forKey: "cmux:group-by-project")
                    }
                )) {
                    Label("Group by project", systemImage: "folder")
                }
                .toggleStyle(.switch)
                .accessibilityLabel("Group by project")
                .accessibilityIdentifier("group-by-project")
                .moveDisabled(true)
                .deleteDisabled(true)
                #if os(iOS)
                Button {
                    withAnimation {
                        listEditMode = listEditMode == .active ? .inactive : .active
                    }
                } label: {
                    Label(
                        listEditMode == .active ? "Done reordering" : "Reorder sessions",
                        systemImage: listEditMode == .active ? "checkmark" : "arrow.up.arrow.down"
                    )
                }
                .accessibilityIdentifier("reorder-sessions")
                .moveDisabled(true)
                .deleteDisabled(true)
                #endif
            }

            if groupByProject {
                ForEach(fleet.onlineGroups(), id: \.workdir) { group in
                    onlineSection(group, owner: owner, hostByRecord: hostByRecord, multiHost: multiHost)
                }
            } else {
                flatSectionsView(owner: owner, hostByRecord: hostByRecord, multiHost: multiHost)
            }

            // Offline hosts: greyed group per host with a "last seen" header (multi-host only).
            ForEach(fleet.offlineHostGroups(), id: \.host.recordId) { entry in
                offlineSection(host: entry.host, sessions: entry.sessions, hostByRecord: hostByRecord)
            }
        }
        #if os(macOS)
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.smGroupedBackground)
        .contentMargins(.horizontal, 12, for: .scrollContent)
        #else
        .smInsetGroupedListStyle()
        // iOS List only allows onMove (system drag ghost) in edit mode. Toggle via
        // the "Reorder" control in the group-by section so selection stays normal.
        .environment(\.editMode, $listEditMode)
        #endif
        #if os(macOS)
        // The reveal must not participate in scroll layout on AppKit. The overlay also keeps a
        // fixed frame while its contents translate into view, so rubber-banding never changes
        // the List's geometry.
        .overlay(alignment: .top) {
            SidebarArchiveRevealBar(state: archiveReveal, onArchived: onArchived)
        }
        #else
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
    }

    /// Flat task list (web !groupByProject): In Progress / Drafts / Settled across all projects.
    @ViewBuilder private func flatSectionsView(
        owner: [String: String],
        hostByRecord: [String: HostView],
        multiHost: Bool
    ) -> some View {
        let online = flatOnlineSessions(owner: owner, multiHost: multiHost)
        let lastTs: (SessionInfo) -> String = { s in
            fleet.broker(for: s.id)?.messages[s.id]?.last?.ts ?? ""
        }
        let sections = buildTaskSections(
            list: combinedTaskSessions(live: online, archived: fleet.archivedForList),
            lastTs: lastTs
        )
        // PA pin (web SessionTaskList paSection) — not in task sections.
        let pas = online.filter { $0.role == "personal_assistant" }
            .sorted { lastTs($0) > lastTs($1) }
        if !pas.isEmpty {
            Section {
                ForEach(Array(pas.enumerated()), id: \.element.id) { index, session in
                    let recordId = owner[session.id] ?? ""
                    let rowHost: HostView? = multiHost ? hostByRecord[recordId] : nil
                    let position = SidebarRowPosition.at(index, count: pas.count)
                    selectableRow(session, host: rowHost, position: position)
                }
            } header: {
                Text("Personal Assistants")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
        }
        ForEach(sections, id: \.key) { section in
            if section.key == .settled {
                Section {
                    Button {
                        flatSettledExpanded.toggle()
                    } label: {
                        Text(flatSettledExpanded
                             ? "Hide \(section.sessions.count) settled"
                             : "Show \(section.sessions.count) settled")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if flatSettledExpanded {
                        ForEach(Array(section.sessions.enumerated()), id: \.element.id) { index, session in
                            let recordId = owner[session.id] ?? ""
                            let rowHost: HostView? = multiHost ? hostByRecord[recordId] : nil
                            let position = SidebarRowPosition.at(index, count: section.sessions.count)
                            selectableRow(session, host: rowHost, position: position,
                                          projectTag: projectLabel(session: session, home: inferHomeDir(workdir: session.workdir)))
                        }
                        .moveDisabled(true)
                        .deleteDisabled(true)
                    }
                }
            } else {
                Section {
                    ForEach(Array(section.sessions.enumerated()), id: \.element.id) { index, session in
                        let recordId = owner[session.id] ?? ""
                        let rowHost: HostView? = multiHost ? hostByRecord[recordId] : nil
                        let position = SidebarRowPosition.at(index, count: section.sessions.count)
                        selectableRow(session, host: rowHost, position: position,
                                      projectTag: projectLabel(session: session, home: inferHomeDir(workdir: session.workdir)))
                    }
                    // onMove must attach to DynamicViewContent before View-erasing modifiers.
                    .onMove { indices, newOffset in
                        var ids = section.sessions.map(\.id)
                        ids.move(fromOffsets: indices, toOffset: newOffset)
                        onReorder(ids)
                    }
                    .deleteDisabled(true)
                } header: {
                    Text(section.label.uppercased())
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
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
        multiHost: Bool
    ) -> some View {
        let openSessions: [SessionInfo] = {
            if group.workdir == PA_GROUP_KEY { return group.sessions }
            let secs = group.sections
            if secs.isEmpty { return group.sessions }
            return secs.filter { $0.key != .settled }.flatMap { $0.sessions }
        }()
        let settled = group.sections.first(where: { $0.key == .settled })?.sessions ?? []
        let activeCount = group.workdir == PA_GROUP_KEY ? group.sessions.count : openSessions.count
        Section {
            if !collapsed.contains(group.workdir) {
                ForEach(Array(openSessions.enumerated()), id: \.element.id) { index, session in
                    let recordId = owner[session.id] ?? ""
                    let rowHost: HostView? = multiHost ? hostByRecord[recordId] : nil
                    let position = SidebarRowPosition.at(index, count: openSessions.count)
                    selectableRow(session, host: rowHost, position: position)
                }
                // onMove must attach to DynamicViewContent before View-erasing modifiers.
                .onMove { indices, newOffset in
                    guard group.workdir != PA_GROUP_KEY else { return }
                    var ids = openSessions.map(\.id)
                    ids.move(fromOffsets: indices, toOffset: newOffset)
                    onReorder(ids)
                }
                .deleteDisabled(true)
                .moveDisabled(group.workdir == PA_GROUP_KEY)
                if !settled.isEmpty {
                    Button {
                        if settledExpanded.contains(group.workdir) { settledExpanded.remove(group.workdir) }
                        else { settledExpanded.insert(group.workdir) }
                    } label: {
                        Text(settledExpanded.contains(group.workdir)
                             ? "Hide \(settled.count) settled"
                             : "Show \(settled.count) settled")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if settledExpanded.contains(group.workdir) {
                        ForEach(settled, id: \.id) { session in
                            let recordId = owner[session.id] ?? ""
                            let rowHost: HostView? = multiHost ? hostByRecord[recordId] : nil
                            selectableRow(session, host: rowHost, position: .middle)
                        }
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
        hostByRecord: [String: HostView]
    ) -> some View {
        Section {
            ForEach(Array(sessions.enumerated()), id: \.element.id) { index, session in
                let rowHost = hostByRecord[host.recordId]
                let position = SidebarRowPosition.at(index, count: sessions.count)
                selectableRow(session, host: rowHost, position: position)
                    .opacity(0.5)
            }
        } header: {
            offlineHeader(host)
        }
    }

    private func header(_ group: SessionGroup, count: Int? = nil) -> some View {
        Button { toggle(group.workdir) } label: {
            HStack(spacing: 6) {
                Image(systemName: collapsed.contains(group.workdir) ? "chevron.right" : "chevron.down")
                    .font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                Text(group.label).textCase(nil)
                Spacer()
                Text("\(count ?? group.sessions.count)").foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Greyed header for an offline host group: dot + name + a relative "last seen" (spec §5).
    private func offlineHeader(_ host: HostView) -> some View {
        HStack(spacing: 6) {
            HostDot(colorIndex: host.colorIndex, size: 8)
            Text(host.displayLabel).textCase(nil).foregroundStyle(.secondary)
            let seen = FleetModelKt.formatLastSeen(nowMs: Int64(Date().timeIntervalSince1970 * 1000),
                                                   lastSeenAt: host.lastSeenAt)
            Text(seen.isEmpty ? "offline" : "offline · \(seen)")
                .font(.caption2).foregroundStyle(.tertiary)
            Spacer()
        }
        .opacity(0.85)
    }

    @ViewBuilder private func row(_ s: SessionInfo, host: HostView?, position: SidebarRowPosition, projectTag: String? = nil) -> some View {
        let b = fleet.broker(for: s.id)
        let muted = s.mute?.boolValue ?? false
        SessionRow(session: s, preview: b?.messages[s.id]?.last?.text,
                   phase: b?.agentPhase[s.id],
                   working: b?.agentWorking[s.id] == true,
                   bgOpen: b?.agentBgOpen[s.id] ?? 0, muted: muted, host: host,
                   projectTag: projectTag)
            .smMacSidebarCard(position: position, selected: selected == s.id)
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
                    Button(role: .destructive) { killTarget = s } label: { Label("Settle", systemImage: "checkmark.circle") }
                }
            }
    }

    /// AppKit's `List(selection:)` does not emit a selection change when the already-selected
    /// row is clicked. Make the row a real button on macOS so callers can still react to that
    /// click (notably, leaving the New Session workspace), while retaining native list selection.
    @ViewBuilder private func selectableRow(
        _ s: SessionInfo,
        host: HostView?,
        position: SidebarRowPosition,
        projectTag: String? = nil
    ) -> some View {
        #if os(macOS)
        Button {
            if (s.userStatus ?? "") == "draft" {
                onOpenDraft(s.id)
            } else {
                selected = s.id
                onSessionSelected(s.id)
            }
        } label: {
            row(s, host: host, position: position, projectTag: projectTag)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .tag(s.id)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            swipeButtons(for: s)
        }
        #else
        row(s, host: host, position: position, projectTag: projectTag)
            .tag(s.id)
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                swipeButtons(for: s)
            }
        #endif
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

struct SessionRow: View {
    let session: SessionInfo
    var preview: String?
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

    private var isDraft: Bool { (session.userStatus ?? "") == "draft" }

    var body: some View {
        HStack(spacing: 8) {
            // Web parity: agent working-state only in the list (no per-row git).
            if isDraft {
                Image(systemName: "pencil")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.teal.opacity(0.85))
                    .frame(width: 14)
            } else {
                SessionStatusRail(git: nil, working: working, bgOpen: bgOpen)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(titleFont.weight(.semibold)).lineLimit(1)
                    if muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary) }
                    if let projectTag {
                        Text(projectTag)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if let host { HostBadge(host: host) }
                }
                Text(preview ?? (isDraft ? "draft" : session.agent))
                    .font(previewFont).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 3)
    }

    private var titleFont: Font {
        #if os(macOS)
        .body
        #else
        .subheadline
        #endif
    }

    private var previewFont: Font {
        #if os(macOS)
        .callout
        #else
        .caption
        #endif
    }
}

private extension View {
    /// iOS gets its grouped cards from `.insetGrouped`; AppKit's `.inset` has no equivalent,
    /// so give Mac rows an explicit grouped surface and an obvious teal selection state.
    @ViewBuilder func smMacSidebarCard(
        position: SidebarRowPosition,
        selected: Bool = false,
        accented: Bool = false
    ) -> some View {
        #if os(macOS)
        let radius: CGFloat = 10
        let shape = UnevenRoundedRectangle(
            cornerRadii: RectangleCornerRadii(
                topLeading: position.roundsTop ? radius : 0,
                bottomLeading: position.roundsBottom ? radius : 0,
                bottomTrailing: position.roundsBottom ? radius : 0,
                topTrailing: position.roundsTop ? radius : 0
            ),
            style: .continuous
        )
        self
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                selected ? Theme.teal.opacity(0.18)
                    : (accented ? Theme.teal.opacity(0.10) : Color(nsColor: .controlBackgroundColor)),
                in: shape
            )
            .overlay {
                if selected { shape.strokeBorder(Theme.teal.opacity(0.34)) }
            }
            .overlay(alignment: .bottom) {
                if position.hasDivider {
                    Divider().padding(.leading, 10)
                }
            }
        #else
        if accented { self.padding(.vertical, 3) } else { self }
        #endif
    }
}
