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
            deliver(best)
            return
        }

        do {
            let key = PushKeypair.shared.privateKey
            let plaintext = try openSealedPush(blob, privateKey: key)
            guard let note = Self.parseNotification(plaintext) else { deliver(best); return }

            // Carry the session id through to the TAP handler so the app opens the right
            // chat — the original userInfo only holds the encrypted blob.
            if let sid = note.sessionId, !sid.isEmpty {
                var info = best.userInfo
                info["sm_session_id"] = sid

                // iMessage-style SINGLE card per chat: fold this message into the chat's
                // running summary (unread count + recent messages), then remove the chat's
                // previously-delivered alert and deliver this updated one in its place — so
                // the chat shows ONE self-updating notification instead of a growing stack.
                // `threadIdentifier` keys the removal here and in `PushManager.clearDelivered`
                // when the chat is opened.
                best.threadIdentifier = sid
                let group = PushGroupState.recordIncoming(sessionId: sid, title: note.title, body: note.body)
                best.title = group.rendered.title
                best.subtitle = group.rendered.subtitle
                best.body = group.rendered.body

                // Route a long-press / pull-down to the custom expanded content extension
                // (SupermuxNotifContent) and carry the transcript it renders — the recent
                // messages (text + time) + unread count — inside the notification itself.
                best.categoryIdentifier = PushGroupState.chatCategory
                info["sm_title"] = note.title
                info["sm_count"] = group.count
                info["sm_items"] = group.items
                best.userInfo = info

                NSLog("[supermux NSE] decrypted ok — %{public}@ / %{public}@", group.rendered.title,
                      group.rendered.subtitle.isEmpty ? group.rendered.body : group.rendered.subtitle)
                // Record for the simulator's deterministic-decrypt verification (iOS only).
                Self.recordLastDelivered(title: group.rendered.title, body: group.rendered.body)
                collapseAndDeliver(sessionId: sid, content: best)
                return
            }

            // No session id (defensive — real pushes always carry one): a single ungrouped
            // alert, no badge/grouping bookkeeping.
            best.title = note.title
            best.body = note.body
            NSLog("[supermux NSE] decrypted ok (ungrouped) — title=%{public}@", note.title)
            Self.recordLastDelivered(title: note.title, body: note.body)
        } catch {
            NSLog("[supermux NSE] decrypt failed: %@", error.localizedDescription)
            // fall through to deliver the generic fallback unchanged
        }
        deliver(best)
    }

    override func serviceExtensionTimeWillExpire() {
        // System is about to kill us — deliver whatever we have.
        if let bestAttemptContent { deliver(bestAttemptContent) }
    }

    // MARK: - Delivery + collapse

    /// Deliver exactly once — guards against the timeout path (`serviceExtensionTimeWillExpire`)
    /// and the async `getDeliveredNotifications` completion both firing.
    private func deliver(_ content: UNNotificationContent) {
        guard let handler = contentHandler else { return }
        contentHandler = nil
        handler(content)
    }

    /// Remove the chat's previously-delivered alert, set the badge to the total unread, then
    /// deliver `content` in its place — leaving a single notification per chat.
    private func collapseAndDeliver(sessionId: String, content: UNMutableNotificationContent) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let stale = delivered
                .filter { $0.request.content.threadIdentifier == sessionId }
                .map { $0.request.identifier }
            if !stale.isEmpty { center.removeDeliveredNotifications(withIdentifiers: stale) }
            content.badge = NSNumber(value: Self.badgeCount(sessionId: sessionId, delivered: delivered))
            self.deliver(content)
        }
    }

    /// Total-unread badge. iOS reads the App Group message count; the mac extension (no App
    /// Group) falls back to the number of distinct chats currently on screen (this one
    /// included), since it can't keep a cross-process counter.
    private static func badgeCount(sessionId: String, delivered: [UNNotification]) -> Int {
        if PushGroupState.hasStore { return PushGroupState.totalUnread() }
        let otherChats = Set(delivered
            .map { $0.request.content.threadIdentifier }
            .filter { !$0.isEmpty && $0 != sessionId })
        return otherChats.count + 1
    }

    // MARK: - Shared App Group record (host app can read the last delivered push)

    /// Persist the last decrypted notification to the shared App Group container as JSON.
    /// The host app shares this group; on the simulator the file is readable via
    /// `simctl get_app_container <app> group.dev.supermux.app`. iOS-only: the mac NSE
    /// entitlements deliberately omit the app group, so `containerURL(...)` would return
    /// nil there anyway — this whole hook only serves the iOS Simulator `simctl`
    /// verification story, hence the explicit `#if` rather than relying on the implicit
    /// nil-guard no-op.
    private static func recordLastDelivered(title: String, body: String) {
        #if os(iOS)
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: PushGroupState.appGroup) else { return }
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
