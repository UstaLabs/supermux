import type { AttachmentRef } from "@/stores/messages"

export interface UploadResult {
  file_id: string
  size: number
  mime: string
  name: string
}

export function useUploader() {
  // Streaming upload: the raw file bytes ARE the request body, with metadata in
  // headers. Content-Type: application/octet-stream selects the broker's streaming
  // ingest path (see the broker plan); the browser sets Content-Length from the
  // File size for the server's fast up-front 413. The legacy multipart path is
  // retained server-side only for old app-store builds.
  async function upload(session: string, file: File, kindHint?: AttachmentRef["kind"]): Promise<UploadResult> {
    const headers: Record<string, string> = {
      "Content-Type": "application/octet-stream",
      "X-Mux-Session": session,
      "X-Mux-Mime": file.type,
      "X-Mux-Filename": encodeURIComponent(file.name),
    }
    if (kindHint) headers["X-Mux-Kind"] = kindHint
    const res = await fetch("/upload", {
      method: "POST",
      headers,
      body: file,
    })
    if (!res.ok) {
      const text = await res.text().catch(() => "")
      throw new Error(`upload failed: ${res.status} ${text}`)
    }
    return res.json() as Promise<UploadResult>
  }

  return { upload }
}
