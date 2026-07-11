import SwiftUI
import Shared

/// Sidebar / root list — the merged multi-host fleet (spec §5): grouped (PA + project) sessions
/// across every paired host, a per-row host badge + an `All · <host…> · +` filter chip row (both
/// shown only when ≥2 hosts are paired), and offline hosts rendered as greyed "last seen" groups.
/// Per-row reads (preview/agent state) and actions (kill/rename/mute) route to the session's OWNING
/// `BrokerSession` via `Fleet`. Single-host is the unchanged path: no chips, no badges.
struct SessionsListView: View {
    let fleet: Fleet
    @Binding var selected: String?
    var onNewSession: () -> Void
    var onArchived: () -> Void
    var onAddHost: () -> Void = {}

    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    @State private var collapsed: Set<String> = SessionsListView.loadCollapsed()
    // Continuous pull-to-reveal: bar height tracks the live overscroll; latches open past a threshold.
    @State private var revealHeight: CGFloat = 0
    @State private var archivedLatched = false
    @State private var renameTarget: SessionInfo?
    @State private var renameText = ""
    @State private var killTarget: SessionInfo?

    private let archivedRevealMax: CGFloat = 52
    private let archivedLatchAt: CGFloat = 46

    var body: some View {
        let owner = fleet.sessionHost
        let hostViews = fleet.hostViews
        let hostByRecord = Dictionary(hostViews.map { ($0.recordId, $0) }, uniquingKeysWith: { a, _ in a })
        let multiHost = fleet.multiHost

        return VStack(spacing: 0) {
            if multiHost {
                HostFilterChips(
                    hosts: hostViews,
                    selected: fleet.filter,
                    count: { rid in owner.values.reduce(0) { $0 + ($1 == rid ? 1 : 0) } },
                    onSelect: { fleet.setFilter($0) },
                    onAddHost: onAddHost
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
                    .padding(.vertical, 3)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("new-session")
            }

            ForEach(fleet.onlineGroups(), id: \.workdir) { group in
                Section {
                    if !collapsed.contains(group.workdir) {
                        ForEach(group.sessions, id: \.id) { s in
                            row(s, host: multiHost ? hostByRecord[owner[s.id] ?? ""] : nil).tag(s.id)
                        }
                    }
                } header: { header(group) }
            }

            // Offline hosts: greyed group per host with a "last seen" header (multi-host only).
            ForEach(fleet.offlineHostGroups(), id: \.host.recordId) { entry in
                Section {
                    ForEach(entry.sessions, id: \.id) { s in
                        row(s, host: hostByRecord[entry.host.recordId]).tag(s.id).opacity(0.5)
                    }
                } header: { offlineHeader(entry.host) }
            }
        }
        #if os(macOS)
        .listStyle(.sidebar)
        #else
        .smInsetGroupedListStyle()
        #endif
        .safeAreaInset(edge: .top, spacing: 0) { archivedBar }
        .onScrollGeometryChange(for: CGFloat.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top
        } action: { _, top in
            let pull = max(0, -top)
            if archivedLatched {
                if top > 24 { withAnimation(.snappy(duration: 0.25)) { archivedLatched = false; revealHeight = 0 } }
            } else if pull >= archivedLatchAt {
                archivedLatched = true
                withAnimation(.snappy(duration: 0.2)) { revealHeight = archivedRevealMax }
            } else {
                revealHeight = pull
            }
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
        .confirmationDialog("Kill \u{201C}\(killTarget?.name ?? "")\u{201D}?",
                            isPresented: Binding(get: { killTarget != nil },
                                                 set: { if !$0 { killTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Kill session", role: .destructive) {
                if let t = killTarget { fleet.broker(for: t.id)?.kill(t.id) }
                killTarget = nil
            }
            Button("Cancel", role: .cancel) { killTarget = nil }
        }
    }

    // The reveal bar itself. Empty (zero-height) until pulled, so there's no resting footprint.
    @ViewBuilder private var archivedBar: some View {
        if revealHeight > 0.5 {
            Button {
                withAnimation(.snappy(duration: 0.2)) { archivedLatched = false; revealHeight = 0 }
                onArchived()
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "archivebox").font(.title3).foregroundStyle(.secondary).frame(width: 26)
                    Text("Archived").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(height: revealHeight)
                .opacity(min(1, revealHeight / archivedLatchAt))
                .contentShape(Rectangle())
                .clipped()
            }
            .buttonStyle(.plain)
            .background(.bar)
        }
    }

    private func header(_ group: SessionGroup) -> some View {
        Button { toggle(group.workdir) } label: {
            HStack(spacing: 6) {
                Image(systemName: collapsed.contains(group.workdir) ? "chevron.right" : "chevron.down")
                    .font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                Text(group.label).textCase(nil)
                Spacer()
                Text("\(group.sessions.count)").foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Greyed header for an offline host group: dot + name + a relative "last seen" (spec §5).
    private func offlineHeader(_ host: HostView) -> some View {
        HStack(spacing: 6) {
            HostDot(colorIndex: host.colorIndex, size: 8)
            Text(host.displayName).textCase(nil).foregroundStyle(.secondary)
            let seen = FleetModelKt.formatLastSeen(nowMs: Int64(Date().timeIntervalSince1970 * 1000),
                                                   lastSeenAt: host.lastSeenAt)
            Text(seen.isEmpty ? "offline" : "offline · \(seen)")
                .font(.caption2).foregroundStyle(.tertiary)
            Spacer()
        }
        .opacity(0.85)
    }

    @ViewBuilder private func row(_ s: SessionInfo, host: HostView?) -> some View {
        let b = fleet.broker(for: s.id)
        let muted = s.mute?.boolValue ?? false
        SessionRow(session: s, preview: b?.messages[s.id]?.last?.text,
                   phase: b?.agentPhase[s.id],
                   working: b?.agentWorking[s.id] == true,
                   bgOpen: b?.agentBgOpen[s.id] ?? 0, muted: muted, host: host)
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) { killTarget = s } label: { Label("Kill", systemImage: "xmark.circle") }
                Button { renameText = s.name; renameTarget = s } label: { Label("Rename", systemImage: "pencil") }.tint(.gray)
                Button { b?.toggleMute(s) } label: {
                    Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
                }.tint(Theme.teal)
            }
            .contextMenu {
                #if os(macOS)
                Button { openWindow(id: "session", value: s.id) } label: {
                    Label("Open in New Window", systemImage: "macwindow.badge.plus")
                }
                Divider()
                #endif
                Button { b?.toggleMute(s) } label: {
                    Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
                }
                Button { renameText = s.name; renameTarget = s } label: { Label("Rename", systemImage: "pencil") }
                Button(role: .destructive) { killTarget = s } label: { Label("Kill", systemImage: "xmark.circle") }
            }
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

    var body: some View {
        HStack(spacing: 8) {
            SessionStatusRail(git: session.git, working: working, bgOpen: bgOpen)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary) }
                    Spacer(minLength: 0)
                    if let host { HostBadge(host: host) }
                }
                Text(preview ?? session.agent)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 3)
    }
}
