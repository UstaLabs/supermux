import SwiftUI
import Shared

/// Adaptive shell: `NavigationSplitView` gives iPad sidebar+detail and folds to a
/// stack on iPhone. Session selection drives the chat (detail); the launcher and
/// the management screens are pushed as full pages (parity with the web routes).
struct RootView: View {
    @State private var broker: BrokerSession
    @State private var selected: String?
    @State private var route: NavRoute?
    @Environment(\.horizontalSizeClass) private var hSize
    var onUnpair: () -> Void

    /// Full-page destinations pushed from the sidebar (mirrors the web router).
    enum NavRoute: Hashable {
        case newSession, archived, usage, proxies, displays, devices, settings, personalAssistants
    }

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
                             onNewSession: { route = .newSession },
                             onArchived: { route = .archived })
                .navigationDestination(item: $route) { page($0) }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button { route = .personalAssistants } label: { Label("Assistants", systemImage: "person.2") }
                            Button { route = .usage } label: { Label("Usage", systemImage: "chart.bar") }
                            Button { route = .devices } label: { Label("Devices", systemImage: "ipad.and.iphone") }
                            Button { route = .proxies } label: { Label("Proxies", systemImage: "network") }
                            Button { route = .displays } label: { Label("Displays", systemImage: "display") }
                            Button { route = .settings } label: { Label("Settings", systemImage: "gearshape") }
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
            if let want, let m = broker.sessions.first(where: { $0.name == want }) {
                selected = m.id; return
            }
            // iPad keeps a session in the detail column; iPhone opens to the list.
            guard hSize == .regular else { return }
            selected = broker.sessions.first(where: { !(broker.messages[$0.id]?.isEmpty ?? true) })?.id
                ?? broker.sessions.first?.id
        }
        .task {
            // Debug: auto-push a management page for headless screenshots.
            if let s = ProcessInfo.processInfo.environment["SM_OPEN_SHEET"] {
                route = NavRoute(debugName: s)
            }
        }
        .task(id: broker.synced) {
            // Debug: drive headless navigation to verify per-session reloads.
            guard broker.synced else { return }
            let env = ProcessInfo.processInfo.environment
            if let to = env["SM_SWITCH_TO"], let m = broker.sessions.first(where: { $0.name == to }) {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                selected = m.id
            } else if env["SM_REOPEN"] == "1", let first = selected {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                selected = nil                                   // go back to the list
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                selected = first                                 // reopen the SAME session
            }
        }
    }

    @ViewBuilder private func page(_ r: NavRoute) -> some View {
        switch r {
        case .newSession: NewSessionView(broker: broker, onSpawned: { id in route = nil; selected = id })
        case .personalAssistants: PersonalAssistantsView(broker: broker, onOpen: { id in route = nil; selected = id })
        case .archived: ArchivedView(broker: broker)
        case .usage: UsageView(broker: broker)
        case .proxies: ProxiesView(broker: broker)
        case .displays: DisplaysView(broker: broker)
        case .devices: DevicesView(broker: broker)
        case .settings: SettingsView(broker: broker)
        }
    }
}

private extension RootView.NavRoute {
    init?(debugName: String) {
        switch debugName {
        case "new": self = .newSession
        case "pas": self = .personalAssistants
        case "archived": self = .archived
        case "usage": self = .usage
        case "proxies": self = .proxies
        case "displays": self = .displays
        case "devices": self = .devices
        case "settings": self = .settings
        default: return nil
        }
    }
}
