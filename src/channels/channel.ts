export interface ChannelCapabilities {
  multiplexesSessions: boolean
  supportsReactions: boolean
  supportsEdit: boolean
  supportsAttachments: boolean
}

export interface OutboundAttachmentRef {
  file_id: string
  kind: "photo" | "document" | "voice" | "audio" | "video" | "video_note"
  mime?: string
  size?: number
  name?: string
}

export type OutboundAction =
  | { op: "reply"; chat_id: string; text: string; reply_to?: string; files?: string[]; attachments?: OutboundAttachmentRef[]; format?: "text" | "markdownv2"; keyboard?: string[]; disable_notification?: boolean }
  | { op: "react"; chat_id: string; message_id: string; emoji: string }
  | { op: "edit_message"; chat_id: string; message_id: string; text: string; format?: "text" | "markdownv2" }
  | { op: "download_attachment"; file_id: string }

export type OutboundResult =
  | { ok: true; value?: unknown }
  | { ok: false; error: string }

/**
 * What the broker knows about a message and a channel may need to deliver it.
 *
 * The web channel needs the session id and the stored entry, because its wire
 * format IS the transcript row. Telegram and WhatsApp ignore this — they send
 * text over HTTP and their service holds it for the user.
 *
 * A channel must NOT use this to look a session up. It carries only what the
 * broker already decided.
 */
export interface OutboundContext {
  sessionId: string
  entry: {
    id: string
    ts: string
    direction: "inbound" | "outbound"
    channel: string
    chat_id: string
    op?: string
    text?: string
    error?: boolean
    attachments?: Array<{ file_id: string; kind: string; mime?: string; size?: number; name?: string }>
  }
}

export interface InboundAttachment {
  kind: "voice" | "photo" | "document" | "audio" | "video" | "video_note"
  file_id: string
  mime?: string
  size?: number
  name?: string
}

export interface InboundMessage {
  channel: string                  // "telegram" | "web" | ...
  chat_id: string                  // namespaced: "telegram:8264..." | "web:device-abc"
  message_id: string
  user: string
  user_id: string
  ts: string                       // ISO 8601
  text?: string
  reply_to_message_id?: string
  target_session_id?: string       // set by channels with multiplexesSessions=false
  attachments?: InboundAttachment[]
}

export interface Channel {
  readonly name: string
  readonly capabilities: ChannelCapabilities
  start(): Promise<void>
  stop(): Promise<void>
  /**
   * Deliver the action to a client. It MUST attempt real I/O, and it MUST
   * return `{ok:false}` when it did not — a channel never reports success for
   * work it did not do. An op the channel cannot perform is a failure too.
   *
   * "Delivered" does not require the user to be present. Telegram and WhatsApp
   * hand the message to a service that holds it; the web channel's message is
   * already in the transcript before this call, so an absent client is success.
   */
  send(action: OutboundAction, ctx?: OutboundContext): Promise<OutboundResult>
  on(event: "inbound", handler: (msg: InboundMessage) => void): void
}
