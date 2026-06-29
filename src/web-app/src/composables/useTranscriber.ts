export function useTranscriber() {
  // `sessionId` is OPTIONAL: a live session enriches the cleanup with prior-message context, but
  // it isn't required — the pre-spawn launcher posts to the id-less `/transcribe` and still gets
  // the whisper + agent-cleanup pass (engine/model/glossary all come from global config).
  async function transcribe(sessionId: string | undefined, blob: Blob, filename: string): Promise<{ text: string; degraded?: boolean }> {
    const fd = new FormData()
    fd.append("audio", new File([blob], filename, { type: blob.type }))
    const path = sessionId ? `/sessions/${encodeURIComponent(sessionId)}/transcribe` : `/transcribe`
    const res = await fetch(path, { method: "POST", body: fd })
    if (!res.ok) { const t = await res.text().catch(() => ""); throw new Error(`transcribe failed: ${res.status} ${t}`) }
    return res.json() as Promise<{ text: string; degraded?: boolean }>
  }
  return { transcribe }
}
