//
//  PushGroupState.swift
//  Supermux
//
//  Shared state + rendering for iMessage-style *collapsed* push notifications.
//
//  Before this, the Notification Service Extension tagged every message's alert with
//  `threadIdentifier = sessionId`, so iOS *stacked* a chat's alerts as N separate
//  notifications. Instead we now keep a SINGLE, self-updating notification per chat: the
//  NSE records each incoming message here, removes the chat's previously-delivered alert,
//  and re-delivers ONE alert whose subtitle is the unread count and whose body is the last
//  few lines. Opening the chat (`PushManager.clearDelivered`) forgets that chat's state.
//
//  Long-pressing that single card expands it into a custom mini-transcript drawn by the
//  `SupermuxNotifContent` content extension. It reads the recent messages (text + time) +
//  count straight from the notification's userInfo (which the NSE fills from `Grouped`
//  below) — no App Group / network needed at expand time.
//
//  All state lives in the shared App Group so BOTH the app and the extension (separate
//  processes) read/write the same store. Cross-process writes are last-writer-wins; that's
//  acceptable because opening a chat is authoritative (resets that chat to zero), so any
//  transient drift self-heals. The mac push extension deliberately ships WITHOUT an App
//  Group (`hasStore == false`); there the helpers degrade to no-ops and callers fall back
//  to counting delivered notifications.
//
//  Compiled into BOTH the app target and the push extensions (see project.yml).
//

import Foundation

enum PushGroupState {
    /// The shared App Group id — must match the app + extension entitlements.
    static let appGroup = "group.dev.supermux.app"

    /// The notification category that routes a long-press / pull-down to the custom
    /// expanded content extension. Must match `UNNotificationExtensionCategory` in
    /// `SupermuxNotifContent`'s Info.plist (see project.yml) and the category the app
    /// registers on launch.
    static let chatCategory = "supermux.chat"

    /// `sessionId → ["count": Int, "items": [["t": text, "at": time]]]`. Single source of
    /// truth for the collapsed notification's count/body, the expanded transcript, AND the
    /// app-icon badge (total unread messages).
    private static let stateKey = "sm_notif_group_state"

    /// How many recent messages the expanded transcript shows.
    static let maxLines = 4
    /// Cap each stored line so a huge message can't bloat the App Group value.
    private static let maxLineLen = 140

    private static var store: UserDefaults? { UserDefaults(suiteName: appGroup) }

    /// False on the mac extension (no App Group entitlement) — callers then fall back to
    /// deriving state from the delivered-notification list instead of this store.
    static var hasStore: Bool { store != nil }

    // MARK: - Recording + reset

    /// The result of folding a new message into a chat: the collapsed content (title /
    /// subtitle / body) plus the structured transcript the expanded content extension
    /// renders (`items` are plist dicts `["t": text, "at": time]`, oldest → newest).
    struct Grouped {
        let rendered: Rendered
        let count: Int
        let items: [[String: String]]
    }

    /// Record one freshly-arrived message for `sessionId` and return everything the NSE
    /// needs: the collapsed content + the count + the recent-messages transcript. Increments
    /// the chat's unread count and appends to its recent-items ring (capped at `maxLines`).
    static func recordIncoming(sessionId: String, title: String, body: String) -> Grouped {
        var state = loadState()
        var entry = state[sessionId] ?? [:]
        let count = (entry["count"] as? Int ?? 0) + 1
        // Coerce defensively: NSUserDefaults hands back NSArray/NSDictionary/NSString, and a
        // one-shot `as? [[String: String]]` across that bridge is unreliable — so the ring
        // accumulates instead of silently resetting to one item each push.
        var items: [[String: String]] = (entry["items"] as? [[String: Any]] ?? []).map { dict in
            dict.reduce(into: [String: String]()) { acc, kv in if let s = kv.value as? String { acc[kv.key] = s } }
        }
        items.append(["t": String(body.prefix(maxLineLen)), "at": timeString()])
        if items.count > maxLines { items = Array(items.suffix(maxLines)) }
        entry["count"] = count
        entry["items"] = items
        state[sessionId] = entry
        saveState(state)
        return Grouped(rendered: render(title: title, lines: items.map { $0["t"] ?? "" }, count: count),
                       count: count,
                       items: items)
    }

    /// Forget a chat's unread state — called when the user opens it.
    static func reset(sessionId: String) {
        var state = loadState()
        guard state.removeValue(forKey: sessionId) != nil else { return }
        saveState(state)
    }

    /// Total unread messages across every chat — the app-icon badge value.
    static func totalUnread() -> Int {
        loadState().values.reduce(0) { $0 + ($1["count"] as? Int ?? 0) }
    }

    // MARK: - Pure rendering (no I/O — unit-tested by PushGroupStateTests)

    struct Rendered: Equatable {
        let title: String
        let subtitle: String
        let body: String
    }

    /// Build the collapsed notification's (title, subtitle, body) from a chat's recent
    /// lines + unread count.
    ///
    /// - A single unread message: no count subtitle, body = that message.
    /// - Multiple unread: subtitle = "N new messages", body = the recent lines **newest
    ///   first** (so the one-line collapsed preview shows the latest message; long-press
    ///   expands to the custom transcript).
    static func render(title: String, lines: [String], count: Int) -> Rendered {
        if count <= 1 {
            return Rendered(title: title, subtitle: "", body: lines.last ?? "")
        }
        return Rendered(title: title,
                        subtitle: "\(count) new messages",
                        body: lines.reversed().joined(separator: "\n"))
    }

    // MARK: - App Group I/O

    /// Defensive per-entry cast (a one-shot deep cast across the NSUserDefaults bridge is
    /// finicky): keep only values that survive as `[String: Any]`.
    private static func loadState() -> [String: [String: Any]] {
        guard let raw = store?.dictionary(forKey: stateKey) else { return [:] }
        var out: [String: [String: Any]] = [:]
        for (key, value) in raw {
            if let entry = value as? [String: Any] { out[key] = entry }
        }
        return out
    }

    private static func saveState(_ state: [String: [String: Any]]) {
        store?.set(state, forKey: stateKey)
    }

    // MARK: - Time formatting

    /// Locale-aware short time ("4:41 PM" / "16:41"), stamped when a message is recorded so
    /// each transcript row keeps its own arrival time as newer messages arrive.
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return f
    }()

    private static func timeString() -> String { timeFormatter.string(from: Date()) }
}
