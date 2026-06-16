import SwiftUI
import Shared

/// Adaptive shell: `NavigationSplitView` gives iPad sidebar+detail and folds to a
/// stack on iPhone. Selection is the session id (drives compact navigation).
struct RootView: View {
    @State private var broker: BrokerSession
    @State private var selected: String?
    @State private var sheet: InfoSheet?
    @State private var showLauncher = false
    var onUnpair: () -> Void

    init(baseURL: String, token: String, onUnpair: @escaping () -> Void) {
        _broker = State(initialValue: BrokerSession(baseURL: baseURL, token: token))
        self.onUnpair = onUnpair
    }

    private var selectedSession: SessionInfo? {
        guard let selected else { return nil }
        return broker.sessions.first(where: { $0.id == selected })
    }

    var body: some View {
        NavigationSplitView {
            SessionsListView(broker: broker, selected: $selected,
                             onNewSession: { showLauncher = true })
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            ForEach(InfoSheet.allCases) { page in
                                Button { sheet = page } label: {
                                    Label(page.title, systemImage: page.systemImage)
                                }
                            }
                            Divider()
                            Button("Unpair", role: .destructive, action: onUnpair)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
        } detail: {
            if let s = selectedSession {
                ChatView(broker: broker, session: s)
            } else {
                ContentUnavailableView("Pick a session",
                                       systemImage: "bubble.left.and.bubble.right")
            }
        }
        .tint(Theme.teal)
        .task { broker.start() }
        .task(id: broker.synced) {
            guard broker.synced, selected == nil else { return }
            let want = ProcessInfo.processInfo.environment["SM_OPEN_SESSION"]
            selected = broker.sessions.first(where: { want != nil && $0.name == want })?.id
                ?? broker.sessions.first(where: { !(broker.messages[$0.id]?.isEmpty ?? true) })?.id
                ?? broker.sessions.first?.id
        }
        .sheet(item: $sheet) { page in
            NavigationStack {
                page.view(broker: broker)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) { Button("Done") { sheet = nil } }
                    }
            }
            .tint(Theme.teal)
        }
        .sheet(isPresented: $showLauncher) {
            NavigationStack {
                NewSessionView(broker: broker, onSpawned: { id in selected = id })
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) { Button("Cancel") { showLauncher = false } }
                    }
            }
            .tint(Theme.teal)
        }
    }
}
