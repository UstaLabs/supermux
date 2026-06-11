import type { RecentInboundIds } from "./recent-inbound-ids"

export type InboundDeliveryDeps = {
  getAdapter: (sessionId: string) => { kind: string; send: (text: string, meta?: any) => Promise<void> } | undefined
  isClaude: (sessionId: string) => boolean
  applyDeliver: (sessionId: string) => void
  sendInboundSocket: (sessionId: string, payload: { content: string; meta: any }) => Promise<void>
  seen: RecentInboundIds
}

export type InboundDeliveryResult =
  | { ok: true; deduped?: boolean }
  | { ok: false; reason: "adapter_not_ready" }

// Deliver one inbound user turn to a session, uniformly across agents:
//   - dedupe by meta.message_id (idempotent: a re-sent message is a no-op);
//   - if an adapter exists, hand the turn to adapter.send();
//   - else, for Claude only, fall back to the shim socket (which queues if the
//     shim isn't connected yet) — preserving today's behavior;
//   - else (adapter-driven agent with no adapter object) report not-ready WITHOUT
//     flipping the live "sending" state (the caller surfaces this its own way).
export async function deliverInbound(
  deps: InboundDeliveryDeps,
  sessionId: string,
  text: string,
  meta: any,
): Promise<InboundDeliveryResult> {
  const messageId = typeof meta?.message_id === "string" ? meta.message_id : undefined
  if (messageId && deps.seen.has(sessionId, messageId)) return { ok: true, deduped: true }

  const adapter = deps.getAdapter(sessionId)
  if (!adapter && !deps.isClaude(sessionId)) return { ok: false, reason: "adapter_not_ready" }

  deps.applyDeliver(sessionId)
  if (adapter) await adapter.send(text, meta)
  else await deps.sendInboundSocket(sessionId, { content: text, meta })

  if (messageId) deps.seen.mark(sessionId, messageId)
  return { ok: true }
}
