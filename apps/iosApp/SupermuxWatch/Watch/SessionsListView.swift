import SwiftUI

/// The watch's home screen: a Digital-Crown-scrollable list of active sessions.
struct SessionsListView: View {
    let broker: WatchBrokerSession

    var body: some View {
        Group {
            if !broker.synced {
                VStack(spacing: 6) {
                    ProgressView()
                    Text("Connecting…").font(.footnote).foregroundStyle(.secondary)
                    // Diagnostic: the exact broker URL + WS connection state.
                    Text(broker.baseURL)
                        .font(.caption2).foregroundStyle(.secondary)
                        .lineLimit(2).multilineTextAlignment(.center)
                    if broker.route == .offline {
                        Text("Offline").font(.caption).foregroundStyle(.orange)
                    }
                    if !broker.status.isEmpty {
                        Text(broker.status)
                            .font(.caption2).foregroundStyle(.orange)
                            .lineLimit(4).multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, 6)
            } else if broker.sessions.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "bubble.left.and.bubble.right").font(.title3).foregroundStyle(.secondary)
                    Text("No active sessions").font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                List(broker.orderedSessions, id: \.id) { session in
                    NavigationLink(value: session.id) { SessionRow(session: session) }
                }
            }
        }
        .navigationTitle("Supermux")
        .toolbar {
            if broker.route == .phone {
                ToolbarItem(placement: .topBarTrailing) {
                    Image(systemName: "iphone")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Connected via iPhone")
                }
            }
        }
        .navigationDestination(for: String.self) { id in
            if let session = broker.sessions.first(where: { $0.id == id }) {
                SessionDetailView(broker: broker, session: session)
            }
        }
    }
}

private struct SessionRow: View {
    let session: SessionInfo

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill((session.connected ?? false) ? Color.green : Color.secondary)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(session.name).font(.headline).lineLimit(1)
                Text(session.agent ?? "").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }
}
