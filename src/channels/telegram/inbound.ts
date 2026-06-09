// src/channels/telegram/inbound.ts
//
// Builds an InboundMessage from a grammy ctx, eagerly downloading any
// attachment through FileStore so the rest of the system sees a synthetic
// (channel-agnostic) file_id rather than a Telegram-namespaced one.
import { readFileSync, unlinkSync } from "fs"
import { downloadAttachment, type DownloadableApi } from "../../core/session-manager/download"
import type { FileStore, AttachmentKind } from "../../core/files/store"
import type { InboundAttachment, InboundMessage } from "../channel"
import { makeLogger } from "../../shared/log"

const log = makeLogger("channels/telegram/inbound")

// /tmp staging dir for the raw download. FileStore.put copies the bytes into
// the canonical store, so this file becomes garbage immediately. We attempt
// best-effort cleanup; leaving them around is acceptable (Linux clears /tmp
// on reboot) but tidy housekeeping helps in long-running deployments.
const TG_INBOUND_TMP = "/tmp/cmux-tg-dl"

type Opts = {
  ctx: any
  api: DownloadableApi
  fileStore: FileStore
}

type TelegramRawAttachment = {
  kind: AttachmentKind
  file_id: string
  mime?: string
  name?: string
  size?: number
}

function pickRawAttachment(m: any): TelegramRawAttachment | null {
  // Telegram's update shape distinguishes file kinds by which field is set on
  // the message. voice/audio/video_note/document are mutually exclusive top-
  // level fields. photo is an array of resolutions (smallest → largest); we
  // take the largest, matching the prior in-channel behavior.
  if (m.voice) {
    return { kind: "voice", file_id: m.voice.file_id, mime: m.voice.mime_type, size: m.voice.file_size }
  }
  if (m.audio) {
    return { kind: "audio", file_id: m.audio.file_id, mime: m.audio.mime_type, size: m.audio.file_size, name: m.audio.file_name }
  }
  if (m.video_note) {
    return { kind: "video_note", file_id: m.video_note.file_id, mime: m.video_note.mime_type, size: m.video_note.file_size }
  }
  if (m.document) {
    return { kind: "document", file_id: m.document.file_id, mime: m.document.mime_type, size: m.document.file_size, name: m.document.file_name }
  }
  if (Array.isArray(m.photo) && m.photo.length > 0) {
    const largest = m.photo[m.photo.length - 1]
    return { kind: "photo", file_id: largest.file_id, size: largest.file_size }
  }
  return null
}

export async function normalizeTelegramInbound(opts: Opts): Promise<InboundMessage> {
  const { ctx, api, fileStore } = opts
  const m: any = ctx.message
  const raw = pickRawAttachment(m)

  let attachments: InboundAttachment[] | undefined
  if (raw) {
    // tmpPath is hoisted so the finally block can clean up on BOTH success
    // (FileStore.put copied the bytes into the canonical store) and failure
    // (download succeeded but fileStore.put threw — leaving the staging file
    // behind would accumulate in /tmp over a long-running deployment).
    let tmpPath: string | undefined
    try {
      tmpPath = await downloadAttachment(api, raw.file_id, TG_INBOUND_TMP)
      const bytes = readFileSync(tmpPath)
      const stored = await fileStore.put({
        kind: raw.kind,
        mime: raw.mime,
        name: raw.name,
        session: undefined, // resolved by routing later
        origin: "telegram-dl",
        bytes,
      })
      attachments = [{
        kind: raw.kind,
        file_id: stored.file_id,
        mime: raw.mime,
        size: bytes.length,
        name: raw.name,
      }]
    } catch (err: any) {
      log.warn("eager_download_failed_dropping_attachment", {
        err: err?.message ?? String(err),
        telegram_file_id: raw.file_id,
        kind: raw.kind,
      })
      // attachments stays undefined → the rest of the message still flows
    } finally {
      if (tmpPath) {
        try {
          unlinkSync(tmpPath)
        } catch (e: any) {
          if (e?.code !== "ENOENT") {
            log.warn("temp_unlink_failed", { path: tmpPath, err: e?.message ?? String(e) })
          }
        }
      }
    }
  }

  return {
    channel: "telegram",
    chat_id: `telegram:${String(ctx.chat.id)}`,
    message_id: String(m.message_id),
    user: m.from?.username ?? String(m.from?.id ?? ""),
    user_id: String(m.from?.id ?? ""),
    ts: new Date((m.date ?? 0) * 1000).toISOString(),
    text: m.text ?? m.caption ?? "",
    reply_to_message_id: m.reply_to_message ? String(m.reply_to_message.message_id) : undefined,
    attachments,
  }
}
