import Foundation
import WatchConnectivity

/// iOS side of watch provisioning: hands the broker `{baseURL, token}` to the paired
/// Apple Watch over WatchConnectivity so the watch app can connect to the broker on
/// its own. `updateApplicationContext` is latest-wins and is delivered even when the
/// watch isn't reachable right now (queued until it is), so re-pushing is harmless.
final class PhoneWatchProvisioner: NSObject, WCSessionDelegate {
    static let shared = PhoneWatchProvisioner()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Send the current credentials (if paired) to the watch. Safe to call repeatedly.
    func pushCurrent() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated else { return }
        guard let base = BrokerConfig.baseURL, let token = BrokerConfig.token,
              !base.isEmpty, !token.isEmpty else { return }
        try? session.updateApplicationContext(["baseURL": base, "token": token])
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession,
                 activationDidCompleteWith activationState: WCSessionActivationState,
                 error: Error?) {
        pushCurrent()
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        // Reactivate to keep the channel live after a watch switch.
        session.activate()
    }

    func sessionWatchStateDidChange(_ session: WCSession) { pushCurrent() }
    func sessionReachabilityDidChange(_ session: WCSession) { pushCurrent() }
}
