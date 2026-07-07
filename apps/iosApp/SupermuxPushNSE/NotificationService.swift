//
//  NotificationService.swift
//  SupermuxPushNSE
//
//  Notification Service Extension: decrypts supermux push alerts ON-DEVICE before
//  the system shows them. The relay's APNs payload (see `src/relay/apns.ts`) is:
//
//      { aps: { alert: { title, body }, "mutable-content": 1 }, data: "<sealed blob>" }
//
//  `mutable-content:1` lets this extension intercept the alert; the sealed blob lives
//  under `userInfo["data"]`. We:
//    1. Load the device's static P-256 keypair from the SHARED Keychain access group
//       (`PushKeypair.shared` — the same item the app generated).
//    2. `openSealedPush(blob, privateKey:)` → plaintext JSON `{session, sessionId?, text?, kind?}`.
//    3. Set the notification title = `session`, body = `text`, and deliver it.
//
//  On ANY failure (no blob / locked Keychain / decrypt error / bad JSON) we deliver
//  `bestAttemptContent` unchanged — the generic "supermux" fallback the relay set —
//  so the user always sees *something*. `PushCrypto.swift` + `PushKeypair.swift` are
//  shared sources compiled into BOTH the app and this extension.
//

import Foundation
import UserNotifications

class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttemptContent: UNMutableNotificationContent?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        let best = (request.content.mutableCopy() as? UNMutableNotificationContent)
        self.bestAttemptContent = best

        guard let best else { contentHandler(request.content); return }

        // The relay puts the sealed blob under `data` (apns.ts). Anything else → fallback.
        guard let blob = request.content.userInfo["data"] as? String else {
            contentHandler(best)
            return
        }

        do {
            let key = PushKeypair.shared.privateKey
            let plaintext = try openSealedPush(blob, privateKey: key)
            if let note = Self.parseNotification(plaintext) {
                best.title = note.title
                best.body = note.body
                // Carry the session id through to the TAP handler so the app opens the
                // right chat — the original userInfo only holds the encrypted blob.
                if let sid = note.sessionId, !sid.isEmpty {
                    var info = best.userInfo
                    info["sm_session_id"] = sid
                    best.userInfo = info
                    // Group every notification from the same chat into one stack
                    // (iMessage-style). iOS collapses same-`threadIdentifier` alerts on the
                    // lock screen and in Notification Center; the host app clears a chat's
                    // stack by this same id when it's opened (see PushManager.clearDelivered).
                    best.threadIdentifier = sid
                }
                // Reflect the growing unread count on the app icon. The push is E2E-encrypted,
                // so the server can NOT set `aps.badge` — we keep the count on-device in the
                // shared App Group and the host app re-derives it from the delivered list when
                // a chat is opened (self-healing any drift).
                best.badge = NSNumber(value: Self.bumpBadgeCount())
                NSLog("[supermux NSE] decrypted ok — title=%{public}@ body=%{public}@", note.title, note.body)
                // Record the last decrypted notification in the shared App Group container
                // so the host app can read what was delivered (and so the decrypt can be
                // verified deterministically on the simulator).
                Self.recordLastDelivered(title: note.title, body: note.body)
            }
        } catch {
            NSLog("[supermux NSE] decrypt failed: %@", error.localizedDescription)
            // fall through to deliver the generic fallback unchanged
        }
        contentHandler(best)
    }

    override func serviceExtensionTimeWillExpire() {
        // System is about to kill us — deliver whatever we have.
        if let contentHandler, let bestAttemptContent {
            contentHandler(bestAttemptContent)
        }
    }

    // MARK: - Shared App Group record (host app can read the last delivered push)

    private static let appGroup = "group.dev.supermux.app"

    /// Key for the on-device unread badge count, shared with the host app via the App Group.
    /// (Must match `PushManager.badgeKey`.)
    private static let badgeKey = "sm_badge_count"

    /// Increment the shared unread badge counter and return the new value. Falls back to `1`
    /// if the App Group defaults are unavailable (e.g. the mac NSE, which omits the group) —
    /// there the host app's re-derive-from-delivered path drives the badge instead.
    private static func bumpBadgeCount() -> Int {
        guard let defaults = UserDefaults(suiteName: appGroup) else { return 1 }
        let next = defaults.integer(forKey: badgeKey) + 1
        defaults.set(next, forKey: badgeKey)
        return next
    }

    /// Persist the last decrypted notification to the shared App Group container as JSON.
    /// The host app shares this group; on the simulator the file is readable via
    /// `simctl get_app_container <app> group.dev.supermux.app`. iOS-only: the mac NSE
    /// entitlements (Task 2) deliberately omit the app group, so `containerURL(...)`
    /// would return nil there anyway — this whole hook only serves the iOS Simulator
    /// `simctl` verification story, hence the explicit `#if` rather than relying on the
    /// implicit nil-guard no-op.
    private static func recordLastDelivered(title: String, body: String) {
        #if os(iOS)
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return }
        let payload: [String: Any] = ["title": title, "body": body, "ts": ISO8601DateFormatter().string(from: Date())]
        if let data = try? JSONSerialization.data(withJSONObject: payload) {
            try? data.write(to: dir.appendingPathComponent("last_push.json"))
        }
        #endif
    }

    // MARK: - Payload parsing (mirrors Android `PushRouter.parseNotification`)

    private struct ParsedNotification { let title: String; let body: String; let sessionId: String? }

    /// Parse the decrypted broker `PushPayload` JSON `{session, sessionId?, text?, kind?}`
    /// into a renderable (title, body). `session` is the title; `text` the body, falling
    /// back to a media label from `kind` (parity with the Android client).
    private static func parseNotification(_ plaintext: String) -> ParsedNotification? {
        guard let data = plaintext.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let session = obj["session"] as? String else { return nil }
        let body = (obj["text"] as? String) ?? mediaLabel(obj["kind"] as? String)
        let sessionId = obj["sessionId"] as? String
        return ParsedNotification(title: session, body: body, sessionId: sessionId)
    }

    private static func mediaLabel(_ kind: String?) -> String {
        switch kind {
        case "photo": return "Sent a photo"
        case "voice": return "Sent a voice message"
        case "audio": return "Sent audio"
        case "video_note": return "Sent a video note"
        case "document": return "Sent a document"
        default: return "New message"
        }
    }
}
