import { extname } from "path"
import type { Channel, ChannelCapabilities, InboundMessage, OutboundAction, OutboundResult } from "../channel"
import type { FileStore } from "../../core/files/store"
import { GowaClient, type GowaMediaKind } from "./gowa-api"
import { normalizeWhatsAppInbound } from "./inbound"
import { createWebhookHandler, WhatsAppWebhookServer } from "./webhook"
import { loadWhatsAppAccess, isWhatsAppAllowed } from "./access"
import { makeLogger } from "../../shared/log"
import { ACCESS_FILE } from "../../shared/paths"

const log = makeLogger("channels/whatsapp")
const IMAGE_EXT = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif"])
const AUDIO_EXT = new Set([".ogg", ".opus", ".mp3", ".m4a", ".wav", ".aac"])

export interface WhatsAppChannelOpts {
  gowaUrl: string
  gowaBasicAuth?: string
  gowaDeviceId?: string
  webhookPort: number
  webhookSecret: string
  fileStore: FileStore
}

export class WhatsAppChannel implements Channel {
  readonly name = "whatsapp"
  readonly capabilities: ChannelCapabilities = {
    multiplexesSessions: true,
    supportsReactions: false,
    supportsEdit: false,
    supportsAttachments: true,
  }
  private readonly gowa: GowaClient
  private readonly fileStore: FileStore
  private readonly server: WhatsAppWebhookServer
  private inboundHandlers: Array<(m: InboundMessage) => void> = []

  constructor(opts: WhatsAppChannelOpts) {
    this.fileStore = opts.fileStore
    this.gowa = new GowaClient({ baseUrl: opts.gowaUrl, basicAuth: opts.gowaBasicAuth, deviceId: opts.gowaDeviceId })
    const handler = createWebhookHandler({ secret: opts.webhookSecret, onMessage: (p) => { void this.onPayload(p) } })
    this.server = new WhatsAppWebhookServer(opts.webhookPort, handler)
  }

  on(event: "inbound", handler: (m: InboundMessage) => void): void {
    if (event === "inbound") this.inboundHandlers.push(handler)
  }

  async start(): Promise<void> {
    this.server.start()
    try {
      const st = await this.gowa.status()
      if (!st.is_logged_in) log.warn("whatsapp_not_logged_in", { hint: "pair the secondary number via GOWA GET /app/login (QR) or /app/login-with-code" })
      else log.info("whatsapp_ready", {})
    } catch (err: any) {
      log.warn("whatsapp_status_probe_failed", { err: err?.message ?? String(err) })
    }
    log.info("whatsapp channel listening", { port: this.server.boundPort })
  }

  async stop(): Promise<void> {
    await this.server.stop()
  }

  async send(action: OutboundAction): Promise<OutboundResult> {
    try {
      if (action.op !== "reply") return { ok: false, error: `whatsapp: unsupported op "${action.op}"` }
      const phone = toJid(action.chat_id)
      if (action.files && action.files.length > 0) {
        let firstId = ""
        for (let i = 0; i < action.files.length; i++) {
          const path = action.files[i]!
          const caption = i === 0 ? action.text : undefined
          const ext = extname(path).toLowerCase()
          const kind: GowaMediaKind = IMAGE_EXT.has(ext) ? "image" : AUDIO_EXT.has(ext) ? "audio" : "file"
          const r = await this.gowa.sendMedia(kind, phone, path, { caption, replyTo: action.reply_to })
          if (i === 0) firstId = r.message_id
        }
        return { ok: true, value: { message_id: firstId } }
      }
      const r = await this.gowa.sendText(phone, action.text, action.reply_to)
      return { ok: true, value: { message_id: r.message_id } }
    } catch (err: any) {
      return { ok: false, error: String(err?.message ?? err) }
    }
  }

  private async onPayload(payload: any): Promise<void> {
    const fromJid = String(payload?.from ?? "")
    if (!isWhatsAppAllowed(loadWhatsAppAccess(ACCESS_FILE), fromJid)) {
      log.warn("access_dropped_inbound", { from: fromJid })
      return
    }
    let msg: InboundMessage
    try {
      msg = await normalizeWhatsAppInbound(payload, { gowa: this.gowa, fileStore: this.fileStore })
    } catch (err: any) {
      log.error("whatsapp_inbound_normalize_failed", { err: err?.message ?? String(err) })
      return
    }
    for (const h of this.inboundHandlers) {
      try { h(msg) } catch (err: any) { log.error("whatsapp inbound handler threw", { err: err?.message ?? String(err) }) }
    }
  }
}

// "whatsapp:<jid|number>" → a GOWA `phone` JID.
function toJid(chatId: string): string {
  const raw = chatId.startsWith("whatsapp:") ? chatId.slice("whatsapp:".length) : chatId
  return raw.includes("@") ? raw : `${raw}@s.whatsapp.net`
}
