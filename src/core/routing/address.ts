/**
 * The one place that reads a chat id.
 *
 * A chat id names the channel it belongs to: `web`, `web:<device>` (legacy),
 * `telegram:<id>`, `whatsapp:<jid>`. A bare value with no prefix is a telegram
 * chat from before the namespacing, held by long-lived sessions across a broker
 * upgrade.
 *
 * Before this module, eleven call sites did this by hand and they did not
 * agree — `handleOutbound` turned the bare value `web` into `telegram:web`
 * while `onAssistantMessage` kept it as `web`, and a scan for `web:` never
 * matched the current `web` (which killed the agent-error push). Every reader
 * uses this module now.
 *
 * A new channel needs no change here: its own prefix is its channel name.
 */

export type Address = {
  /** The channel that owns the chat: "web", "telegram", "whatsapp", … */
  channel: string
  /** The chat id in its namespaced form. Legacy bare ids are normalized. */
  chatId: string
}

/** The single logical web channel. Each session has its own chat there. */
export const WEB_CHAT_ID = "web"

export function parseAddress(raw: string | null | undefined): Address | null {
  if (typeof raw !== "string") return null
  const value = raw.trim()
  if (value.length === 0) return null

  const colon = value.indexOf(":")
  if (colon > 0) {
    return { channel: value.slice(0, colon), chatId: value }
  }
  // A leading colon names no channel.
  if (colon === 0) return null

  // No prefix: the web constant, or a pre-namespacing telegram chat id.
  if (value === WEB_CHAT_ID) return { channel: "web", chatId: WEB_CHAT_ID }
  return { channel: "telegram", chatId: `telegram:${value}` }
}

/** The channel name of a chat id, or undefined when it names none. */
export function channelOf(raw: string | null | undefined): string | undefined {
  return parseAddress(raw)?.channel
}

/** True for both web spellings — the bare `web` and the legacy `web:<device>`. */
export function isWebChat(raw: string | null | undefined): boolean {
  return channelOf(raw) === "web"
}
