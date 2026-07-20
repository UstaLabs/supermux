import SwiftUI
import Shared

@main
struct SupermuxApp: App {
    // UIKit/AppKit AppDelegate (push/APNs) adapted into the SwiftUI lifecycle. The delegate
    // requests notification authorization + registers for remote notifications on
    // launch (if paired), and orchestrates relay/broker push registration.
    #if os(iOS)
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate
    #else
    @NSApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate
    #endif
    @State private var paired: Bool
    @AppStorage("appearance") private var appearance = "system"
    #if os(macOS)
    @StateObject private var macHost: MacHostCoordinator
    @State private var macManualPairing = false
    @State private var macSetupChecked: Bool
    @State private var macNeedsOnboarding = false
    @State private var macOpenNewSessionAfterOnboarding = false
    #endif

    init() {
        // Crash guard FIRST, before any networking: ktor-darwin surfaces WS/connection
        // errors on an unhandled coroutine → SIGABRT (the "app cannot be open" launch
        // crash when the broker restarts / a connection error hits at startup). Install
        // a Kotlin/Native hook that logs-and-continues so the reconnect loop recovers
        // instead of the process aborting.
        IosClientKt.installIosCrashGuard()
        // Debug/testing convenience: auto-pair from launch env
        // (inject via SIMCTL_CHILD_SM_PAIR_TOKEN / SIMCTL_CHILD_SM_PAIR_BASE).
        let env = ProcessInfo.processInfo.environment
        if let t = env["SM_PAIR_TOKEN"], let b = env["SM_PAIR_BASE"], !t.isEmpty, !b.isEmpty {
            BrokerConfig.pair(PairToken(baseURL: b, token: t))
        }
        // Multi-host storage (spec §3.2): run the one-time single-host → PairedHost[0] migration at
        // launch, AFTER any debug/env auto-pair above so a freshly-seeded token migrates too
        // (mirrors Android's MainActivity ordering). Existing paired users land in the shared
        // multi-host `PairedHostStore` with ZERO re-pairing; the live connection still runs from
        // BrokerConfig for now (the fleet-list UI that reads the store is a later task).
        #if os(macOS)
        let persistHostState = MacHostPolicy.shouldPersist()
        if persistHostState { HostStore.migrateFromLegacyIfNeeded() }
        let initiallyPaired = persistHostState && BrokerConfig.isPaired
        // Headless interaction checks can exercise the real onboarding hierarchy without
        // clearing the developer's pairing or changing production state. Inert unless set.
        let forceOnboarding = env["SM_FORCE_ONBOARDING"] == "1"
        _paired = State(initialValue: initiallyPaired)
        _macSetupChecked = State(initialValue: forceOnboarding || !initiallyPaired)
        _macNeedsOnboarding = State(initialValue: forceOnboarding && initiallyPaired)
        #else
        HostStore.migrateFromLegacyIfNeeded()
        _paired = State(initialValue: BrokerConfig.isPaired)
        #endif
        #if os(macOS)
        let nativeHost = MacHostCoordinator.live()
        _macHost = StateObject(wrappedValue: nativeHost)
        // Hosting is an application lifecycle responsibility, not a window lifecycle task.
        // macOS may restore zero windows after a prior close; the local broker must still start.
        if MacHostPolicy.shouldAutostart() {
            Task { @MainActor in await nativeHost.start() }
        }
        // Headless feel-test eyes (SM_SNAPSHOT=1) — see DebugSnapshot.swift.
        DebugSnapshot.startIfEnabled()
        #endif
        #if os(iOS)
        // Start the WatchConnectivity channel so a paired Apple Watch gets the broker
        // credentials (pushed on activation + whenever they change below).
        PhoneWatchProvisioner.shared.activate()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if paired, let base = BrokerConfig.baseURL {
                    #if os(macOS)
                    if !macSetupChecked {
                        ProgressView("Checking setup…")
                            .controlSize(.large)
                            .task { await checkMacOnboarding() }
                    } else if macNeedsOnboarding {
                        macWizard
                    } else {
                        pairedRoot(base: base)
                    }
                    #else
                    pairedRoot(base: base)
                    #endif
                } else {
                    #if os(macOS)
                    if macManualPairing {
                        PairingView { _ in
                            paired = true
                            macSetupChecked = true
                            macNeedsOnboarding = false
                            PushManager.shared.registerIfPaired()
                        }
                    } else {
                        macWizard
                    }
                    #else
                    OnboardingView { _ in
                        paired = true
                        PhoneWatchProvisioner.shared.pushCurrent()
                        PushManager.shared.registerIfPaired()
                    }
                    #endif
                }
            }
            .onOpenURL { url in
                // Deep-link pairing: supermux://pair?t=TOKEN&base=https%3A%2F%2Fhost
                // (also lets the test harness inject a token via `simctl openurl`).
                if let p = deepLinkPair(url)
                    ?? PairToken.parse(url.absoluteString, fallbackBaseURL: BrokerConfig.baseURL) {
                    BrokerConfig.pair(p)
                    paired = true
                    #if os(macOS)
                    macSetupChecked = true
                    macNeedsOnboarding = false
                    #endif
                    #if os(iOS)
                    PhoneWatchProvisioner.shared.pushCurrent()
                    #endif
                    PushManager.shared.registerIfPaired()
                }
            }
            .preferredColorScheme(appearance == "light" ? .light : appearance == "dark" ? .dark : nil)
            #if os(macOS)
            // The Mac is always the wide multi-pane workspace (`isRegularWidth` is a constant),
            // so there's no compact fallback — floor the window so the panes can't be crushed.
            .frame(minWidth: 1100, minHeight: 700)
            #endif
        }
        // Menu-bar commands + default size on the main window (separate `#if` from the second
        // scene below: one `#if` can't both append postfix modifiers here AND introduce a
        // sibling `WindowGroup` — the parser reads the whole block as a postfix chain).
        #if os(macOS)
        .commands {
            // File ▸ New Session (⌘N). Replaces the default "New" item; posts a notification
            // that RootView routes to the launcher (menu commands can't reach a view binding).
            // No SidebarCommands(): the mac shell is a NavigationStack with a CUSTOM sidebar
            // toggled by ⌘B (WorkspaceShortcuts), so the standard Toggle Sidebar item —
            // which needs a NavigationSplitView to act on — would be a dead menu entry.
            CommandGroup(replacing: .newItem) {
                Button("New Session") {
                    NotificationCenter.default.post(name: .smNewSession, object: nil)
                }
                .keyboardShortcut("n", modifiers: .command)
                Divider()
                Button("Pair New Device…") {
                    NotificationCenter.default.post(name: .smPairNewDevice, object: nil)
                }
            }
            TextEditingCommands()
        }
        .defaultSize(width: 1440, height: 900)
        #endif

        // A detached window per opened session (⌃-click a row ▸ Open in New Window). Each
        // window owns its own BrokerSession — the web-tab model, where every window is an
        // independent broker client (the broker fans out to N clients).
        #if os(macOS)
        WindowGroup(id: "session", for: String.self) { $sessionId in
            if let sessionId {
                SessionWindow(sessionId: sessionId)
            }
        }
        .defaultSize(width: 1000, height: 760)

        // The native Settings window (adds Supermux ▸ Settings… + ⌘, automatically).
        Settings {
            MacSettingsWindow()
        }
        #endif
    }

    @ViewBuilder
    private func pairedRoot(base: String) -> some View {
        // RootView owns the multi-host `Fleet` (built from `HostStore.shared`); the
        // primary host's URL keys the view identity so a re-pair to a different broker
        // rebuilds the fleet. Added hosts don't change `base`, so the fleet stays live.
        RootView(startWithNewSession: shouldStartWithNewSession, onUnpair: {
            BrokerConfig.unpair()
            HostStore.forgetAll()
            paired = false
        })
        .id(base)
        #if os(macOS)
        .task {
            macOpenNewSessionAfterOnboarding = false
            if MacHostPolicy.shouldAutostart(), macHost.state == .idle { await macHost.start() }
        }
        #endif
    }

    #if os(macOS)
    private var macWizard: some View {
        MacHostWizard(
            coordinator: macHost,
            onContinue: {
                macOpenNewSessionAfterOnboarding = true
                paired = BrokerConfig.isPaired
                macSetupChecked = true
                macNeedsOnboarding = false
            },
            onConnectManually: { macManualPairing = true }
        )
    }

    /// A local pair is persisted as soon as the host is prepared so onboarding REST calls
    /// can authenticate. Ask the broker whether setup actually finished before showing RootView;
    /// this resumes a wizard that was interrupted after Welcome instead of silently skipping it.
    private func checkMacOnboarding() async {
        guard let base = BrokerConfig.baseURL, let token = BrokerConfig.token else {
            macNeedsOnboarding = true
            macSetupChecked = true
            return
        }
        let broker = BrokerSession(baseURL: base, token: token)
        for _ in 0..<20 {
            if let config = await broker.config() {
                macNeedsOnboarding = !config.onboarded
                macSetupChecked = true
                return
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        // Preserve access for an already-paired but temporarily offline host. RootView will
        // reconnect normally; we only force onboarding when the broker confirms it is pending.
        macNeedsOnboarding = false
        macSetupChecked = true
    }
    #endif

    private var shouldStartWithNewSession: Bool {
        #if os(macOS)
        macOpenNewSessionAfterOnboarding
        #else
        false
        #endif
    }
}

private func deepLinkPair(_ url: URL) -> PairToken? {
    guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let t = c.queryItems?.first(where: { $0.name == "t" })?.value, !t.isEmpty,
          let base = c.queryItems?.first(where: { $0.name == "base" })?.value, !base.isEmpty
    else { return nil }
    return PairToken(baseURL: base, token: t)
}
