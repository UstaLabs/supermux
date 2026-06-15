import SwiftUI
import Shared

/// Adaptive shell: `NavigationSplitView` gives the iPad sidebar+detail and folds
/// to a stack on iPhone automatically. Detail = chat for the selected session.
struct RootView: View {
    @State private var broker: BrokerSession
    @State private var selected: SessionInfo?
    var onUnpair: () -> Void

    init(baseURL: String, token: String, onUnpair: @escaping () -> Void) {
        _broker = State(initialValue: BrokerSession(baseURL: baseURL, token: token))
        self.onUnpair = onUnpair
    }

    var body: some View {
        NavigationSplitView {
            SessionsListView(broker: broker, onSelect: { selected = $0 })
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("Unpair", role: .destructive, action: onUnpair)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
        } detail: {
            if let selected {
                ChatView(broker: broker, session: selected)
            } else {
                ContentUnavailableView("Pick a session",
                                       systemImage: "bubble.left.and.bubble.right")
            }
        }
        .tint(Theme.teal)
        .task { broker.start() }
        .task(id: broker.synced) {
            // Debug convenience: auto-open a session once synced (SM_OPEN_SESSION=name).
            guard broker.synced, selected == nil else { return }
            let want = ProcessInfo.processInfo.environment["SM_OPEN_SESSION"]
            selected = broker.sessions.first(where: { want != nil && $0.name == want })
                ?? broker.sessions.first(where: { !(broker.messages[$0.id]?.isEmpty ?? true) })
                ?? broker.sessions.first
        }
    }
}
