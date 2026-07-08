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
//  few lines — so long-pressing it *expands* into a mini transcript. Opening the chat
//  (`PushManager.clearDelivered`) forgets that chat's state.
//
//  All state lives in the shared App Group so BOTH the app and the extension (separate
//  processes) read/write the same store — exactly like the badge counter it replaces.
//  Cross-process writes are last-writer-wins; that's acceptable because opening a chat is
//  authoritative (it resets that chat to zero), so any transient drift self-heals on the
//  next open or message. The mac push extension deliberately ships WITHOUT an App Group
//  (`hasStore == false`); there the helpers degrade to no-ops and callers fall back to
//  counting delivered notifications.
//
//  Compiled into BOTH the app target and the push extensions (see project.yml).
//

import Foundation

enum PushGroupState {
    /// The shared App Group id — must match the app + extension entitlements.
    static let appGroup = "group.dev.supermux.app"

    /// `sessionId → ["count": Int, "lines": [String]]`. Single source of truth for the
    /// collapsed notification's count/body AND the app-icon badge (total unread messages).
    private static let stateKey = "sm_notif_group_state"

    /// How many recent lines the expanded notification shows.
    static let maxLines = 4
    /// Cap each stored line so a huge message can't bloat the App Group value.
    private static let maxLineLen = 140

    private static var store: UserDefaults? { UserDefaults(suiteName: appGroup) }

    /// False on the mac extension (no App Group entitlement) — callers then fall back to
    /// deriving state from the delivered-notification list instead of this store.
    static var hasStore: Bool { store != nil }

    // MARK: - Recording + reset

    /// Record one freshly-arrived message for `sessionId` and return the rendered collapsed
    /// content. Increments the chat's unread count and appends to its recent-lines ring.
    static func recordIncoming(sessionId: String, title: String, body: String) -> Rendered {
        var state = loadState()
        var entry = state[sessionId] ?? [:]
        let count = (entry["count"] as? Int ?? 0) + 1
        var lines = entry["lines"] as? [String] ?? []
        lines.append(String(body.prefix(maxLineLen)))
        if lines.count > maxLines { lines = Array(lines.suffix(maxLines)) }
        entry["count"] = count
        entry["lines"] = lines
        state[sessionId] = entry
        saveState(state)
        return render(title: title, lines: lines, count: count)
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
    ///   expands to the rest).
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
}
