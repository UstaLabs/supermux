import SwiftUI
import Shared

/// Sidebar / root list — grouped (PA + project), swipe + context actions,
/// collapsible groups. Uses `List(selection:)` so NavigationSplitView pushes the
/// chat on iPhone (compact) and shows it in the detail column on iPad.
struct SessionsListView: View {
    let broker: BrokerSession
    @Binding var selected: String?
    var onNewSession: () -> Void
    var onArchived: () -> Void

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
            }

            ForEach(broker.groups(), id: \.workdir) { group in
                Section {
                    if !collapsed.contains(group.workdir) {
                        ForEach(group.sessions, id: \.id) { s in row(s).tag(s.id) }
                    }
                } header: { header(group) }
            }
        }
        .listStyle(.insetGrouped)
        // Continuous Mail-style reveal: the "Archived" bar lives in a top safeAreaInset (outside
        // the scroll content) and its height tracks the live overscroll 1:1. The offset signal
        // (contentOffset.y + contentInsets.top) is invariant to the inset's own height, so the
        // bar growing can't feed back into the scroll. Past a threshold it latches open so it
        // stays tappable; scrolling back down tucks it away.
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
        // Inline title: a persistent top reveal bar can't coexist with a large title (the
        // safeAreaInset eats the large title's space and squashes it).
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if !broker.synced && broker.sessions.isEmpty {
                ProgressView("Connecting…").tint(Theme.teal)
            }
        }
        .alert("Rename session", isPresented: Binding(get: { renameTarget != nil },
                                                      set: { if !$0 { renameTarget = nil } })) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Rename") {
                if let t = renameTarget { broker.rename(t.id, to: renameText) }
                renameTarget = nil
            }
        }
        .confirmationDialog("Kill “\(killTarget?.name ?? "")”?",
                            isPresented: Binding(get: { killTarget != nil },
                                                 set: { if !$0 { killTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Kill session", role: .destructive) {
                if let t = killTarget { broker.kill(t.id) }
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

    @ViewBuilder private func row(_ s: SessionInfo) -> some View {
        let muted = s.mute?.boolValue ?? false
        SessionRow(broker: broker, session: s, preview: broker.messages[s.id]?.last?.text,
                   phase: broker.agentPhase[s.id], muted: muted)
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) { killTarget = s } label: { Label("Kill", systemImage: "xmark.circle") }
                Button { renameText = s.name; renameTarget = s } label: { Label("Rename", systemImage: "pencil") }.tint(.gray)
                Button { broker.toggleMute(s) } label: {
                    Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
                }.tint(Theme.teal)
            }
            .contextMenu {
                Button { broker.toggleMute(s) } label: {
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
    let broker: BrokerSession
    let session: SessionInfo
    var preview: String?
    var phase: String?
    var muted: Bool = false

    private var working: Bool { broker.agentWorking[session.id] == true }

    var body: some View {
        HStack(spacing: 8) {
            SessionStatusRail(git: session.git, working: working)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary) }
                    Spacer(minLength: 0)
                }
                Text(preview ?? session.agent)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 3)
    }
}
