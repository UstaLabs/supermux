import SwiftUI
import Shared

/// The sidebar / root list — sessions grouped by Personal Assistants + project
/// (shared `groupSessions`), with swipe + context actions and collapsible groups,
/// mirroring the web SessionListView.
struct SessionsListView: View {
    let broker: BrokerSession
    var onSelect: (SessionInfo) -> Void
    var onNewSession: () -> Void

    @State private var collapsed: Set<String> = SessionsListView.loadCollapsed()
    @State private var renameTarget: SessionInfo?
    @State private var renameText = ""
    @State private var killTarget: SessionInfo?

    var body: some View {
        List {
            Section {
                Button(action: onNewSession) {
                    Label {
                        Text("New session").fontWeight(.medium).foregroundStyle(.primary)
                    } icon: {
                        Image(systemName: "plus.circle.fill").foregroundStyle(Theme.teal)
                    }
                }
                .buttonStyle(.plain)
            }

            ForEach(broker.groups(), id: \.workdir) { group in
                Section {
                    if !collapsed.contains(group.workdir) {
                        ForEach(group.sessions, id: \.id) { row($0) }
                    }
                } header: {
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
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("supermux")
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

    @ViewBuilder private func row(_ s: SessionInfo) -> some View {
        let muted = s.mute?.boolValue ?? false
        Button { onSelect(s) } label: {
            SessionRow(session: s, preview: broker.messages[s.id]?.last?.text,
                       phase: broker.agentPhase[s.id], muted: muted)
        }
        .buttonStyle(.plain)
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
    let session: SessionInfo
    var preview: String?
    var phase: String?
    var muted: Bool = false

    private var working: Bool {
        guard let phase else { return false }
        return ["working", "thinking", "running", "tool", "busy"].contains(phase)
    }

    var body: some View {
        HStack(spacing: 11) {
            AgentLogo(agent: session.agent)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if working { ProgressView().controlSize(.mini) }
                    if muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary) }
                    Spacer(minLength: 0)
                }
                Text(preview ?? session.agent)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
    }
}
