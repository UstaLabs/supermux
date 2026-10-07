import type { RecentInboundIds } from "./recent-inbound-ids"

export type InboundDeliveryDeps = {
  getAdapter: (sessionId: string) => { kind: string; send: (text: string, meta?: any) => Promise<void> } | undefined
  seen: RecentInboundIds
  // Fired after a turn is successfully handed off (incl. an idempotent re-send). The broker
  // uses it to re-broadcast the session's CURRENT agent_state so every client can clear its
  // local "Sending…" bubble even if the turn-start hook is later dropped. It must NOT change
  // agent state — delivery stays a pure reflector; this only re-emits existing truth.
  onDelivered?: (sessionId: string) => void
}

export type InboundDeliveryResult =
  | { ok: true; deduped?: boolean }
  | { ok: false; reason: "adapter_not_ready" }

// Deliver one inbound user turn to a session, uniformly across agents:
//   - dedupe by meta.message_id (idempotent: a re-sent message is a no-op);
//   - if an adapter exists, hand the turn to adapter.send();
//   - else report not-ready (the caller surfaces this its own way). Every session runs through
//     supermux-core, so the adapter IS the input pipe; the tmux-era fallback (a Claude row with no
//     adapter → the shim socket's inbound queue → the mux-channel shim) is gone (C3b).
// Delivery never CHANGES agent state — the agent flips to "working" only on a real turn start —
// but on a successful hand-off it fires deps.onDelivered so the broker can re-broadcast the
// CURRENT state, clearing the client's "Sending…" bubble even if that event is dropped.
export async function deliverInbound(
  deps: InboundDeliveryDeps,
  sessionId: string,
  text: string,
  meta: any,
): Promise<InboundDeliveryResult> {
  const messageId = typeof meta?.message_id === "string" ? meta.message_id : undefined
  if (messageId && deps.seen.has(sessionId, messageId)) {
    deps.onDelivered?.(sessionId)   // a retry still needs the sender's "Sending…" reconciled
    return { ok: true, deduped: true }
  }

  const adapter = deps.getAdapter(sessionId)
  if (!adapter) return { ok: false, reason: "adapter_not_ready" }
  await adapter.send(text, meta)

  if (messageId) deps.seen.mark(sessionId, messageId)
  deps.onDelivered?.(sessionId)
  return { ok: true }
}
