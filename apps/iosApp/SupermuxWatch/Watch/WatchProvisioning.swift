import Foundation
import WatchConnectivity

/// Obtains the broker `{baseURL, token}` for the watch and publishes it.
///
/// Sources, in order:
///  1. Credentials already stored in the watch Keychain (returning user).
///  2. Launch env vars `SM_PAIR_BASE` / `SM_PAIR_TOKEN` — headless-simulator hook,
///     mirroring the iOS app's `SIMCTL_CHILD_SM_PAIR_*` screenshot hooks.
///  3. Live hand-off from the paired iPhone over WatchConnectivity.
///
/// The watch is a separate device, so a Keychain access group cannot sync the phone's
/// token to it — WatchConnectivity is the provisioning channel; after that the watch
/// connects to the broker on its own.
@MainActor
@Observable
final class WatchProvisioning: NSObject, WCSessionDelegate {
    private(set) var creds: (baseURL: String, token: String)?

    func start() {
        if let stored = WatchKeychain.load() { creds = stored }

        let env = ProcessInfo.processInfo.environment
        if let base = env["SM_PAIR_BASE"], let token = env["SM_PAIR_TOKEN"],
           !base.isEmpty, !token.isEmpty {
            apply(baseURL: base, token: token)
        }

        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    private func apply(baseURL: String, token: String) {
        WatchKeychain.save(baseURL: baseURL, token: token)
        creds = (baseURL, token)
    }

    private func ingest(_ dict: [String: Any]) {
        guard let base = dict["baseURL"] as? String,
              let token = dict["token"] as? String,
              !base.isEmpty, !token.isEmpty else { return }
        apply(baseURL: base, token: token)
    }

    // MARK: WCSessionDelegate (callbacks arrive off the main actor → hop to MainActor)

    nonisolated func session(_ session: WCSession,
                             activationDidCompleteWith activationState: WCSessionActivationState,
                             error: Error?) {}

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        Task { @MainActor in self.ingest(applicationContext) }
    }

    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        Task { @MainActor in self.ingest(userInfo) }
    }
}
