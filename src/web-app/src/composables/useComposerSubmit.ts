import type { MaybeRefOrGetter } from "vue"
import { toValue } from "vue"
import { toast } from "vue-sonner"
import { useWS } from "@/api/ws"
import { useUploader } from "@/composables/useUploader"
import { useMessages } from "@/stores/messages"
import { useUploads } from "@/stores/uploads"
import { useVoicePreviews } from "@/stores/voice-previews"
import type { PromptInputMessage } from "@/components/ai-elements/prompt-input"
import type { AttachmentRef } from "@/stores/messages"

function webMessageId(): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2, 10)
  return `web-${Date.now()}-${random}`
}

export function useComposerSubmit(sessionId: MaybeRefOrGetter<string>) {
  const ws = useWS()
  const messages = useMessages()
  const uploader = useUploader()
  const uploads = useUploads()
  const voicePreviews = useVoicePreviews()

  function send(text: string, attachments?: AttachmentRef[]) {
    const id = toValue(sessionId)
    const t = text?.trim() ?? ""
    if (!t && (!attachments || attachments.length === 0)) return
    const messageId = webMessageId()
    messages.append(id, {
      id: `in:web:${messageId}`,
      ts: new Date().toISOString(),
      direction: "inbound",
      channel: "web",
      chat_id: "web",
      message_id: messageId,
      text: t || undefined,
      attachments,
    })
    ws.send({
      type: "send",
      session: id,
      op: "reply",
      client_message_id: messageId,
      args: { text: t || undefined, attachments: attachments?.length ? attachments.map((a) => a.file_id) : undefined },
    })
    messages.markSent(id)
  }

  async function submit(payload: PromptInputMessage) {
    const id = toValue(sessionId)
    const files = (payload?.files ?? []) as unknown as Array<{ id: string; file?: File }>

    if (files.length === 0) {
      if (payload?.text) send(payload.text)
      return
    }

    const attachments: AttachmentRef[] = []
    for (const f of files) {
      if (!f.file) continue
      const kindHint = (f.file as { _cmuxKind?: AttachmentRef["kind"] })?._cmuxKind
      const current = uploads.get(f.id)
      if (current?.status === "uploaded") {
        attachments.push({
          kind: kindHint ?? "document",
          file_id: current.result.file_id,
          mime: current.result.mime,
          size: current.result.size,
          name: current.result.name,
        })
        continue
      }
      uploads.start(f.id)
      const fileName = f.file?.name ?? "file"
      const uploadingToastId = toast.loading(`Uploading ${fileName}…`)
      try {
        const result = await uploader.upload(id, f.file, kindHint)
        toast.dismiss(uploadingToastId)
        uploads.succeed(f.id, result)
        attachments.push({
          kind: kindHint ?? "document",
          file_id: result.file_id,
          mime: result.mime,
          size: result.size,
          name: result.name,
        })
      } catch (err: unknown) {
        toast.dismiss(uploadingToastId)
        const msg = err instanceof Error ? err.message : String(err)
        uploads.fail(f.id, msg)
        toast.error("Upload failed", { description: `${fileName}: ${msg}` })
        throw new Error(`Failed to upload ${fileName}: ${msg}`)
      }
    }

    send(payload?.text ?? "", attachments)
    uploads.clearAll()
    voicePreviews.clearAll()
  }

  return { send, submit }
}
