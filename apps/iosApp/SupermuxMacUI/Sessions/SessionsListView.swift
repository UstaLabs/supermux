import SwiftUI
import Shared

/// Sidebar / root list — the merged multi-host fleet (spec §5): grouped (PA + project) sessions
/// across every paired host, a per-row host badge (All filter only) + an `All · <host…> · +` filter
/// chip row (both when ≥2 hosts are paired), and offline hosts as greyed "last seen" groups.
/// Per-row reads (preview/agent state) and actions (settle/rename/mute) route to the session's OWNING
/// `BrokerSession` via `Fleet`. Single-host is the unchanged path: no chips, no badges.
///
/// Interaction: free drag reorder (Android / desktop / web parity) via onDrag + drop with a
/// live working order — not List.onMove/edit mode. macOS: drag from the trailing grip only
/// (whole-row onDrag races trackpad rubber-band and jumps the AppKit List); iPad: whole-row
/// press+drag; iPhone: edit-mode grabbers. Settled / PA / offline rows stay fixed.
///
/// Cost: this body re-runs on EVERY fleet-wide observable change — a `message_append` or an
/// `agent_state` tick for any session on any host — so it runs many times a second while agents
/// work, and lands in the middle of trackpad scrolls. Everything it derives is therefore derived
/// ONCE per pass and read per row: `Fleet.index()` for owner/broker/last-message, and
/// `projectTagsBySession` for the labels. `SessionListRenderCostTests` holds the line.
struct SessionsListView: View {
    let fleet: Fleet
    @Binding var selected: String?
    var onNewSession: () -> Void
    var onAddHost: () -> Void = {}
    var onSessionSelected: (String) -> Void = { _ in }
    var onOpenDraft: (String) -> Void = { _ in }
    var onReorder: ([String]) -> Void = { _ in }

    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #else
    @Environment(\.horizontalSizeClass) private var hSize
    #endif

    /// iPhone (and any compact-width iPad multitasking slot) gets the native full-width inset
    /// list — no per-row card, hairline separators, Mail/Messages density. Regular width (the
    /// iPad sidebar) and macOS keep the sidebar pill treatment, which is correct there.
    private var phoneList: Bool {
        #if os(macOS)
        false
        #else
        hSize == .compact
        #endif
    }

    @State private var collapsed: Set<String> = SessionsListView.loadCollapsed()
    @State private var renameTarget: SessionInfo?
    @State private var renameText = ""
    @State private var killTarget: SessionInfo?
    /// Source session for "Continue in new conversation" (web SessionContextMenu parity).
    @State private var continueTarget: SessionInfo?
    /// Shared with the compact shell's overflow menu (`RootView`), which is where iPhone puts this
    /// instead of spending a list row on it — @AppStorage keeps both in sync off the same key.
    @AppStorage("cmux:group-by-project") private var groupByProject = false
    /// iPhone large-title search (`.searchable`). Empty on the sidebar platforms.
    @State private var search = ""
    #if os(iOS)
    /// iPhone reordering. The free-drag path can never fire here: `.onDrag` and `.contextMenu`
    /// both bind to long-press and the menu wins, so a press on a row opens Mute/Rename/Settle
    /// and the drag never begins. iOS reorders in edit mode with grabbers (Mail, Reminders), so
    /// that is what the phone gets; macOS/iPad keep the free drag, where press+drag works.
    @State private var editMode: EditMode = .inactive
    private var isEditing: Bool { editMode == .active }
    #else
    private var isEditing: Bool { false }
    #endif
    @State private var settledExpanded: Set<String> = []
    @State private var flatSettledExpanded = false
    /// Live whole-row drag reorder (section-scoped). See `SessionSectionReorderState`.
    @State private var reorderState = SessionSectionReorderState()

    var body: some View {
        // ONE walk of the fleet per render — owner map, per-row broker, Settled timestamps.
        // Everything below reads it; nothing re-scans. See `Fleet.index()`.
        let index = fleet.index()
        let owner = index.ownerBySession
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
            list(index: index, hostByRecord: hostByRecord, multiHost: multiHost, showRowHostBadge: showRowHostBadge)
        }
    }

    private func list(index: FleetIndex, hostByRecord: [String: HostView], multiHost: Bool, showRowHostBadge: Bool) -> some View {
        List(selection: $selected) {
            if !phoneList { sidebarChromeSections }

            if groupByProject {
                ForEach(fleet.onlineGroups(index), id: \.workdir) { group in
                    onlineSection(group, index: index, hostByRecord: hostByRecord, showRowHostBadge: showRowHostBadge)
                }
            } else {
                flatSectionsView(index: index, hostByRecord: hostByRecord, multiHost: multiHost, showRowHostBadge: showRowHostBadge)
            }

            // Offline hosts: greyed group per host with a "last seen" header (multi-host only).
            ForEach(fleet.offlineHostGroups(index), id: \.host.recordId) { entry in
                offlineSection(host: entry.host, sessions: entry.sessions, index: index,
                               hostByRecord: hostByRecord, showRowHostBadge: showRowHostBadge)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.smGroupedBackground)
        .accessibilityIdentifier(TestIds.sessionList)
        #if os(macOS)
        .contentMargins(.horizontal, 10, for: .scrollContent)
        // Bounce only when content overflows — a short list that rubber-bands against nothing
        // has no travel to give back, so the release reads as a snap.
        .scrollBounceBehavior(.basedOnSize)
        // No animated geometry under an AppKit-backed List: an animating row height re-measures
        // NSTableView mid-gesture. This clears the transaction for the WHOLE subtree, so the
        // settled expand/collapse and the drag spring snap here too — deliberate, and the reason
        // those `withAnimation` calls look inert on macOS.
        .transaction { $0.animation = nil }
        #else
        // iPhone rows run edge-to-edge and carry their own 16pt leading inset (see
        // `smSessionListRowChrome`), so the scroll content must NOT be inset again.
        .contentMargins(.horizontal, phoneList ? 0 : 12, for: .scrollContent)
        .environment(\.editMode, $editMode)
        #endif
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
        .modifier(PhoneSessionListChrome(
            enabled: phoneList,
            search: $search,
            onNewSession: onNewSession,
            isEditing: isEditing,
            onToggleEditing: toggleEditing
        ))
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
            // Settled-from-archive rows have no live owner; fall back to the active host
            // (same host that supplied `archivedForList`).
            if let b = fleet.broker(forSessionOrArchived: item.session.id) {
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

    /// The two chrome rows the sidebar keeps in-list. On iPhone they'd cost a third of the first
    /// screen before a single session appears, so there they move to the nav bar instead: New
    /// Session becomes the compose button, Group by project joins the shell's ⋯ menu.
    @ViewBuilder private var sidebarChromeSections: some View {
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
                Toggle("", isOn: $groupByProject)
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
    }

    private func commitReorder(_ orderedIds: [String]) {
        guard !orderedIds.isEmpty else { return }
        onReorder(orderedIds)
    }

    private func toggleEditing() {
        #if os(iOS)
        withAnimation(.snappy(duration: 0.22)) {
            editMode = editMode == .active ? .inactive : .active
        }
        #endif
    }

    /// A section's `.onMove` in edit mode. `ids` is that section's order as rendered, so the moved
    /// result is exactly what the free-drag path commits on macOS — same `onReorder` contract.
    private func moveRows(ids: [String], from: IndexSet, to: Int) {
        commitReorder(reorderedSessionIds(ids, from: from, to: to))
    }

    /// Identifiable wrapper so `.sheet(item:)` can present continue for a session row.
    private struct ContinueSheetItem: Identifiable {
        let session: SessionInfo
        var id: String { session.id }
    }

    /// Flat task list (web !groupByProject): In Progress / Drafts / Settled across all projects.
    @ViewBuilder private func flatSectionsView(
        index: FleetIndex,
        hostByRecord: [String: HostView],
        multiHost: Bool,
        showRowHostBadge: Bool
    ) -> some View {
        let owner = index.ownerBySession
        let online = flatOnlineSessions(index: index, multiHost: multiHost)
        // The Settled recency key of every row (hundreds, once archived sessions are folded in)
        // comes from the render's one fleet walk. See `Fleet.index()`.
        let ts = index.lastTsBySession
        let sections = buildTaskSections(
            list: combinedTaskSessions(live: online, archived: fleet.archivedForList),
            lastTs: { ts[$0.id] ?? "" }
        )
        // Labelled ONCE per render, then read per row. Deciding whether to show the tag at all
        // needs every session's label anyway, and asking Kotlin again per row (formatWorkdir +
        // a home-dir probe, both bridged) doubled the cost of the most expensive view in the app.
        // The tag only tells you anything when the list actually spans projects — with a single
        // project it repeats the same word on every row.
        let tags = projectTagsBySession(online)
        let showProjectTag = Set(tags.values).count > 1
        let tag: (SessionInfo) -> String? = { s in showProjectTag ? tags[s.id] : nil }
        // PA pin (web SessionTaskList paSection) — not in task sections.
        // Stable sortOrder only; new messages must not reshuffle rows.
        let pas = sessionsByUserOrder(sessions: online.filter { $0.role == "personal_assistant" })
        if !pas.isEmpty {
            Section {
                ForEach(pas, id: \.id) { session in
                    let recordId = owner[session.id] ?? ""
                    let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                    selectableRow(session, host: rowHost, index: index, reorderable: false,
                                  isLast: session.id == pas.last?.id)
                }
                .moveDisabled(true)
                .deleteDisabled(true)
            } header: {
                sessionSectionHeader("Personal Assistants")
            }
        }
        ForEach(sections, id: \.key) { section in
            if section.key == .settled {
                // Settled folds in archived sessions, which don't come through
                // `flatOnlineSessions` — so the search narrowing has to be applied here too.
                let settled = searchFiltered(section.sessions)
                if !(settled.isEmpty && !search.isEmpty) {
                Section {
                    Button {
                        withAnimation(.snappy(duration: 0.2)) { flatSettledExpanded.toggle() }
                    } label: {
                        Text(flatSettledExpanded
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
                    // Collapsed, the toggle IS the section's only row — so it carries no rule.
                    .smSessionListRowChrome(phone: phoneList, isLast: !flatSettledExpanded)
                    if flatSettledExpanded {
                        ForEach(settled, id: \.id) { session in
                            let recordId = owner[session.id] ?? ""
                            let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                            selectableRow(session, host: rowHost, index: index, reorderable: false,
                                          projectTag: tag(session),
                                          isLast: session.id == settled.last?.id)
                        }
                        .moveDisabled(true)
                        .deleteDisabled(true)
                    }
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
                            index: index,
                            reorderable: true,
                            projectTag: tag(session),
                            sectionKey: sectionKey,
                            sectionIds: ids,
                            isLast: session.id == rows.last?.id
                        )
                    }
                    // `.onMove` must sit on the ForEach itself — `deleteDisabled` erases
                    // DynamicViewContent to a plain View, and the member disappears.
                    .onMove { from, to in moveRows(ids: rows.map(\.id), from: from, to: to) }
                    .deleteDisabled(true)
                } header: {
                    sessionSectionHeader(section.label)
                }
            }
        }
    }

    private func flatOnlineSessions(index: FleetIndex, multiHost: Bool) -> [SessionInfo] {
        let shown = searchFiltered(fleet.filteredSessions(index))
        guard multiHost else { return shown }
        let offline = Set(fleet.hostViews.filter { !$0.online }.map { $0.recordId })
        return shown.filter { !offline.contains(index.ownerBySession[$0.id] ?? "") }
    }

    /// iPhone `.searchable` narrowing — name, project path and agent. A no-op (identity) whenever
    /// the field is empty, so the sidebar platforms never pay for it.
    private func searchFiltered(_ list: [SessionInfo]) -> [SessionInfo] {
        let q = search.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return list }
        return list.filter {
            $0.name.lowercased().contains(q)
                || $0.workdir.lowercased().contains(q)
                || $0.agent.lowercased().contains(q)
        }
    }

    @ViewBuilder private func onlineSection(
        _ group: SessionGroup,
        index: FleetIndex,
        hostByRecord: [String: HostView],
        showRowHostBadge: Bool
    ) -> some View {
        let owner = index.ownerBySession
        let openSessions: [SessionInfo] = searchFiltered({
            if group.workdir == PA_GROUP_KEY { return group.sessions }
            let secs = group.sections
            if secs.isEmpty { return group.sessions }
            return secs.filter { $0.key != .settled }.flatMap { $0.sessions }
        }())
        let settled = searchFiltered(group.sections.first(where: { $0.key == .settled })?.sessions ?? [])
        let activeCount = openSessions.count
        let canReorderGroup = group.workdir != PA_GROUP_KEY
        let sectionKey = "group:\(group.workdir)"
        let baseOpen = openSessions
        let rows = canReorderGroup
            ? reorderState.displaySessions(sectionKey: sectionKey, fallback: baseOpen)
            : baseOpen
        let ids = baseOpen.map(\.id)
        // A search that matches nothing in this project shouldn't leave a bare header behind.
        if !(openSessions.isEmpty && settled.isEmpty && !search.isEmpty) {
        // The settled toggle (and its rows) extend this section below the open rows, so the last
        // open row only ends the section when there is no settled block under it.
        let openRowIsLast = settled.isEmpty
        Section {
            // macOS AppKit List swallows Button taps in `header:`. Put the chevron
            // row in the section body so collapse actually fires.
            #if os(macOS)
            header(group, count: activeCount)
                .moveDisabled(true)
                .deleteDisabled(true)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            #endif
            if !collapsed.contains(group.workdir) {
                ForEach(rows, id: \.id) { session in
                    let recordId = owner[session.id] ?? ""
                    let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                    selectableRow(
                        session,
                        host: rowHost,
                        index: index,
                        reorderable: canReorderGroup,
                        sectionKey: sectionKey,
                        sectionIds: ids,
                        isLast: openRowIsLast && session.id == rows.last?.id
                    )
                }
                .onMove { from, to in
                    guard canReorderGroup else { return }
                    moveRows(ids: rows.map(\.id), from: from, to: to)
                }
                .deleteDisabled(true)
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
                    // Collapsed, the toggle ends the section — so it carries no rule.
                    .smSessionListRowChrome(phone: phoneList,
                                            isLast: !settledExpanded.contains(group.workdir))
                    if settledExpanded.contains(group.workdir) {
                        ForEach(settled, id: \.id) { session in
                            let recordId = owner[session.id] ?? ""
                            let rowHost: HostView? = showRowHostBadge ? hostByRecord[recordId] : nil
                            selectableRow(session, host: rowHost, index: index, reorderable: false,
                                          isLast: session.id == settled.last?.id)
                        }
                        .moveDisabled(true)
                        .deleteDisabled(true)
                    }
                }
            }
        } header: {
            #if os(macOS)
            EmptyView()
            #else
            header(group, count: activeCount)
            #endif
        }
        }
    }

    @ViewBuilder private func offlineSection(
        host: HostView,
        sessions rawSessions: [SessionInfo],
        index: FleetIndex,
        hostByRecord: [String: HostView],
        showRowHostBadge: Bool
    ) -> some View {
        let sessions = searchFiltered(rawSessions)
        if !sessions.isEmpty {
        Section {
            ForEach(sessions, id: \.id) { session in
                let rowHost: HostView? = showRowHostBadge ? hostByRecord[host.recordId] : nil
                selectableRow(session, host: rowHost, index: index, reorderable: false,
                              isLast: session.id == sessions.last?.id)
                    .opacity(0.55)
            }
            .moveDisabled(true)
            .deleteDisabled(true)
        } header: {
            offlineHeader(host)
        }
        }
    }

    private func sessionSectionHeader(_ title: String) -> some View {
        Text(title)
            // iPhone: a real Dynamic Type style so headers scale with the rest of the list.
            // Sidebar: the tight 11pt tracked caps that read correctly at sidebar density.
            .font(phoneList ? .footnote.weight(.semibold) : .system(size: 11, weight: .semibold))
            .foregroundStyle(.secondary)
            .tracking(phoneList ? 0 : 0.4)
            .textCase(nil)
            .smSessionSectionHeaderChrome(phone: phoneList)
    }

    /// The shared group label is a shortened workdir (`…/local/bisiklet-arastirma`) — right for a
    /// narrow sidebar, but a path fragment reads as engineering internals on a phone. iPhone shows
    /// the project leaf, matching the tag the flat list already puts on its rows.
    private func groupHeaderLabel(_ group: SessionGroup) -> String {
        guard phoneList, group.workdir != PA_GROUP_KEY else { return group.label }
        let leaf = group.workdir.split(separator: "/").last.map(String.init) ?? ""
        return leaf.isEmpty ? group.label : leaf
    }

    private func header(_ group: SessionGroup, count: Int? = nil) -> some View {
        Button { toggle(group.workdir) } label: {
            HStack(spacing: 8) {
                Image(systemName: collapsed.contains(group.workdir) ? "chevron.right" : "chevron.down")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.tertiary)
                    .frame(width: 10)
                Text(groupHeaderLabel(group))
                    .font(phoneList ? .footnote.weight(.semibold) : .system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .tracking(phoneList ? 0 : 0.3)
                    .textCase(nil)
                Spacer(minLength: 0)
                Text("\(count ?? group.sessions.count)")
                    .font(phoneList ? .footnote.weight(.medium) : .system(size: 11, weight: .medium, design: .rounded))
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Color.primary.opacity(0.05), in: Capsule())
            }
            .contentShape(Rectangle())
            .padding(.vertical, 2)
        }
        .buttonStyle(.plain)
        .smSessionSectionHeaderChrome(phone: phoneList)
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
        .smSessionSectionHeaderChrome(phone: phoneList)
    }

    @ViewBuilder private func row(
        _ s: SessionInfo,
        host: HostView?,
        index: FleetIndex,
        projectTag: String? = nil,
        reorderable: Bool = false,
        dragSource: SessionRowDragSourceConfig? = nil
    ) -> some View {
        // Live owner for preview/agent state; action broker falls back to active host for
        // archived Settled rows (no live owner). Both are dictionary hits into the render's
        // one fleet walk — resolving them per row used to re-scan every host's session array.
        let liveBroker = index.broker(s.id)
        let actionBroker = index.brokerOrActive(s.id)
        let muted = s.mute?.boolValue ?? false
        let previewEntry = liveBroker?.messages[s.id]?.last
        let working = liveBroker?.agentWorking[s.id] == true
        // Spinner wins while working; green rail only when idle + unread (native rail parity).
        let unread = sessionListShowsUnreadMark(
            active: selected == s.id,
            working: working,
            lastMessageTs: previewEntry?.ts,
            lastReadAt: liveBroker?.lastRead[s.id]
        )
        SessionRow(
            session: s,
            preview: previewEntry?.text,
            previewTs: previewEntry?.ts,
            phase: liveBroker?.agentPhase[s.id],
            working: working,
            bgOpen: liveBroker?.agentBgOpen[s.id] ?? 0,
            muted: muted,
            host: host,
            projectTag: projectTag,
            selected: selected == s.id,
            unread: unread,
            showsDragHint: reorderable,
            isDragging: reorderState.draggingId == s.id,
            phone: phoneList,
            dragSource: dragSource
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
                Button { actionBroker?.resume(s.id); fleet.refreshArchived() } label: {
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
                Button { liveBroker?.toggleMute(s) } label: {
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
    /// DropDelegate for Android/desktop/web-style reorder within a section.
    @ViewBuilder private func selectableRow(
        _ s: SessionInfo,
        host: HostView?,
        index: FleetIndex,
        reorderable: Bool,
        projectTag: String? = nil,
        sectionKey: String = "",
        sectionIds: [String] = [],
        /// Last row of its List section — suppresses its trailing rule (see `smSessionListRowChrome`).
        isLast: Bool = false
    ) -> some View {
        let ids = sectionIds.isEmpty ? [s.id] : sectionIds
        // Never on the phone. `.onDrag` can't win a long-press against `.contextMenu`
        // there, and leaving it attached hijacks the edit-mode grabber: the row lifts
        // into the free-drag ghost (opacity 0.35), no drop delegate ever fires, and it
        // stays stuck faded. Reordering on iPhone is `.onMove` only.
        let freeDrag = reorderable && !sectionKey.isEmpty && !phoneList
        #if os(macOS)
        // Drag source lives on the grip (see SessionRow); whole-row onDrag races scroll.
        let sourcePlacement = SessionRowDragSourcePlacement.grip
        let dragSource: SessionRowDragSourceConfig? = freeDrag
            ? SessionRowDragSourceConfig(
                sessionId: s.id,
                sessionName: s.name,
                sectionKey: sectionKey,
                sectionIds: ids,
                state: reorderState
            )
            : nil
        #else
        let sourcePlacement = SessionRowDragSourcePlacement.wholeRow
        let dragSource: SessionRowDragSourceConfig? = nil
        #endif
        row(s, host: host, index: index, projectTag: projectTag, reorderable: reorderable,
            dragSource: dragSource)
            .contentShape(Rectangle())
            .opacity(reorderState.draggingId == s.id ? 0.35 : 1)
            .simultaneousGesture(TapGesture().onEnded {
                // In edit mode a tap belongs to the reorder UI, not navigation.
                guard !reorderState.isDragging, !isEditing else { return }
                if (s.userStatus ?? "") == "draft" {
                    onOpenDraft(s.id)
                } else {
                    selected = s.id
                    onSessionSelected(s.id)
                }
            })
            .modifier(SessionRowDragReorderModifier(
                enabled: freeDrag,
                sessionId: s.id,
                sessionName: s.name,
                sectionKey: sectionKey,
                sectionIds: ids,
                state: reorderState,
                onCommit: commitReorder,
                sourcePlacement: sourcePlacement
            ))
            .tag(s.id)
            // Grabbers appear only on rows the section can actually reorder — PA, settled and
            // offline rows stay pinned, exactly as they are under the desktop free drag.
            .moveDisabled(!(phoneList && reorderable))
            .accessibilityIdentifier(TestIds.sessionRow(s.id))
            .smSessionListRowChrome(phone: phoneList, isLast: isLast)
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                swipeButtons(for: s, broker: index.broker(s.id))
            }
    }

    /// Keep swipe actions on the actual List row. Putting them inside the macOS row Button's
    /// label causes SwiftUI to size the reveal controls against the whole button host, producing
    /// the enormous icons seen in the sidebar.
    @ViewBuilder private func swipeButtons(for s: SessionInfo, broker b: BrokerSession?) -> some View {
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

/// Server-authoritative unread: last message newer than last_read_at (or no pointer).
/// Mirrors shared KMP `isSessionUnread` / web `stores/unread.ts` (ISO string compare).
func isSessionUnread(lastMessageTs: String?, lastReadAt: String?) -> Bool {
    guard let lastMessageTs, !lastMessageTs.isEmpty else { return false }
    guard let lastReadAt, !lastReadAt.isEmpty else { return true }
    return lastMessageTs > lastReadAt
}

/// Whether the list row should request the unread green rail mark (KMP `sessionListShowsUnread`).
func sessionListShowsUnreadMark(
    active: Bool,
    working: Bool,
    lastMessageTs: String?,
    lastReadAt: String?
) -> Bool {
    if active || working { return false }
    return isSessionUnread(lastMessageTs: lastMessageTs, lastReadAt: lastReadAt)
}

/// Project leaf label (web `projectLabel`) for every session, derived in ONE pass.
///
/// Each label crosses into Kotlin twice — a home-dir probe plus `formatWorkdir` — with a Swift
/// `String` bridged back. The flat list needs every label anyway just to decide whether the tag
/// says anything at all (a single-project list would repeat one word down the column), so asking
/// again per row doubled the cost of the most expensive view in the app. Sessions in the same
/// project share a label, so the work is per PROJECT, not per row.
func projectTagsBySession(_ sessions: [SessionInfo]) -> [String: String] {
    var tags: [String: String] = [:]
    tags.reserveCapacity(sessions.count)
    var labelByPath: [String: String] = [:]
    for s in sessions {
        let path = s.repo_root ?? s.workdir
        if let cached = labelByPath[path] {
            tags[s.id] = cached
            continue
        }
        let label = projectLabel(session: s, home: inferHomeDir(workdir: s.workdir))
        labelByPath[path] = label
        tags[s.id] = label
    }
    return tags
}

/// The markdown-stripping rules for a preview line, each compiled ONCE.
///
/// `String.replacingOccurrences(options: .regularExpression)` compiles its pattern on every call,
/// so the seven rules below were re-compiled for every visible row on every body pass — measured
/// at ~0.2 ms per row, which is most of a frame across a full sidebar. The patterns are literals;
/// compiling them at first use costs one pass and nothing after.
private struct PreviewRule {
    private let regex: NSRegularExpression?
    private let template: String

    init(_ pattern: String, _ template: String) {
        // A literal pattern either compiles or is a typo caught by the tests; on the impossible
        // path the rule is skipped rather than trapping in a list row.
        regex = try? NSRegularExpression(pattern: pattern)
        self.template = template
    }

    func apply(_ s: String) -> String {
        guard let regex else { return s }
        return regex.stringByReplacingMatches(
            in: s, range: NSRange(s.startIndex..., in: s), withTemplate: template
        )
    }
}

private enum PreviewRules {
    static let fence = PreviewRule("```[a-zA-Z0-9_+-]*", " ")            // fences, keep the code
    static let link = PreviewRule("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")     // [label](url) -> label
    static let blockMarker = PreviewRule("(?m)^[ \\t]*(#{1,6}[ \\t]+|>[ \\t]?|[-*+][ \\t]+|\\d+\\.[ \\t]+)", "")
    static let strong = PreviewRule("\\*\\*([^*]+)\\*\\*", "$1")
    /// Single `*emphasis*` only — never a bare `*` used as a bullet or a literal.
    static let emphasis = PreviewRule("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\*)", "$1")
    static let code = PreviewRule("`+([^`\\n]+)`+", "$1")
    static let whitespace = PreviewRule("\\s+", " ")
}

/// Stripped previews, keyed by the raw message text. A row's last message changes far more
/// rarely than the sidebar re-renders (every fleet-wide frame re-runs this for every visible
/// row), and the transform is pure, so the second pass over the same text is a lookup.
/// Bounded, and thread-safe without a lock.
private let previewTextCache: NSCache<NSString, NSString> = {
    let c = NSCache<NSString, NSString>()
    c.countLimit = 512
    return c
}()

/// Agents write markdown; a list row shows plain text. Mail and Messages both strip formatting
/// from the preview line, and without this the rows read as `**Committed** on \`main\`` noise.
///
/// Deliberately conservative and cheap — it runs for every visible row on every body pass: the
/// input is capped to a preview's worth of text, each rule is gated on a substring probe and
/// compiled once, and the result is memoized.
func sessionPreviewPlainText(_ raw: String) -> String {
    let key = raw as NSString
    if let hit = previewTextCache.object(forKey: key) { return hit as String }
    let out = sessionPreviewPlainTextUncached(raw)
    previewTextCache.setObject(out as NSString, forKey: key)
    return out
}

private func sessionPreviewPlainTextUncached(_ raw: String) -> String {
    // A two-line preview never needs more than this, and it bounds the regex work per row.
    var s = raw.count > 300 ? String(raw.prefix(300)) : raw
    if s.contains("```") { s = PreviewRules.fence.apply(s) }
    if s.contains("](") { s = PreviewRules.link.apply(s) }
    // Block markers only ever sit at a line start, so a single-line message that merely
    // *contains* a `-` or a `.` must not pay for this pass.
    if s.contains("\n") || s.first.map({ "#>-*+0123456789".contains($0) }) == true {
        s = PreviewRules.blockMarker.apply(s)
    }
    if s.contains("*") {
        s = PreviewRules.strong.apply(s)
        s = PreviewRules.emphasis.apply(s)
    }
    if s.contains("`") { s = PreviewRules.code.apply(s) }
    s = PreviewRules.whitespace.apply(s)
    return s.trimmingCharacters(in: .whitespacesAndNewlines)
}

/// Leading rail kind after working/unread priority (KMP `sessionListRailIndicator`).
enum SessionListRailKind {
    case working, unread, other
}

func sessionListRailKind(working: Bool, unread: Bool) -> SessionListRailKind {
    if working { return .working }
    if unread { return .unread }
    return .other
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
    /// Flat-mode project leaf tag (web projectLabel). macOS: quiet third-line label;
    /// iPad sidebar: title-row capsule; iPhone: third-line metadata.
    var projectTag: String? = nil
    var selected: Bool = false
    /// Server-authoritative unread (last message newer than last_read_at); bold title when true.
    var unread: Bool = false
    /// Subtle grab affordance for reorderable rows (macOS hover).
    var showsDragHint: Bool = false
    /// Dimmed list slot while this row's free-drag ghost is active.
    var isDragging: Bool = false
    /// iPhone/compact: the native inset-list row (no card, separators, Mail/Messages density).
    var phone: Bool = false
    /// macOS: drag source config for the trailing grip (nil = not a drag source).
    var dragSource: SessionRowDragSourceConfig? = nil

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
        if phone { phoneBody } else { sidebarBody }
    }

    /// iPhone: Mail/Messages register — a bare status dot in a narrow leading gutter, a `.body`
    /// title, a two-line preview, the time top-trailing, and project/host demoted to a quiet
    /// third line so they only draw the eye when they actually differ. No card, no fill: the
    /// List's own separators carry the structure.
    private var phoneBody: some View {
        HStack(alignment: .top, spacing: 12) {
            phoneIndicator
                // Match the title's line box so the dot sits on the first line, as Mail does,
                // rather than floating in the middle of a three-line row. 18pt clears the
                // `.mini` spinner, which is the widest thing the rail renders here.
                .frame(width: 18, height: 22)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(session.name)
                        .font(.body)
                        .fontWeight(unread ? .semibold : .regular)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if muted {
                        Image(systemName: "bell.slash.fill")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Spacer(minLength: 6)
                    if !timeLabel.isEmpty {
                        Text(timeLabel)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                }
                Text(previewLine)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                if projectTag != nil || host != nil || bgOpen > 0 {
                    HStack(spacing: 6) {
                        if let projectTag {
                            Text(projectTag)
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                                .lineLimit(1)
                        }
                        if let host { HostBadge(host: host) }
                        if bgOpen > 0 {
                            // The gutter is one glyph wide on iPhone, so the background-task
                            // count rides the metadata line instead of the rail.
                            Text("\u{29D7}\(bgOpen)")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.orange)
                        }
                    }
                    .padding(.top, 1)
                }
            }
        }
        .padding(.vertical, 9)
        .padding(.trailing, 16)
        // Separators start at the text column, not the screen edge (List adds the row's own
        // 16pt leading inset on top of this).
        .alignmentGuide(.listRowSeparatorLeading) { _ in 30 }   // gutter (18) + spacing (12)
        .contentShape(Rectangle())
        .scaleEffect(isDragging ? 0.98 : 1)
        .animation(.easeOut(duration: 0.12), value: isDragging)
    }

    private var sidebarBody: some View {
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
                    #if !os(macOS)
                    // iPad sidebar: compact capsule next to the title (title-row real estate is
                    // scarce there). macOS uses a quiet third-line label instead — see below.
                    if let projectTag {
                        Text(projectTag)
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.primary.opacity(0.05), in: Capsule())
                    }
                    #endif
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
                #if os(macOS)
                // Flat multi-project list: project leaf as a small third-line label (not a chip).
                // Mirrors the phone metadata line — quieter than a title-row capsule.
                if let projectTag {
                    Text(projectTag)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
                #endif
            }
            #if os(macOS)
            if showsDragHint {
                // Grip is the ONLY drag source on macOS — whole-row onDrag made the AppKit
                // List fight trackpad rubber-banding (scroll vs drag → a few visible jumps).
                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.tertiary.opacity(hovered || selected ? 0.9 : 0.35))
                    .frame(width: 16, height: 28)
                    .contentShape(Rectangle())
                    .modifier(SessionRowDragSourceModifier(config: dragSource))
                    .help("Drag to reorder")
                    .accessibilityLabel("Reorder")
            }
            #endif
        }
        .smSessionRowSurface(selected: selected, accented: false, hovered: isHovered && !isDragging)
        .scaleEffect(isDragging ? 0.98 : 1)
        #if os(macOS)
        // No implicit hover/drag animations here: they remeasure NSTableView cells mid-gesture.
        .onHover { hovered = $0 }
        #else
        .animation(.easeOut(duration: 0.12), value: isDragging)
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
            return sessionPreviewPlainText(preview)
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

    /// The same state, uncontained. The 28pt rounded-square chip is sidebar furniture — at phone
    /// scale it reads as a broken placeholder around a 7pt dot, so iPhone shows the dot itself.
    @ViewBuilder private var phoneIndicator: some View {
        if isDraft {
            Image(systemName: "pencil")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.teal)
        } else {
            // bgOpen: 0 — the count moves to the metadata line (see `phoneBody`).
            SessionStatusRail(git: nil, working: working, bgOpen: 0, unread: unread)
        }
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
            // Selection wash only — hover is snap-updated on macOS so it never feeds
            // layout animations into an AppKit List mid-scroll.
            .animation(.easeOut(duration: 0.12), value: selected)
    }

    /// List row chrome. Sidebar: clear backgrounds, hidden separators, tight vertical rhythm —
    /// the card carries the structure. iPhone: the List's own hairline separators do, so they
    /// come back on and the row takes the standard 16pt leading/trailing inset.
    @ViewBuilder func smSessionListRowChrome(
        spacing: EdgeInsets = EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0),
        phone: Bool = false,
        isLast: Bool = false
    ) -> some View {
        if phone {
            self
                .listRowBackground(Color.clear)
                // Rules go strictly BETWEEN rows: never above a section's first row, never
                // below its last. A one-row section therefore draws no border at all, instead
                // of the boxed-in look a leading + trailing rule gives it.
                .listRowSeparator(.hidden, edges: .top)
                .listRowSeparator(isLast ? .hidden : .visible, edges: .bottom)
                // Trailing inset is 0 and the row pads itself instead: a `listRowInsets`
                // trailing value also shortens the separator, and an iOS separator runs to
                // the screen edge.
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 0))
        } else {
            self
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(spacing)
        }
    }

    /// Plain-list section headers default to carrying a separator of their own, which reads as a
    /// rule under the title. iPhone hides it and tightens the header's vertical rhythm.
    ///
    /// The header is also OPAQUE and full-bleed on iPhone. A plain List pins it while its section
    /// scrolls, and a transparent header parks inside the nav bar's scroll-edge blur with rows
    /// ghosting straight through the title — so it has to bring its own canvas, the way Mail and
    /// Contacts do. Insets go to zero and the padding moves inside so the fill reaches both edges.
    @ViewBuilder func smSessionSectionHeaderChrome(phone: Bool) -> some View {
        if phone {
            self
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 5)
                .background(Color.smGroupedBackground)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets())
        } else {
            self
        }
    }
}

/// The iPhone list's navigation chrome: a large collapsing title with search in it, and the
/// compose button that replaces the in-list "New session" card. Inert on iPad/macOS, which keep
/// the inline title and the card.
private struct PhoneSessionListChrome: ViewModifier {
    let enabled: Bool
    @Binding var search: String
    let onNewSession: () -> Void
    /// Reorder affordance. A plain Bool + closure rather than `EditButton`, because the toolbar is
    /// applied outside the `.environment(\.editMode:)` that drives the List — an `EditButton` here
    /// would not see it. `EditMode` is also UIKit-only, so it never crosses into this shared type.
    let isEditing: Bool
    let onToggleEditing: () -> Void

    /// The nav bar needs a real background of its own. The List hides its scroll content
    /// background and paints its own canvas, so the bar has no system material to compose
    /// against: its Liquid Glass button capsules render opaque white while the strip between
    /// them shows raw scrolling rows ghosting under the title. Pinning the bar to the same
    /// canvas the list uses makes the whole header one continuous surface.
    @ViewBuilder private func barBackground(_ v: some View) -> some View {
        #if os(iOS)
        v.toolbarBackgroundVisibility(.visible, for: .navigationBar)
            .toolbarBackground(Color.smGroupedBackground, for: .navigationBar)
        #else
        v
        #endif
    }

    func body(content: Content) -> some View {
        if enabled {
            barBackground(content)
                .navigationTitle("Sessions")
                .smLargeNavigationTitle()
                .toolbar {
                    ToolbarItem(placement: .smTopLeading) {
                        Button(isEditing ? "Done" : "Edit", action: onToggleEditing)
                            .accessibilityIdentifier("session-list-edit")
                    }
                }
                // `.automatic`, not a pinned drawer: the list draws its own background with
                // `scrollContentBackground(.hidden)`, so a pinned field has no material behind
                // it and rows scroll through it. Automatic parks it under the large title and
                // scrolls it away — Mail/Messages behaviour.
                .searchable(text: $search, prompt: "Search sessions")
                .toolbar {
                    ToolbarItem(placement: .smTopTrailing) {
                        Button(action: onNewSession) {
                            Label("New session", systemImage: "square.and.pencil")
                        }
                        .accessibilityIdentifier(TestIds.newSession)
                    }
                }
        } else {
            content
                .navigationTitle("supermux")
                .smInlineNavigationTitle()
        }
    }
}
