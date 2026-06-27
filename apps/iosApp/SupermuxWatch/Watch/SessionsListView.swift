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
                List {
                    if broker.needsYouCount + broker.workingCount > 0 {
                        Text(glanceText)
                            .font(.caption2).foregroundStyle(.secondary)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(broker.orderedSessions, id: \.id) { session in
                        NavigationLink(value: session.id) { WatchSessionRow(session: session) }
                            .swipeActions(edge: .trailing) {
                                Button { broker.send(session.id, "continue") } label: {
                                    Label("Continue", systemImage: "arrowshape.right.fill")
                                }.tint(.green)
                                Button { broker.interrupt(session.id) } label: {
                                    Label("Stop", systemImage: "stop.fill")
                                }.tint(.orange)
                                Button { broker.setMute(session.id, !(session.mute ?? false)) } label: {
                                    Label((session.mute ?? false) ? "Unmute" : "Mute",
                                          systemImage: (session.mute ?? false) ? "bell.slash" : "bell")
                                }.tint(.gray)
                            }
                    }
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

    /// "2 need you · 1 working", omitting zero parts.
    private var glanceText: String {
        var parts: [String] = []
        if broker.needsYouCount > 0 { parts.append("\(broker.needsYouCount) need you") }
        if broker.workingCount > 0 { parts.append("\(broker.workingCount) working") }
        return parts.joined(separator: " · ")
    }
}
