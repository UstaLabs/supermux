// src/channels/telegram/index.ts
import { Bot } from "grammy"
import type { Channel, ChannelCapabilities, InboundMessage, OutboundAction, OutboundResult } from "../channel"
import { createBotApi } from "./bot-api"
import { loadAccess, isAllowed } from "./access"
import { setMenu } from "./menu"
import { normalizeTelegramInbound } from "./inbound"
import type { FileStore } from "../../core/files/store"
import { makeLogger } from "../../shared/log"
import { ACCESS_FILE } from "../../shared/paths"

const log = makeLogger("channels/telegram")

export interface TelegramChannelOpts {
  token: string
  fileStore: FileStore
}

export class TelegramChannel implements Channel {
  readonly name = "telegram"
  readonly capabilities: ChannelCapabilities = {
    multiplexesSessions: true,
    supportsReactions: true,
    supportsEdit: true,
    supportsAttachments: true,
  }
  private readonly bot: Bot
  private readonly api: ReturnType<typeof createBotApi>
  private readonly token: string
  private readonly fileStore: FileStore
  private inboundHandlers: Array<(m: InboundMessage) => void> = []

  constructor(opts: TelegramChannelOpts) {
    this.bot = new Bot(opts.token)
    this.api = createBotApi(this.bot.api)
    this.token = opts.token
    this.fileStore = opts.fileStore
    this.bot.on("message", (ctx) => this.handleIncoming(ctx))
  }

  on(event: "inbound", handler: (m: InboundMessage) => void): void {
    if (event === "inbound") this.inboundHandlers.push(handler)
  }

  async start(): Promise<void> {
    const MAX_RETRIES = 6
    for (let attempt = 0; ; attempt++) {
      try {
        await this.bot.start()
        return
      } catch (err: any) {
        if (err?.error_code === 409 && attempt < MAX_RETRIES) {
          const delay = Math.min(5_000 * (attempt + 1), 30_000)
          log.warn("telegram_polling_conflict", { attempt: attempt + 1, retryInMs: delay })
          await new Promise((r) => setTimeout(r, delay))
          continue
        }
        throw err
      }
    }
  }

  async stop(): Promise<void> {
    await this.bot.stop()
  }

  async send(action: OutboundAction): Promise<OutboundResult> {
    try {
      if (action.op === "reply") {
        // strip "telegram:" prefix back to raw chat_id for the API
        const rawChat = action.chat_id.startsWith("telegram:")
          ? action.chat_id.slice("telegram:".length)
          : action.chat_id
        const sent = await this.api.sendReply({
          chat_id: rawChat,
          text: action.text,
          reply_to: action.reply_to,
          files: action.files,
          format: action.format,
          keyboard: action.keyboard,
          disable_notification: action.disable_notification ?? false,
        })
        return { ok: true, value: { message_id: sent.message_id } }
      } else if (action.op === "react") {
        const rawChat = action.chat_id.replace(/^telegram:/, "")
        await this.api.react({ chat_id: rawChat, message_id: action.message_id, emoji: action.emoji })
        return { ok: true, value: "reacted" }
      } else if (action.op === "edit_message") {
        const rawChat = action.chat_id.replace(/^telegram:/, "")
        await this.api.editMessage({ chat_id: rawChat, message_id: action.message_id, text: action.text, format: action.format })
        return { ok: true, value: "edited" }
      } else if (action.op === "download_attachment") {
        // delegated to caller via separate path; not used in v1 from Telegram outbound
        return { ok: false, error: "download_attachment not implemented at channel level" }
      }
      return { ok: false, error: `unknown op` }
    } catch (err: any) {
      return { ok: false, error: String(err?.message ?? err) }
    }
  }

  async refreshMenu(entries: Array<{ command: string; description: string }>): Promise<void> {
    await setMenu(this.bot.api, entries)
  }

  async getFile(file_id: string): Promise<any> {
    return this.bot.api.getFile(file_id)
  }

  private async handleIncoming(ctx: any): Promise<void> {
    const access = loadAccess(ACCESS_FILE)
    const senderId = String(ctx.message.from?.id ?? "")
    const chatType = ctx.chat.type
    if (!isAllowed(access, { chatType, chatId: String(ctx.chat.id), senderId })) {
      log.warn("access: dropped inbound", { sender_id: senderId, chat_id: String(ctx.chat.id), chat_type: chatType })
      return
    }

    let inbound: InboundMessage
    try {
      inbound = await normalizeTelegramInbound({
        ctx,
        api: { token: this.token, getFile: (id: string) => this.bot.api.getFile(id) },
        fileStore: this.fileStore,
      })
    } catch (err: any) {
      log.error("telegram_inbound_normalize_failed", { err: err?.message ?? String(err) })
      return
    }

    for (const h of this.inboundHandlers) {
      try { h(inbound) } catch (err: any) { log.error("telegram inbound handler threw", { err: err?.message ?? String(err) }) }
    }
  }
}
