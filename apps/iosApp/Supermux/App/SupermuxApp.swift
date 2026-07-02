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
        _paired = State(initialValue: BrokerConfig.isPaired)
        #if os(iOS)
        // Start the WatchConnectivity channel so a paired Apple Watch gets the broker
        // credentials (pushed on activation + whenever they change below).
        PhoneWatchProvisioner.shared.activate()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if paired, let base = BrokerConfig.baseURL, let token = BrokerConfig.token {
                    RootView(baseURL: base, token: token, onUnpair: {
                        BrokerConfig.unpair()
                        paired = false
                    })
                    .id(base)
                } else {
                    PairingView { _ in
                        paired = true
                        #if os(iOS)
                        PhoneWatchProvisioner.shared.pushCurrent()
                        #endif
                        PushManager.shared.registerIfPaired()
                    }
                }
            }
            .onOpenURL { url in
                // Deep-link pairing: supermux://pair?t=TOKEN&base=https%3A%2F%2Fhost
                // (also lets the test harness inject a token via `simctl openurl`).
                if let p = deepLinkPair(url)
                    ?? PairToken.parse(url.absoluteString, fallbackBaseURL: BrokerConfig.baseURL) {
                    BrokerConfig.pair(p)
                    paired = true
                    #if os(iOS)
                    PhoneWatchProvisioner.shared.pushCurrent()
                    #endif
                    PushManager.shared.registerIfPaired()
                }
            }
            .preferredColorScheme(appearance == "light" ? .light : appearance == "dark" ? .dark : nil)
        }
        #if os(macOS)
        .defaultSize(width: 1440, height: 900)
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
