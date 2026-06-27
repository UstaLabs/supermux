// Watch-only enrichment for GET /sessions: the signals the phone gets over WebSocket
// (agent phase, last-message preview, unread) that the lean REST snapshot omits.
// watchOS can't open a WebSocket (Apple TN3135), so the watch polls /sessions; this
// helper is what each row is mapped through to carry those extra fields.

export interface WatchRowExtras {
  phase?: string
  tool?: string
  lastText?: string
  lastTs?: string
  lastFrom?: "in" | "out"
  unread: boolean
}

const PREVIEW_MAX = 120

/** Derive the watch's extra row fields from the agent state, the session's last log
 *  entry, and its server-side read pointer. Pure; unit-tested. `unread` uses the same
 *  string-timestamp comparison as the web unread store (src/web-app/src/stores/unread.ts). */
export function watchRowExtras(
  state: { phase?: string; tool?: string } | undefined,
  last: { ts?: string; direction?: string; text?: string } | undefined,
  readTs: string | undefined,
): WatchRowExtras {
  const lastTs = last?.ts
  const text = last?.text
  return {
    phase: state?.phase,
    tool: state?.tool,
    lastText: text
      ? (text.length > PREVIEW_MAX ? text.slice(0, PREVIEW_MAX - 1) + "…" : text)
      : undefined,
    lastTs,
    lastFrom: last?.direction ? (last.direction.startsWith("in") ? "in" : "out") : undefined,
    unread: !!(lastTs && (!readTs || lastTs > readTs)),
  }
}
