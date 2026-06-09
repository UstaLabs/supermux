// src/core/files/kinds.ts
export type AttachmentKind = "photo" | "document" | "voice" | "audio" | "video_note"

export function kindFromMime(mime: string | undefined): AttachmentKind {
  if (!mime) return "document"
  if (mime.startsWith("image/")) return "photo"
  if (mime.startsWith("audio/")) return "audio"
  if (mime.startsWith("video/")) return "video_note"
  return "document"
}