import SwiftUI
import Shared

/// The sidebar / root list — sessions grouped by Personal Assistants + workdir,
/// exactly as the PWA (shared `groupSessions`).
struct SessionsListView: View {
    let broker: BrokerSession
    var onSelect: (SessionInfo) -> Void

    var body: some View {
        List {
            ForEach(broker.groups(), id: \.workdir) { group in
                Section(group.label) {
                    ForEach(group.sessions, id: \.id) { s in
                        Button { onSelect(s) } label: {
                            SessionRow(session: s,
                                       preview: broker.messages[s.id]?.last?.text,
                                       phase: broker.agentPhase[s.id])
                        }
                        .buttonStyle(.plain)
                    }
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
    }
}

struct SessionRow: View {
    let session: SessionInfo
    var preview: String?
    var phase: String?

    private var working: Bool {
        guard let phase else { return false }
        return ["working", "thinking", "running", "tool", "busy"].contains(phase)
    }

    var body: some View {
        HStack(spacing: 11) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Theme.teal.opacity(0.14))
                .frame(width: 34, height: 34)
                .overlay(
                    Image(systemName: "cube.transparent")
                        .font(.system(size: 15)).foregroundStyle(Theme.teal)
                )
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(session.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if working { ProgressView().controlSize(.mini) }
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
