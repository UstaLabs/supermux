import type { AttachmentRef } from "@/stores/messages"

export interface UploadResult {
  file_id: string
  size: number
  mime: string
  name: string
}

export function useUploader() {
  async function upload(session: string, file: File, kindHint?: AttachmentRef["kind"]): Promise<UploadResult> {
    const fd = new FormData()
    fd.append("session", session)
    fd.append("file", file)
    if (kindHint) fd.append("kind", kindHint)
    const res = await fetch("/upload", {
      method: "POST",
      body: fd,
    })
    if (!res.ok) {
      const text = await res.text().catch(() => "")
      throw new Error(`upload failed: ${res.status} ${text}`)
    }
    return res.json() as Promise<UploadResult>
  }

  return { upload }
}
