//
//  PushManager.swift
//  Supermux
//
//  Native push (APNs) registration + the broker orchestration that mirrors the
//  Android `SupermuxMessagingService`. The flow (relay sends `mutable-content`
//  alerts with the sealed blob under `data`; see `src/relay/apns.ts`):
//
//   1. App launch (paired): request notification authorization, then
//      `registerForRemoteNotifications()`.
//   2. `didRegisterForRemoteNotificationsWithDeviceToken` → hex-encode the APNs
//      token, then:
//        a. `relayUrl = BrokerApi(...).pushRelayUrl()`  (skip if null — relay not set)
//        b. `registerPushTokenWithRelay(relayUrl, "ios", hexToken)` — asks the relay
//           to push a *bootstrap* (silent) message back carrying a routingToken.
//   3. `didReceiveRemoteNotification` (background/content-available) with a BOOTSTRAP
//      payload (`{"kind":"bootstrap","routingToken":...}` in `data`) →
//        c. `registerPushDevice("ios", routingToken, PushKeypair.shared.publicKeyB64Url)`.
//   4. SEALED alerts are opened + rendered by the Notification Service Extension
//      (`SupermuxPushNSE`), which loads the SAME keypair from the shared Keychain
//      group — see `SupermuxPushNSE/NotificationService.swift`.
//
//  NOTE: the bootstrap push is meant to be SILENT (content-available, no alert). The
//  relay does not yet send it silent (a separate relay change, out of scope here), so
//  step 3 only fires its live path once that lands AND a real APNs token exists
//  (Phase E). The handler is written correctly regardless.
//

import Combine
import Foundation
import Shared
import UIKit
import UserNotifications

/// App-side push manager: drives APNs registration and the broker register→bootstrap
/// orchestration. A singleton (the `PushAppDelegate` forwards UIKit callbacks here).
final class PushManager: NSObject {
    static let shared = PushManager()
    private static let platform = "ios"

    private override init() { super.init() }

    /// Build the shared Ktor client the same way `BrokerSession` does (Darwin engine,
    /// ATS-relaxed for self-hosted brokers). One per `BrokerApi` call site.
    private func makeApi() -> BrokerApi? {
        guard let base = BrokerConfig.baseURL, let token = BrokerConfig.token,
              !base.isEmpty, !token.isEmpty else { return nil }
        return BrokerApi(baseUrl: base, token: token, http: IosClientKt.iosHttpClient())
    }

    // MARK: - Step 1: authorization + registration

    /// Request notification authorization (if paired) and register for remote
    /// notifications on grant. Called on launch and after pairing.
    func registerIfPaired() {
        guard BrokerConfig.isPaired else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error { NSLog("[supermux push] authorization error: %@", error.localizedDescription) }
            guard granted else {
                NSLog("[supermux push] notification authorization denied")
                return
            }
            // registerForRemoteNotifications must run on the main thread.
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }

    // MARK: - Step 2: APNs token → relay

    /// Handle a fresh APNs device token: hex-encode it and register with the relay.
    func didRegister(deviceToken: Data) {
        let hexToken = deviceToken.map { String(format: "%02x", $0) }.joined()
        NSLog("[supermux push] APNs token: %@", hexToken)
        Task { await registerWithRelay(hexToken: hexToken) }
    }

    func didFailToRegister(error: Error) {
        NSLog("[supermux push] APNs registration failed: %@", error.localizedDescription)
    }

    /// Steps 2a–2b: resolve the relay URL from the broker, hand it our APNs token.
    private func registerWithRelay(hexToken: String) async {
        guard let api = makeApi() else {
            NSLog("[supermux push] not paired; skipping relay registration")
            return
        }
        do {
            guard let relayUrl = try await api.pushRelayUrl(), !relayUrl.isEmpty else {
                NSLog("[supermux push] broker has no relayUrl; native push not configured")
                return
            }
            try await api.registerPushTokenWithRelay(relayUrl: relayUrl, platform: Self.platform, pushToken: hexToken)
            NSLog("[supermux push] registered APNs token with relay; awaiting bootstrap push")
        } catch {
            NSLog("[supermux push] relay registration failed: %@", error.localizedDescription)
        }
    }

    // MARK: - Step 3: bootstrap push → register device with broker

    /// Handle a background remote notification. Returns the UIKit background-fetch result.
    /// A BOOTSTRAP payload (plaintext `{"kind":"bootstrap","routingToken":...}` in `data`)
    /// registers this device (pubkey + routingToken) with the broker. SEALED alerts are
    /// handled by the NSE, not here.
    func didReceiveRemoteNotification(_ userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        guard let blob = userInfo["data"] as? String,
              let routingToken = Self.parseBootstrapRoutingToken(blob) else {
            return .noData
        }
        guard let api = makeApi() else {
            NSLog("[supermux push] bootstrap arrived but app is not paired; cannot register device")
            return .noData
        }
        do {
            try await api.registerPushDevice(
                platform: Self.platform,
                routingToken: routingToken,
                pubkey: PushKeypair.shared.publicKeyB64Url
            )
            NSLog("[supermux push] device registered with broker")
            return .newData
        } catch {
            NSLog("[supermux push] broker device registration failed: %@", error.localizedDescription)
            return .failed
        }
    }

    /// Parse a bootstrap payload → routingToken, else nil (a sealed blob is dot-joined
    /// base64url and never valid JSON, so it falls through to nil). Mirrors the Android
    /// `PushRouter.parseBootstrap`.
    static func parseBootstrapRoutingToken(_ blob: String) -> String? {
        let trimmed = blob.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (obj["kind"] as? String) == "bootstrap",
              let token = obj["routingToken"] as? String, !token.isEmpty
        else { return nil }
        return token
    }
}

/// Routes a tapped push to the right chat. Set by the notification tap handler
/// (`didReceive response`), observed by `RootView` to drive session selection.
@MainActor final class PushRouter: ObservableObject {
    static let shared = PushRouter()
    @Published var pendingSessionId: String?
    private init() {}
}

/// UIKit application delegate, adapted into the SwiftUI lifecycle via
/// `@UIApplicationDelegateAdaptor`. Forwards push callbacks to `PushManager`.
final class PushAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // Warm the push keypair on launch so its public key is generated + persisted in
        // the shared Keychain group up front (the NSE reads the same key to decrypt, and
        // the bootstrap handler registers this pubkey with the broker). Idempotent.
        PushKeypair.shared.loadOrCreate()
        // Register on launch if already paired (post-pairing registration is kicked
        // off from the pairing flow / `registerIfPaired()`).
        PushManager.shared.registerIfPaired()
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushManager.shared.didRegister(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PushManager.shared.didFailToRegister(error: error)
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        await PushManager.shared.didReceiveRemoteNotification(userInfo)
    }
}

extension PushAppDelegate: UNUserNotificationCenterDelegate {
    /// Show banners/sounds even while the app is in the foreground (parity with the
    /// Android high-importance channel).
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .badge]
    }

    /// User TAPPED a notification → open the session it was about. The NSE stashed the
    /// session id under `sm_session_id` in the decrypted notification's userInfo.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let id = info["sm_session_id"] as? String, !id.isEmpty else { return }
        await MainActor.run { PushRouter.shared.pendingSessionId = id }
    }
}
