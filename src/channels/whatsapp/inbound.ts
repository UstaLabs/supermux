import type { FileStore } from "../../core/files/store"
import type { AttachmentKind } from "../../core/files/kinds"
import type { InboundAttachment, InboundMessage } from "../channel"
import { makeLogger } from "../../shared/log"

const log = makeLogger("channels/whatsapp/inbound")

export interface WhatsAppNormalizeDeps {
  gowa: {
    fetchMedia(pathOrUrl: string): Promise<Uint8Array>
    downloadMedia(messageId: string, phone: string): Promise<string>
  }
  fileStore: Pick<FileStore, "put">
}

const MEDIA_FIELDS = ["image", "audio", "document", "video", "sticker"] as const
type MediaField = (typeof MEDIA_FIELDS)[number]

function pickMedia(p: any): { field: MediaField; raw: any } | null {
  for (const field of MEDIA_FIELDS) if (p[field] != null) return { field, raw: p[field] }
  return null
}

// WhatsApp lumps voice notes and audio under `audio`; a `.ogg` is a voice note
// (parity with Telegram's `voice`). image→photo, document→document; video and
// sticker fall back to document for v1 (tier B is text+image+document+voice).
function mediaKind(field: MediaField, pathOrUrl: string): AttachmentKind {
  if (field === "image") return "photo"
  if (field === "audio") return pathOrUrl.toLowerCase().endsWith(".ogg") ? "voice" : "audio"
  return "document"
}

// The GOWA webhook carries no MIME field, but the served file path/filename has
// an extension. FileStore derives the on-disk extension from `mime` (undefined →
// `.bin`), so map the extension back to a MIME (parity with how Telegram passes
// a real mime through). Returns undefined for unknown extensions.
const EXT_MIME: Record<string, string> = {
  ".ogg": "audio/ogg",
  ".opus": "audio/ogg",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".pdf": "application/pdf",
  ".mp4": "video/mp4",
  ".m4a": "audio/mp4",
}

function mimeFromPath(pathOrUrl: string, name?: string): string | undefined {
  const source = name && name.includes(".") ? name : pathOrUrl
  const lower = source.toLowerCase()
  const dot = lower.lastIndexOf(".")
  if (dot < 0) return undefined
  // strip any query string on a served URL (e.g. ".jpeg?foo=1")
  const ext = lower.slice(dot).split("?")[0] ?? ""
  return EXT_MIME[ext]
}

// `message` is the INNER GOWA webhook message-payload object (the one with
// `id` / `chat_id` / `from` / `from_name` / `timestamp` / `body` /
// `replied_to_id` and media fields like `image` / `audio` / `document`). The
// caller (the webhook handler) has already unwrapped the outer
// `{ event, device_id, payload }` envelope — do NOT unwrap `.payload` again here.
export async function normalizeWhatsAppInbound(message: any, deps: WhatsAppNormalizeDeps): Promise<InboundMessage> {
  const p = message ?? {}
  const chatId = String(p.chat_id ?? p.from ?? "")
  let attachments: InboundAttachment[] | undefined

  const media = pickMedia(p)
  if (media) {
    try {
      const raw = media.raw
      let ref: { pathOrUrl: string; name?: string } | null = null
      if (typeof raw === "string") ref = { pathOrUrl: raw }
      else if (raw?.path) ref = { pathOrUrl: String(raw.path), name: raw.filename ? String(raw.filename) : undefined }
      else if (raw?.url) {
        // auto-download OFF: ask GOWA to decrypt+save, then fetch the served file
        const fileUrl = await deps.gowa.downloadMedia(String(p.id ?? ""), chatId)
        ref = { pathOrUrl: fileUrl, name: raw.filename ? String(raw.filename) : undefined }
      }
      if (ref) {
        const bytes = await deps.gowa.fetchMedia(ref.pathOrUrl)
        const kind = mediaKind(media.field, ref.pathOrUrl)
        const mime = mimeFromPath(ref.pathOrUrl, ref.name)
        const stored = await deps.fileStore.put({ kind, mime, name: ref.name, origin: "whatsapp-dl", bytes })
        attachments = [{ kind, file_id: stored.file_id, mime, size: bytes.length, name: ref.name }]
      }
    } catch (err: any) {
      log.warn("eager_download_failed_dropping_attachment", { err: err?.message ?? String(err), id: String(p.id ?? ""), field: media.field })
      // attachments stays undefined → message still flows
    }
  }

  return {
    channel: "whatsapp",
    chat_id: `whatsapp:${chatId}`,
    message_id: String(p.id ?? ""),
    user: String(p.from_name ?? p.from ?? ""),
    user_id: String(p.from ?? ""),
    ts: typeof p.timestamp === "string" && p.timestamp ? p.timestamp : new Date().toISOString(),
    text: typeof p.body === "string" ? p.body : "",
    reply_to_message_id: p.replied_to_id ? String(p.replied_to_id) : undefined,
    attachments,
  }
}
