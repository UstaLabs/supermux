export interface ViewingState {
  session: string | null
  visible: boolean
  updatedAt: number
}

export class ViewingTracker {
  private readonly states = new Map<string, ViewingState>()
  private readonly ttlMs: number

  constructor(opts: { ttlMs?: number } = {}) {
    this.ttlMs = opts.ttlMs ?? 5 * 60_000
  }

  update(device: string, partial: { session: string | null; visible: boolean }): void {
    this.states.set(device, { ...partial, updatedAt: Date.now() })
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
   * list/home (session === null + visible). Used to suppress notifications to a
   * device the user is already looking at.
   */
  isPresentFor(device: string, sessionId: string): boolean {
    const s = this.states.get(device)
    if (!s) return false
    if (Date.now() - s.updatedAt > this.ttlMs) return false
    if (!s.visible) return false
    return s.session === sessionId || s.session === null
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
}
