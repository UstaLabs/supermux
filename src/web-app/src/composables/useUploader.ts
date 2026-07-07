import type { AttachmentRef } from "@/stores/messages"

export interface UploadResult {
  file_id: string
  size: number
  mime: string
  name: string
}

export type UploadProgress = (sent: number, total: number) => void

const DEFAULT_THRESHOLD_BYTES = 5 * 1024 * 1024
const MAX_RESUME_ATTEMPTS = 5

export function useUploader(opts?: { thresholdBytes?: number }) {
  const threshold = opts?.thresholdBytes ?? DEFAULT_THRESHOLD_BYTES

  // Small files: one raw octet-stream POST (unchanged wire shape). fetch can't
  // report progress, so we emit 0 then 100 — small files are near-instant.
  async function uploadSingle(session: string, file: File, kindHint?: AttachmentRef["kind"], onProgress?: UploadProgress): Promise<UploadResult> {
    onProgress?.(0, file.size)
    const headers: Record<string, string> = {
      "Content-Type": "application/octet-stream",
      "X-Mux-Session": session,
      "X-Mux-Mime": file.type,
      "X-Mux-Filename": encodeURIComponent(file.name),
    }
    if (kindHint) headers["X-Mux-Kind"] = kindHint
    const res = await fetch("/upload", { method: "POST", headers, body: file })
    if (!res.ok) {
      const text = await res.text().catch(() => "")
      throw new Error(`upload failed: ${res.status} ${text}`)
    }
    onProgress?.(file.size, file.size)
    return res.json() as Promise<UploadResult>
  }

  // Large files: init → PATCH slices → finalize, resuming from the server offset
  // (HEAD) on a dropped chunk. The browser streams each Blob slice from disk, so
  // memory stays bounded to one chunk.
  async function uploadChunked(session: string, file: File, kindHint?: AttachmentRef["kind"], onProgress?: UploadProgress): Promise<UploadResult> {
    const initRes = await fetch("/upload/init", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ session, mime: file.type, name: file.name, kind: kindHint, total_size: file.size }),
    })
    if (!initRes.ok) throw new Error(`upload init failed: ${initRes.status}`)
    const { upload_id, chunk_size } = (await initRes.json()) as { upload_id: string; chunk_size: number }

    let offset = 0
    let attempts = 0
    for (;;) {
      const end = Math.min(offset + chunk_size, file.size)
      const slice = file.slice(offset, end)
      try {
        const resp = await fetch(`/upload/${upload_id}`, {
          method: "PATCH",
          headers: { "upload-offset": String(offset) },
          body: slice,
        })
        if (resp.status === 200) {
          const j = (await resp.json()) as { offset?: number; file_id?: string; size?: number; mime?: string; name?: string }
          if (j.file_id) {
            onProgress?.(file.size, file.size)
            return { file_id: j.file_id, size: j.size ?? file.size, mime: j.mime ?? file.type, name: j.name ?? file.name }
          }
          offset = j.offset ?? end
          attempts = 0
          onProgress?.(offset, file.size)
        } else if (resp.status === 409) {
          offset = Number(resp.headers.get("upload-offset") ?? offset)
        } else {
          const text = await resp.text().catch(() => "")
          throw new Error(`upload chunk failed: ${resp.status} ${text}`)
        }
      } catch (err) {
        if (++attempts > MAX_RESUME_ATTEMPTS) throw err
        const serverOffset = await headOffset(upload_id)
        if (serverOffset === null) throw err
        offset = serverOffset
      }
    }
  }

  async function headOffset(upload_id: string): Promise<number | null> {
    const resp = await fetch(`/upload/${upload_id}`, { method: "HEAD" })
    if (resp.status !== 200) return null
    const h = resp.headers.get("upload-offset")
    return h === null ? null : Number(h)
  }

  async function upload(session: string, file: File, kindHint?: AttachmentRef["kind"], onProgress?: UploadProgress): Promise<UploadResult> {
    return file.size <= threshold
      ? uploadSingle(session, file, kindHint, onProgress)
      : uploadChunked(session, file, kindHint, onProgress)
  }

  return { upload }
}
