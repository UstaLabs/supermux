export interface ViewingState {
  /**
   * Exact sessions this device currently has open and visible. A workspace can
   * show two chats at once, so this is a set rather than a single id.
   */
  sessions: Set<string>
  /**
   * Foregrounded on the session list (session=null, visible=true). Suppresses
   * push for every session but does not count as exact-viewing any of them.
   */
  onList: boolean
  /** Device is in the foreground at all (list or a chat). */
  visible: boolean
  updatedAt: number
}

export class ViewingTracker {
  private readonly states = new Map<string, ViewingState>()
  private readonly ttlMs: number

  constructor(opts: { ttlMs?: number } = {}) {
    this.ttlMs = opts.ttlMs ?? 5 * 60_000
  }

  /**
   * Apply one Viewing frame.
   *
   * - `session=null, visible=false` → clear (background / nothing open)
   * - `session=null, visible=true`  → on the list (suppress all, exact none)
   * - `session=S, visible=true`     → the device is viewing exactly S (REPLACE)
   * - `session=S, visible=false`    → REMOVE S from the set
   *
   * Replace-on-true is deliberate and load-bearing: every shipped client
   * switches chats by sending only `Viewing(s2, true)`.
   *
   * A client viewing several chats at once (the desktop workspace layout) sends
   * them together — `{"type":"viewing","sessions":[...],"visible":true}` — which
   * routes to [setSessions] and sets the whole set atomically.
   */
  update(device: string, partial: { session: string | null; visible: boolean }): void {
    const now = Date.now()
    if (partial.session === null) {
      this.states.set(device, {
        sessions: new Set(),
        onList: partial.visible,
        visible: partial.visible,
        updatedAt: now,
      })
      return
    }
    if (partial.visible) {
      // REPLACE, not add. Every shipped client (web useViewing, iOS, Android,
      // macOS) switches chats by sending only Viewing(s2, true) and relies on it
      // overwriting s1. Under add-semantics s1 would stay in the set forever and
      // its push notifications would be silently suppressed on that device for
      // the rest of the session — a regression invisible until someone notices
      // they stopped being notified about a chat they once opened.
      //
      // A client that really is viewing several chats at once sends the whole
      // set in one frame instead; see setSessions and the `sessions` field on
      // the viewing frame.
      this.states.set(device, {
        sessions: new Set([partial.session]),
        onList: false,
        visible: true,
        updatedAt: now,
      })
      return
    }
    const cur = this.states.get(device)
    const sessions = new Set(cur?.sessions ?? [])
    sessions.delete(partial.session)
    this.states.set(device, {
      sessions,
      onList: false,
      visible: sessions.size > 0,
      updatedAt: now,
    })
  }

  /**
   * Replace this device's exact-viewing set in one shot. Preferred entry point
   * when a client already knows the full concurrent set.
   */
  setSessions(device: string, sessions: string[], visible: boolean): void {
    const uniq = [...new Set(sessions.filter((s) => typeof s === "string" && s.length > 0))]
    this.states.set(device, {
      sessions: new Set(uniq),
      onList: visible && uniq.length === 0,
      visible,
      updatedAt: Date.now(),
    })
  }

  clear(device: string): void {
    this.states.delete(device)
  }

  isViewing(chatId: string, sessionId: string): boolean {
    if (!chatId.startsWith("web:")) return false
    return this.isPresentFor(chatId.slice(4), sessionId)
  }

  /**
   * True when this device's foreground screen makes a push for `sessionId`
   * redundant: it's either viewing that session's chat OR sitting on the chat
   * list/home (onList). Used to suppress notifications to a device the user is
   * already looking at.
   */
  isPresentFor(device: string, sessionId: string): boolean {
    const s = this.states.get(device)
    if (!s) return false
    if (Date.now() - s.updatedAt > this.ttlMs) return false
    if (!s.visible) return false
    if (s.onList) return true
    return s.sessions.has(sessionId)
  }

  /**
   * True when the (single) user is present for `sessionId` on ANY device. A push
   * is a global decision: if you've got that session — or the chat list — open
   * anywhere, no device should buzz.
   */
  isAnyPresentFor(sessionId: string): boolean {
    for (const device of this.states.keys()) {
      if (this.isPresentFor(device, sessionId)) return true
    }
    return false
  }

  /**
   * True when some non-expired device is viewing EXACTLY this session and is
   * visible. Stricter than `isPresentFor` / `isAnyPresentFor`: sitting on the
   * chat list (`onList`) suppresses push but does NOT count here.
   * Drives server-side read status — a chat is only "read" by being opened.
   */
  isAnyExactViewing(sessionId: string): boolean {
    for (const s of this.states.values()) {
      if (Date.now() - s.updatedAt > this.ttlMs) continue
      if (!s.visible) continue
      if (s.sessions.has(sessionId)) return true
    }
    return false
  }
}
