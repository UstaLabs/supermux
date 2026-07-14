import SwiftUI
import Shared

#if os(macOS)
/// A detached macOS window showing a single session's chat. Owns its own
/// `BrokerSession` (its own WS connection) — the same model as a second web tab:
/// each window is an independent broker client, and the broker fans out to N clients.
/// Closing this window tears down only this window's WS, never the main workspace's.
struct SessionWindow: View {
    @State private var fleet: Fleet
    let sessionId: String

    init(sessionId: String) {
        _fleet = State(initialValue: Fleet())
        self.sessionId = sessionId
    }

    private var owner: (BrokerSession, SessionInfo)? {
        guard let broker = fleet.broker(for: sessionId),
              let session = broker.sessions.first(where: { $0.id == sessionId }) else { return nil }
        return (broker, session)
    }

    var body: some View {
        Group {
            if let (broker, session) = owner {
                ChatView(broker: broker, session: session)
                    .navigationTitle(session.name)
            } else {
                // Until the snapshot lands (or if the session is gone) show a spinner.
                ProgressView().controlSize(.large)
            }
        }
        .tint(Theme.teal)
        .task { fleet.start() }
        // Window closed → genuinely tear down this window's WS + frame loop (see
        // BrokerSession.stop(); without it the closed window's session leaks alive).
        .onDisappear { fleet.stop() }
        .frame(minWidth: 640, minHeight: 480)
    }
}
#endif
