import SwiftUI
import Shared
import Combine

/// Adaptive shell: `NavigationSplitView` gives iPad sidebar+detail and folds to a
/// stack on iPhone. Session selection drives the chat (detail); the launcher and
/// the management screens are pushed as full pages (parity with the web routes).
///
/// Multi-host (spec §5): the shell is driven by a `Fleet` — N per-host `BrokerSession`s merged into
/// one recordId-tagged session list. The list/rail/launcher read the merged fleet; the chat + detail
/// panes route to the SELECTED session's OWNING host, so the huge single-session surface is unchanged.
struct RootView: View {
    @State private var fleet: Fleet
    @State private var selected: String?
    @State private var route: NavRoute?
    @State private var showAddHost = false
    @State private var debugArchived: ArchivedItem?    // SM_OPEN_ARCHIVED headless repro
    @State private var layout = WorkspaceLayoutModel()
    #if os(macOS)
    private let isRegularWidth = true   // the Mac is always the wide multi-pane workspace
    @Environment(\.openSettings) private var openSettings
    #else
    @Environment(\.horizontalSizeClass) private var hSize
    private var isRegularWidth: Bool { hSize == .regular }
    #endif
    @Environment(\.scenePhase) private var scenePhase
    var onUnpair: () -> Void

    /// Full-page destinations pushed from the sidebar (mirrors the web router).
    enum NavRoute: Hashable {
        case newSession, archived, usage, proxies, displays, devices, pairDevice, settings, personalAssistants
    }

    init(onUnpair: @escaping () -> Void) {
        _fleet = State(initialValue: Fleet())
        self.onUnpair = onUnpair
    }

    private var selectedSession: SessionInfo? {
        guard let selected else { return nil }
        return fleet.sessions.first(where: { $0.id == selected })
    }

    var body: some View {
        Group {
            if isRegularWidth { regularShell } else { compactShell }
        }
        .tint(Theme.teal)
        .sheet(isPresented: $showAddHost) {
            AddHostView(fleet: fleet, onAdded: {})
        }
        .task {
            fleet.start()
            // Seed viewing presence on launch (onChange won't fire for the initial state).
            fleet.updateViewing(session: selected, visible: scenePhase == .active)
        }
        // Cross-platform teardown (deliberately NOT mac-gated): RootView leaves the hierarchy
        // on unpair and on re-pair recreation (`.id(base)` in SupermuxApp) — on iOS too —
        // and without stop() the old brokers' frame loops retain them forever, leaving stale
        // sessions endlessly reconnecting in the background (on macOS also on window close).
        .onDisappear { fleet.stop() }
        .task(id: fleet.synced) {
            guard fleet.synced, selected == nil else { return }
            let want = ProcessInfo.processInfo.environment["SM_OPEN_SESSION"]
            if let want, let m = fleet.sessions.first(where: { $0.name == want }) {
                selected = m.id; return
            }
            // iPad keeps a session in the detail column; iPhone opens to the list.
            guard isRegularWidth else { return }
            selected = fleet.sessions.first(where: { !(fleet.broker(for: $0.id)?.messages[$0.id]?.isEmpty ?? true) })?.id
                ?? fleet.sessions.first?.id
        }
        .task {
            // Debug: auto-push a management page for headless screenshots.
            if let s = ProcessInfo.processInfo.environment["SM_OPEN_SHEET"] {
                route = NavRoute(debugName: s)
            }
        }
        .task(id: fleet.synced) {
            // Debug: drive headless navigation to verify per-session reloads.
            guard fleet.synced else { return }
            let env = ProcessInfo.processInfo.environment
            if let to = env["SM_SWITCH_TO"], let m = fleet.sessions.first(where: { $0.name == to }) {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                selected = m.id
            } else if env["SM_REOPEN"] == "1", let first = selected {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                selected = nil                                   // go back to the list
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                selected = first                                 // reopen the SAME session
            }
        }
        .task(id: fleet.synced) {
            // Debug: open an ARCHIVED session's read-only transcript headlessly.
            guard fleet.synced, debugArchived == nil else { return }
            if let want = ProcessInfo.processInfo.environment["SM_OPEN_ARCHIVED"],
               let a = (await fleet.activeBroker?.archived() ?? []).first(where: { $0.name == want }) {
                debugArchived = ArchivedItem(id: a.id, dto: a)
            }
        }
        .task(id: fleet.synced) {
            // Debug: reproduce resume-from-archive → open the now-live chat, to verify the
            // transcript loads on open (SM_RESUME_OPEN=<archived session name>). Mirrors the user
            // repro: resume from archive, tap the chat — it must show messages, not be empty.
            guard fleet.synced, selected == nil else { return }
            guard let want = ProcessInfo.processInfo.environment["SM_RESUME_OPEN"],
                  let a = (await fleet.activeBroker?.archived() ?? []).first(where: { $0.name == want }) else { return }
            fleet.activeBroker?.resume(a.id)
            // Wait for the session_added frame to land the resumed session in the live list, then open it.
            for _ in 0..<40 {
                if let m = fleet.sessions.first(where: { $0.id == a.id }) { selected = m.id; return }
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
        .smFullScreenCover(item: $debugArchived) { item in
            if let b = fleet.activeBroker {
                NavigationStack { ArchivedChatView(broker: b, archived: item.dto) }
            }
        }
        .onReceive(PushRouter.shared.$pendingSessionId) { id in
            // A tapped push → open that session. Setting `selected` resolves once the
            // session loads; the default-selection task is guarded on `selected == nil`,
            // so it won't override this.
            guard let id else { return }
            selected = id
            PushRouter.shared.pendingSessionId = nil
        }
        // Opening a chat clears its delivered notifications and re-derives the app badge, and
        // tells the owning host we're now viewing it (so it won't push us for this chat). `selected`
        // is the single source of truth for the open chat on every form factor (iPhone detail,
        // iPad/mac workspace), and a tapped push routes through it too, so this one hook covers them all.
        .onChange(of: selected) { _, id in
            if let id { PushManager.shared.clearDelivered(sessionId: id) }
            if let id, let owner = fleet.sessionHost[id] { fleet.setActive(owner) }
            fleet.updateViewing(session: id, visible: scenePhase == .active)
        }
        // Returning to the foreground on an already-open chat clears whatever landed while the
        // app was backgrounded; going to the background reports us away so pushes resume.
        .onChange(of: scenePhase) { _, phase in
            let active = phase == .active
            if active, let id = selected { PushManager.shared.clearDelivered(sessionId: id) }
            fleet.updateViewing(session: selected, visible: active)
        }
        #if os(macOS)
        .onReceive(NotificationCenter.default.publisher(for: .smNewSession)) { _ in
            // macOS File ▸ New Session (⌘N) → open the launcher (a sheet on the Mac).
            route = .newSession
        }
        .onReceive(NotificationCenter.default.publisher(for: .smPairNewDevice)) { _ in
            // Device management must be reachable even when no session is selected (and
            // therefore no session-header overflow menu exists).
            route = .pairDevice
        }
        // Settings is a real window on the Mac, not a sheet — redirect any route to it
        // (covers the SM_OPEN_SHEET=settings headless hook; the ⋯ menu opens it directly).
        .onChange(of: route) { _, r in
            guard r == .settings else { return }
            route = nil
            openSettings()
        }
        #endif
    }

    /// Compact width (iPhone / Slide-Over / narrow): the split view folds to a stack.
    private var compactShell: some View {
        NavigationSplitView {
            SessionsListView(fleet: fleet, selected: $selected,
                             onNewSession: { route = .newSession },
                             onArchived: { route = .archived },
                             onAddHost: { showAddHost = true })
                .navigationDestination(item: $route) { page($0) }
                .toolbar {
                    ToolbarItem(placement: .smTopTrailing) {
                        Menu {
                            Button { showAddHost = true } label: { Label("Add host", systemImage: "plus.rectangle.on.rectangle") }
                            Divider()
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
            if let s = selectedSession, let b = fleet.broker(for: s.id) {
                ChatView(broker: b, session: s)
            } else {
                ContentUnavailableView("Pick a session",
                                       systemImage: "bubble.left.and.bubble.right")
            }
        }
    }

    /// Regular width (iPad): the PWA's wide multi-pane workspace. iPad pushes management pages;
    /// Mac keeps New Session in a centered, in-window card and uses sheets for the other pages.
    private var regularShell: some View {
        NavigationStack {
            IPadWorkspace(fleet: fleet, selected: $selected, route: $route, layout: layout,
                          onAddHost: { showAddHost = true },
                          newSessionContent: {
                              #if os(macOS)
                              MacNewSessionOverlay(onClose: { route = nil }) {
                                  page(.newSession)
                              }
                              #else
                              EmptyView()
                              #endif
                          })
            #if os(iOS)
                .navigationDestination(item: $route) { page($0) }
            #endif
        }
        #if os(macOS)
        .sheet(item: macSheetRoute) { r in
            // The pages were designed to be pushed (they rely on the nav back-button and have
            // no page-level dismiss), so the sheet wrapper supplies a single Done button.
            NavigationStack {
                page(r)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { route = nil }
                        }
                    }
            }
            .frame(minWidth: 760, minHeight: 560)
        }
        #endif
    }

    #if os(macOS)
    /// All non-launcher management routes retain their existing Mac sheet presentation. Filtering
    /// here prevents SwiftUI from also materializing a sheet window for New Session.
    private var macSheetRoute: Binding<NavRoute?> {
        Binding(
            get: { route == .newSession ? nil : route },
            set: { route = $0 }
        )
    }
    #endif

    @ViewBuilder private func page(_ r: NavRoute) -> some View {
        switch r {
        case .newSession: ActiveHostPage(fleet: fleet) { broker in
            NewSessionView(broker: broker, fleet: fleet, onSpawned: { id in route = nil; selected = id })
        }
        case .personalAssistants: HostScopedPage(fleet: fleet) { broker in
            PersonalAssistantsView(broker: broker, onOpen: { id in route = nil; selected = id })
        }
        case .archived: HostScopedPage(fleet: fleet) { ArchivedView(broker: $0) }
        case .usage: HostScopedPage(fleet: fleet) { UsageView(broker: $0) }
        case .proxies: HostScopedPage(fleet: fleet) { ProxiesView(broker: $0) }
        case .displays: HostScopedPage(fleet: fleet) { DisplaysView(broker: $0) }
        case .devices: HostScopedPage(fleet: fleet) { DevicesView(broker: $0) }
        case .pairDevice: HostScopedPage(fleet: fleet) { AddDeviceView(broker: $0) }
        case .settings: HostScopedPage(fleet: fleet) { SettingsView(broker: $0) }
        }
    }
}

#if os(macOS)
private struct MacNewSessionOverlay<Content: View>: View {
    var onClose: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.opacity(0.16)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onClose)

                VStack(spacing: 0) {
                    HStack {
                        Text("New session").font(.headline)
                        Spacer()
                        Button("Cancel", action: onClose)
                    }
                    .padding(12)
                    .background(.bar)
                    Divider()
                    content()
                }
                .frame(
                    width: min(max(0, proxy.size.width - 64), 760),
                    height: min(max(0, proxy.size.height - 64), 640)
                )
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.smSeparator.opacity(0.7))
                }
                .shadow(color: .black.opacity(0.3), radius: 28, y: 12)
            }
        }
        .transition(.opacity)
        .onExitCommand(perform: onClose)
        .accessibilityIdentifier("new-session-overlay")
    }
}
#endif

/// Resolves the fleet's ACTIVE broker inside its own `body`, so a host switch re-renders the page
/// with the new host's broker. Resolving it in `page(_:)` directly was not enough: a pushed
/// `navigationDestination` closure is not re-evaluated when observable fleet state changes, so
/// picking another host left the pushed page (Settings host picker, launcher projects) on the old
/// broker until it was re-opened.
private struct ActiveHostPage<Content: View>: View {
    let fleet: Fleet
    @ViewBuilder let content: (BrokerSession) -> Content

    var body: some View {
        if let broker = fleet.activeBroker {
            content(broker)
        } else {
            ProgressView("Connecting…").tint(Theme.teal)
        }
    }
}

/// `ActiveHostPage` plus the explicit host-scope bar for pages whose data and actions belong to
/// one broker (Usage, Devices, Settings, …).
private struct HostScopedPage<Content: View>: View {
    let fleet: Fleet
    @ViewBuilder let content: (BrokerSession) -> Content

    var body: some View {
        ActiveHostPage(fleet: fleet) { broker in
            content(broker)
                // Recreate page-local loading/state when host scope changes. Without this, a page
                // could keep host A's rows while its action closures had already moved to host B.
                .id(broker.baseURL)
                .safeAreaInset(edge: .top, spacing: 0) {
                    HostScopePicker(hosts: fleet.hostViews, selected: fleet.activeRecordId) {
                        fleet.setActive($0)
                    }
                }
        }
    }
}

private struct ArchivedItem: Identifiable { let id: String; let dto: ArchivedDto }

#if os(macOS)
// `.sheet(item:)` needs Identifiable (iOS uses `.navigationDestination(item:)`, which only
// needs Hashable). The enum has no associated values, so it's its own stable identity.
extension RootView.NavRoute: Identifiable {
    var id: Self { self }
}
#endif

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
        case "pair-device": self = .pairDevice
        case "settings": self = .settings
        default: return nil
        }
    }
}
