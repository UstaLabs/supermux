import SwiftUI

@main
struct SupermuxApp: App {
    @State private var paired: Bool
    @AppStorage("appearance") private var appearance = "system"

    init() {
        // Debug/testing convenience: auto-pair from launch env
        // (inject via SIMCTL_CHILD_SM_PAIR_TOKEN / SIMCTL_CHILD_SM_PAIR_BASE).
        let env = ProcessInfo.processInfo.environment
        if let t = env["SM_PAIR_TOKEN"], let b = env["SM_PAIR_BASE"], !t.isEmpty, !b.isEmpty {
            BrokerConfig.pair(PairToken(baseURL: b, token: t))
        }
        _paired = State(initialValue: BrokerConfig.isPaired)
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
                    PairingView { _ in paired = true }
                }
            }
            .onOpenURL { url in
                // Deep-link pairing: supermux://pair?t=TOKEN&base=https%3A%2F%2Fhost
                // (also lets the test harness inject a token via `simctl openurl`).
                if let p = deepLinkPair(url)
                    ?? PairToken.parse(url.absoluteString, fallbackBaseURL: BrokerConfig.baseURL) {
                    BrokerConfig.pair(p)
                    paired = true
                }
            }
            .preferredColorScheme(appearance == "light" ? .light : appearance == "dark" ? .dark : nil)
        }
    }
}

private func deepLinkPair(_ url: URL) -> PairToken? {
    guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let t = c.queryItems?.first(where: { $0.name == "t" })?.value, !t.isEmpty,
          let base = c.queryItems?.first(where: { $0.name == "base" })?.value, !base.isEmpty
    else { return nil }
    return PairToken(baseURL: base, token: t)
}
