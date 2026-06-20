export function useTranscriber() {
  async function transcribe(sessionId: string, blob: Blob, filename: string): Promise<{ text: string; degraded?: boolean }> {
    const fd = new FormData()
    fd.append("audio", new File([blob], filename, { type: blob.type }))
    const res = await fetch(`/sessions/${encodeURIComponent(sessionId)}/transcribe`, { method: "POST", body: fd })
    if (!res.ok) throw new Error(`transcribe failed: ${res.status}`)
    return res.json() as Promise<{ text: string; degraded?: boolean }>
  }
  return { transcribe }
}
