import { extname } from "path"
import { InputFile } from "grammy"

export type SendReplyArgs = {
  chat_id: string
  text: string
  reply_to?: string
  disable_notification: boolean
  files?: string[]
  keyboard?: string[]
  format?: "text" | "markdownv2"
}

export type EditMessageArgs = { chat_id: string; message_id: string; text: string; format?: "text" | "markdownv2" }
export type ReactArgs = { chat_id: string; message_id: string; emoji: string }

export type BotApi = {
  sendReply: (a: SendReplyArgs) => Promise<{ message_id: number }>
  editMessage: (a: EditMessageArgs) => Promise<void>
  react: (a: ReactArgs) => Promise<void>
}

const IMAGE_EXT = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif"])
const VOICE_EXT = new Set([".ogg", ".opus"])
const VIDEO_EXT = new Set([".mp4", ".mov", ".m4v", ".webm", ".mkv"])

export function createBotApi(api: any): BotApi {
  return {
    async sendReply(a) {
      const parseMode = a.format === "markdownv2" ? "MarkdownV2" : undefined
      const reply_parameters = a.reply_to ? { message_id: Number(a.reply_to) } : undefined
      const reply_markup = a.keyboard ? {
        keyboard: a.keyboard.map(t => [{ text: t }]),
        one_time_keyboard: true, resize_keyboard: true,
      } : undefined
      const baseOpts = {
        disable_notification: a.disable_notification,
        ...(parseMode ? { parse_mode: parseMode } : {}),
        ...(reply_parameters ? { reply_parameters } : {}),
        ...(reply_markup ? { reply_markup } : {}),
      }
      if (a.files && a.files.length > 0) {
        let firstSent: any
        for (let i = 0; i < a.files.length; i++) {
          const path = a.files![i]!
          const ext = extname(path).toLowerCase()
          const caption = i === 0 ? a.text : undefined
          const opts = { caption, ...baseOpts }
          // Local paths must be wrapped in InputFile for grammy >= 1.x; raw strings
          // are interpreted as URLs (Telegram returns "URL host is empty" for "/tmp/...").
          // Pass-through http(s):// URLs unwrapped.
          const fileArg = /^https?:\/\//.test(path) ? path : new InputFile(path)
          const sent = IMAGE_EXT.has(ext)
            ? await api.sendPhoto(a.chat_id, fileArg, opts)
            : VOICE_EXT.has(ext)
              ? await api.sendVoice(a.chat_id, fileArg, opts)
              : VIDEO_EXT.has(ext)
                ? await api.sendVideo(a.chat_id, fileArg, opts)
                : await api.sendDocument(a.chat_id, fileArg, opts)
          if (i === 0) firstSent = sent
        }
        return { message_id: firstSent.message_id }
      }
      const sent = await api.sendMessage(a.chat_id, a.text, baseOpts)
      return { message_id: sent.message_id }
    },
    async editMessage(a) {
      const parseMode = a.format === "markdownv2" ? "MarkdownV2" : undefined
      await api.editMessageText(a.chat_id, Number(a.message_id), a.text, parseMode ? { parse_mode: parseMode } : {})
    },
    async react(a) {
      await api.setMessageReaction(a.chat_id, Number(a.message_id), [{ type: "emoji", emoji: a.emoji }])
    },
  }
}
