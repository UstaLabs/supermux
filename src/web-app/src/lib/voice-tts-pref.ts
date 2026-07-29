/** Cached read-aloud engine preference from app-config (platform | codex). */
let cached: string = "platform"
let loaded = false
let inflight: Promise<string> | null = null

export function getVoiceTtsEngineCached(): string {
  return cached
}

export function setVoiceTtsEngineCached(engine: string) {
  cached = engine === "codex" ? "codex" : "platform"
  loaded = true
}

/** Load from broker if not yet loaded; returns platform | codex. */
export async function loadVoiceTtsEngine(fetcher: () => Promise<{ voiceTtsEngine?: string }>): Promise<string> {
  if (loaded) return cached
  if (inflight) return inflight
  inflight = (async () => {
    try {
      const cfg = await fetcher()
      setVoiceTtsEngineCached(typeof cfg.voiceTtsEngine === "string" ? cfg.voiceTtsEngine : "platform")
    } catch {
      setVoiceTtsEngineCached("platform")
    } finally {
      inflight = null
    }
    return cached
  })()
  return inflight
}
