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
  send(action: OutboundAction): Promise<OutboundResult>
  on(event: "inbound", handler: (msg: InboundMessage) => void): void
}
