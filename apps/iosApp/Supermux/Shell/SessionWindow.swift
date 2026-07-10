import SwiftUI
import Shared

#if os(macOS)
/// A detached macOS window showing a single session's chat. Owns its own
/// `BrokerSession` (its own WS connection) — the same model as a second web tab:
/// each window is an independent broker client, and the broker fans out to N clients.
/// Closing this window tears down only this window's WS, never the main workspace's.
struct SessionWindow: View {
    @State private var broker: BrokerSession
    let sessionId: String

    init(baseURL: String, token: String, sessionId: String) {
        _broker = State(initialValue: BrokerSession(baseURL: baseURL, token: token))
        self.sessionId = sessionId
    }

    private var session: SessionInfo? {
        broker.sessions.first(where: { $0.id == sessionId })
    }

    var body: some View {
        Group {
            if let s = session {
                ChatView(broker: broker, session: s)
                    .navigationTitle(s.name)
            } else {
                // Until the snapshot lands (or if the session is gone) show a spinner.
                ProgressView().controlSize(.large)
            }
        }
        .tint(Theme.teal)
        .task { broker.start() }
        // Window closed → genuinely tear down this window's WS + frame loop (see
        // BrokerSession.stop(); without it the closed window's session leaks alive).
        .onDisappear { broker.stop() }
        .frame(minWidth: 640, minHeight: 480)
    }
}
#endif
