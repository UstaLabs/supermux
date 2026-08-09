// src/channels/web/inbound-handler.ts
//
// Pure handler that processes a web InboundMessage, appends to the message log,
// and hands the turn to THE inbound funnel (SessionManager.deliver in
// production — adapter.send for codex/cursor/opencode/grok, the shim socket for
// claude). Extracted from main.ts so it can be tested without spinning up a
// full broker. The Telegram inbound has its own analogous flow (inline in
// main.ts for now) — they should stay in lockstep: any attachment_* meta key on
// one path must be on the other.
//
// Bug C2 (fixed): the original inline handler in main.ts forgot to pass
// `msg.attachments` to messageLog.append and forgot to include `attachment_*`
// meta keys on delivery. Without either, Claude never sees PWA uploads.
// Telegram inbound was correct; this is the parity gap closed.

import type { InboundMessage } from "../channel"
import type { MessageStore } from "../../core/session-manager/messages"
import { makeLogger } from "../../shared/log"

const log = makeLogger("channels/web/inbound-handler")

export interface WebInboundDeps {
  messageLog: MessageStore
  /** THE inbound funnel (SessionManager.deliver): kind-routes the turn to the
   * agent adapter or claude's shim socket. Receives the full meta — including
   * attachment_* keys when present — so uploads survive every agent kind. */
  deliver: (session_id: string, text: string, meta: Record<string, string>) => Promise<void>
  hasSession: (id: string) => boolean
  replyNoSuchSession: (chat_id: string, sessionId: string) => Promise<void>
}

export async function handleWebInbound(msg: InboundMessage, deps: WebInboundDeps): Promise<void> {
  const sessionId = msg.target_session_id
  if (!sessionId || !deps.hasSession(sessionId)) {
    await deps.replyNoSuchSession(msg.chat_id, sessionId ?? "<unknown>")
    return
  }
  try {
    deps.messageLog.append(sessionId, {
      id: `in:${msg.chat_id}:${msg.message_id}`,
      ts: msg.ts,
      direction: "inbound",
      channel: "web",
      chat_id: msg.chat_id,
      message_id: msg.message_id,
      text: msg.text,
      attachments: msg.attachments,
    })
  } catch (err: any) {
    log.error("messages_append_failed", { session: sessionId, err: err?.message ?? String(err) })
  }
  // Single meta build so attachment_* keys never get lost in branching.
  const meta: Record<string, string> = {
    chat_id: msg.chat_id,
    message_id: msg.message_id,
    user: msg.user,
    user_id: msg.user_id,
    ts: msg.ts,
    ...(msg.attachments?.[0] ? {
      attachment_kind: msg.attachments[0].kind,
      attachment_file_id: msg.attachments[0].file_id,
      ...(msg.attachments[0].size != null ? { attachment_size: String(msg.attachments[0].size) } : {}),
      ...(msg.attachments[0].mime ? { attachment_mime: msg.attachments[0].mime } : {}),
      ...(msg.attachments[0].name ? { attachment_name: msg.attachments[0].name } : {}),
    } : {}),
  }
  await deps.deliver(sessionId, msg.text ?? "", meta)
}
