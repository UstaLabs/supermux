// Inbound attachments on the string-valued turn meta.
//
// The flat attachment_* keys only ever had room for one file, so a message with
// several uploads reached the agent with just the first. The flat keys still
// describe attachments[0] (older readers keep working); `attachments` carries
// every file as a JSON array, and `attachment_count` says how many there are.

import type { InboundMeta } from "./types"

export type MetaAttachment = { file_id: string; kind?: string; mime?: string; size?: number; name?: string }

/** The attachment_* / attachments meta keys for an inbound message's files. */
export function attachmentMeta(attachments: readonly MetaAttachment[] | undefined): Record<string, string> {
  const first = attachments?.[0]
  if (!first) return {}
  return {
    ...(first.kind ? { attachment_kind: first.kind } : {}),
    attachment_file_id: first.file_id,
    ...(first.size != null ? { attachment_size: String(first.size) } : {}),
    ...(first.mime ? { attachment_mime: first.mime } : {}),
    ...(first.name ? { attachment_name: first.name } : {}),
    attachment_count: String(attachments.length),
    attachments: JSON.stringify(attachments.map((a) => ({
      file_id: a.file_id,
      ...(a.kind ? { kind: a.kind } : {}),
      ...(a.mime ? { mime: a.mime } : {}),
      ...(a.size != null ? { size: a.size } : {}),
      ...(a.name ? { name: a.name } : {}),
    }))),
  }
}

/** Every attachment on a turn's meta: the `attachments` array when present, else the single flat attachment_* file. */
export function inboundAttachments(meta: InboundMeta | undefined): MetaAttachment[] {
  if (!meta) return []
  if (meta.attachments) {
    try {
      const parsed = JSON.parse(meta.attachments) as unknown
      if (Array.isArray(parsed)) {
        const list = parsed.filter((a): a is MetaAttachment => typeof a?.file_id === "string" && a.file_id.length > 0)
        if (list.length > 0) return list
      }
    } catch {}
  }
  if (!meta.attachment_file_id) return []
  return [{
    file_id: meta.attachment_file_id,
    kind: meta.attachment_kind,
    mime: meta.attachment_mime,
    name: meta.attachment_name,
    ...(meta.attachment_size ? { size: Number(meta.attachment_size) } : {}),
  }]
}

export function isImageAttachment(a: MetaAttachment): boolean {
  return !!a.mime?.startsWith("image/") || a.kind === "photo" || a.kind === "image"
}
