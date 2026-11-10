// A click in a card sends text as the user's own message (#104). The broker's half: which card ids a session
// has, how often a card may send, how long the text may be. The app's click rules live in the app.
export const MAX_CARD_TEXT = 2000
export const CARD_SEND_INTERVAL_MS = 1000

export type CardSendCheck = { ok: true; title: string } | { ok: false; reason: string }

export class CardSendGuard {
  private readonly last = new Map<string, number>()

  /** `find`: the card's title if the session has a card with this id (a message of that session carries it). */
  constructor(
    private readonly find: (session: string, cardId: string) => { title: string } | undefined,
    private readonly now: () => number = Date.now,
  ) {}

  check(session: string, cardId: unknown, text: unknown): CardSendCheck {
    if (typeof cardId !== "string" || !cardId) return { ok: false, reason: "unknown card" }
    const card = this.find(session, cardId)
    if (!card) return { ok: false, reason: "unknown card" }
    if (typeof text !== "string" || text.length < 1) return { ok: false, reason: "a card message needs text" }
    if (text.length > MAX_CARD_TEXT) return { ok: false, reason: `a card message is at most ${MAX_CARD_TEXT} characters` }
    const key = `${session}\u0000${cardId}`
    const t = this.now()
    const prev = this.last.get(key)
    if (prev !== undefined && t - prev < CARD_SEND_INTERVAL_MS) return { ok: false, reason: "a card sends one message per second" }
    this.last.set(key, t)
    if (this.last.size > 1000) for (const [k, at] of this.last) if (t - at >= CARD_SEND_INTERVAL_MS) this.last.delete(k)
    return { ok: true, title: card.title }
  }
}

/** What the agent receives for a message sent from a card. */
export function fromCardText(card: { id: string; title: string }, text: string): string {
  return `(from the card "${card.title}")\n${text}`
}
