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
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import UserNotifications

/// Platform-neutral background-push outcome — the same three cases as
/// `UIBackgroundFetchResult`, but without importing UIKit so `PushManager` stays
/// cross-platform. The iOS delegate maps this back to `UIBackgroundFetchResult`.
enum PushFetchResult {
    case newData, noData, failed
}

/// App-side push manager: drives APNs registration and the broker register→bootstrap
/// orchestration. A singleton (the `PushAppDelegate` forwards UIKit callbacks here).
final class PushManager: NSObject {
    static let shared = PushManager()
    // macOS also registers as "ios": same APNs topic (shared bundle id), and
    // the relay only distinguishes APNs vs FCM. Introduce "macos" only when
    // the broker learns to segment device platforms.
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
                #if canImport(UIKit)
                UIApplication.shared.registerForRemoteNotifications()
                #else
                NSApplication.shared.registerForRemoteNotifications()
                #endif
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

    // MARK: - Clear-on-open

    /// Called when the user opens a chat: forget that chat's unread state (the single card
    /// the NSE keeps under `threadIdentifier == sessionId`) and reset the app-icon badge to
    /// the total unread that remains. `PushGroupState` is the source of truth on iOS; the
    /// mac client (no App Group) falls back to counting the chats still on screen.
    func clearDelivered(sessionId: String) {
        guard !sessionId.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        PushGroupState.reset(sessionId: sessionId)
        center.getDeliveredNotifications { notes in
            let ids = notes
                .filter { $0.request.content.threadIdentifier == sessionId }
                .map { $0.request.identifier }
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
            let badge: Int
            if PushGroupState.hasStore {
                badge = PushGroupState.totalUnread()
            } else {
                let remainingChats = Set(notes
                    .map { $0.request.content.threadIdentifier }
                    .filter { !$0.isEmpty && $0 != sessionId })
                badge = remainingChats.count
            }
            Task { @MainActor in try? await center.setBadgeCount(badge) }
        }
    }

    // MARK: - Step 3: bootstrap push → register device with broker

    /// Handle a background remote notification. Returns a platform-neutral fetch result
    /// (`PushFetchResult`) — the iOS delegate maps it back to the real
    /// `UIBackgroundFetchResult` the OS contract wants, while macOS (no background-fetch
    /// completion contract) simply discards it. Keeping this method UIKit-free is what
    /// lets `PushManager` compile on macOS.
    /// A BOOTSTRAP payload (plaintext `{"kind":"bootstrap","routingToken":...}` in `data`)
    /// registers this device (pubkey + routingToken) with the broker. SEALED alerts are
    /// handled by the NSE, not here.
    func didReceiveRemoteNotification(_ userInfo: [AnyHashable: Any]) async -> PushFetchResult {
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

#if canImport(UIKit)
/// UIKit application delegate, adapted into the SwiftUI lifecycle via
/// `@UIApplicationDelegateAdaptor`. Forwards push callbacks to `PushManager`.
/// Method bodies are hoisted into the shared `private extension` below (see
/// `handleLaunch()`/`handleToken(_:)`/`handleFailure(_:)`) so this class and its
/// `NSApplicationDelegate` twin (`#else`, below) stay thin shells around the same
/// logic; `handleRemote(_:)` is the one exception — it can't be fully shared because
/// its return type (`UIBackgroundFetchResult`) is UIKit-only, so each platform gets
/// its own thin `handleRemote` that still funnels into `PushManager` identically.
final class PushAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        handleLaunch()
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        handleToken(deviceToken)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        handleFailure(error)
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        await handleRemote(userInfo)
    }

    /// Shared body lives in `PushManager.shared.didReceiveRemoteNotification` (which
    /// returns a platform-neutral `PushFetchResult`); this thin wrapper exists only to
    /// map that back to the UIKit-only `UIBackgroundFetchResult` the OS method requires,
    /// which is why it can't move into the cross-platform extension below.
    private func handleRemote(_ userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        switch await PushManager.shared.didReceiveRemoteNotification(userInfo) {
        case .newData: return .newData
        case .noData: return .noData
        case .failed: return .failed
        }
    }
}
#else
/// AppKit application delegate, adapted into the SwiftUI lifecycle via
/// `@NSApplicationDelegateAdaptor`. Mirrors `PushAppDelegate`'s UIKit branch
/// method-for-method (see the type doc comment there); shared bodies live in the
/// `private extension` below.
final class PushAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        handleLaunch()
    }

    func application(_ application: NSApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        handleToken(deviceToken)
    }

    func application(_ application: NSApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        handleFailure(error)
    }

    func application(_ application: NSApplication,
                     didReceiveRemoteNotification userInfo: [String: Any]) {
        handleRemote(userInfo)
    }

    /// Same underlying call as iOS's `handleRemote`, minus the `UIBackgroundFetchResult`
    /// the OS-facing method has nowhere to return on macOS (no background-fetch
    /// completion contract) — fire-and-forget the async broker registration instead.
    private func handleRemote(_ userInfo: [String: Any]) {
        Task { _ = await PushManager.shared.didReceiveRemoteNotification(userInfo) }
    }
}
#endif

/// Shared method bodies for both `PushAppDelegate` branches above — kept once here so
/// neither `#if` branch duplicates logic (only the OS-facing method *signatures* differ).
private extension PushAppDelegate {
    /// Shared `application(_:didFinishLaunchingWithOptions:)` /
    /// `applicationDidFinishLaunching(_:)` body.
    func handleLaunch() {
        UNUserNotificationCenter.current().delegate = self
        // Register the chat category so a long-press / pull-down on a collapsed chat
        // notification routes to the custom expanded content extension (SupermuxNotifContent).
        // No actions yet — the expanded view is read-only (quick-reply is a future add).
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: PushGroupState.chatCategory, actions: [],
                                   intentIdentifiers: [], options: [])
        ])
        // Warm the push keypair on launch so its public key is generated + persisted in
        // the shared Keychain group up front (the NSE reads the same key to decrypt, and
        // the bootstrap handler registers this pubkey with the broker). Idempotent.
        PushKeypair.shared.loadOrCreate()
        // Register on launch if already paired (post-pairing registration is kicked
        // off from the pairing flow / `registerIfPaired()`).
        PushManager.shared.registerIfPaired()
    }

    /// Shared `didRegisterForRemoteNotificationsWithDeviceToken` body.
    func handleToken(_ deviceToken: Data) {
        PushManager.shared.didRegister(deviceToken: deviceToken)
    }

    /// Shared `didFailToRegisterForRemoteNotificationsWithError` body.
    func handleFailure(_ error: Error) {
        PushManager.shared.didFailToRegister(error: error)
    }
}

/// `UNUserNotificationCenterDelegate` is identical on iOS and macOS (the
/// `UserNotifications` framework itself is cross-platform), and both `PushAppDelegate`
/// branches above share the same type name, so this conformance is declared ONCE here
/// — outside the `#if` — rather than duplicated into each branch.
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
