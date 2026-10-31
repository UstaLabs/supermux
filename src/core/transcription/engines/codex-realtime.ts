// Codex / ChatGPT-subscription Realtime STT engine.
//
// Mints an ephemeral transcription session with the logged-in Codex OAuth token
// (~/.codex/auth.json), then streams 24 kHz mono PCM over the public Realtime
// WebSocket (`intent=transcription`). No platform `sk-` key required.
//
// Higher quality than local whisper-base → prefersCleanup is false (skip LLM
// rewrite). Whisper remains the universal fallback when this engine is
// unavailable or throws.

import { readFileSync, writeFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import { readPcm16 } from "../wav"
import { DictationConnections, RealtimeConnection, type TranscriptionConfig } from "./realtime-connection"
import type { SttEngine, SttResult, SttTranscribeOpts } from "../stt-types"

const log = makeLogger("stt-codex-realtime")

const OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
const OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
const MINT_URL = "https://api.openai.com/v1/realtime/client_secrets"
const WS_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
/**
 * gpt-transcribe (2026-07) replaced gpt-4o-transcribe, which INVENTS text for audio with no speech
 * in it — measured 2026-10-11 on 2.5 s of silence: "Bu sabah çok güzel bir breakfast yaptım.",
 * on room noise: "Bu yıl Christmas tatilini İstanbul'da geçireceğim." (no hint: "No, grazie.",
 * "بسم الله."). gpt-transcribe answers "" for both and also stopped dropping trailing clauses.
 */
const DEFAULT_MODEL = "gpt-transcribe"
const PCM_RATE = 24_000
/** ~100 ms of 24 kHz mono s16le. */
const PCM_CHUNK_BYTES = (PCM_RATE * 2) / 10
const DEFAULT_TIMEOUT_MS = 60_000

/** Known Realtime transcription model ids — ignore whisper path-like model opts. */
const KNOWN_MODELS = new Set([
  "gpt-transcribe",
  "gpt-live-transcribe",
  "gpt-4o-transcribe",
  "gpt-4o-mini-transcribe",
  "gpt-realtime-whisper",
  "whisper-1",
])

export type FetchFn = (input: string | URL | Request, init?: RequestInit) => Promise<Response>
export type ReadFileFn = (path: string) => string
export type WriteFileFn = (path: string, data: string) => void

/** Minimal WebSocket surface the engine needs (Bun/browser compatible). */
export interface RealtimeWs {
  readonly readyState: number
  readonly protocol: string
  send(data: string): void
  close(code?: number, reason?: string): void
  addEventListener(type: "open" | "message" | "error" | "close", listener: (ev: any) => void): void
  removeEventListener?(type: string, listener: (ev: any) => void): void
}

export type ConnectWsFn = (url: string, protocols: string[]) => RealtimeWs

export interface CodexRealtimeEngineOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  writeFileFn?: WriteFileFn
  authPath?: string
  connectWs?: ConnectWsFn
  /** Override availability probe (tests). */
  isAvailable?: () => boolean
  /** Default transcription model when opts.model is absent/unknown. */
  model?: string
  timeoutMs?: number
  /** Where a dictation's open connection lives between passes (tests inject their own). */
  connections?: DictationConnections
}

/**
 * Every dictation's open connection. Module-level because the broker builds a new engine object
 * for each request; the connection has to outlive it.
 */
const sharedConnections = new DictationConnections()
/** A pass on an open connection answers in well under a second; past this, go cold instead. */
const REUSED_PASS_TIMEOUT_MS = 8_000
const CONNECT_TIMEOUT_MS = 10_000

function defaultRead(path: string): string {
  return readFileSync(path, "utf8")
}

function readJson(read: ReadFileFn, path: string): any | null {
  try {
    return JSON.parse(read(path))
  } catch {
    return null
  }
}

async function refreshOAuth(fetchFn: FetchFn, refreshToken: string): Promise<any> {
  const res = await fetchFn(OAUTH_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: OAUTH_CLIENT_ID,
      refresh_token: refreshToken,
    }).toString(),
  })
  if (!res.ok) throw new Error(`codex-realtime: oauth refresh ${res.status}`)
  return res.json()
}

function resolveModel(requested: string | undefined, fallback: string): string {
  if (requested && KNOWN_MODELS.has(requested)) return requested
  return fallback
}

/** BCP-47 / whisper code → ISO-639-1 primary subtag ("en-US" → "en"); "" for auto/invalid. */
function primaryLang(code: string | undefined): string {
  if (!code || code === "auto") return ""
  const p = code.split(/[-_]/)[0]!.toLowerCase()
  return /^[a-z]{2,3}$/.test(p) ? p : ""
}

/** "tr" → "Turkish"; falls back to the code when the runtime has no name for it. */
function languageName(code: string): string {
  try {
    return new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code
  } catch {
    return code
  }
}

function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? ""
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`
}

/** Cap the glossary part of the prompt so a huge list can't crowd out the audio context. */
const PROMPT_TERMS_MAX_CHARS = 800

/**
 * Transcription hints for the Realtime session. `language` pins recognition,
 * so it is only sent when exactly one language is expected; mixed-language
 * speech (e.g. Turkish + English) is steered by the free-text `prompt`
 * instead, which also always carries the glossary so project and technical
 * names come out spelled right.
 */
export function transcriptionHints(
  opts: Pick<SttTranscribeOpts, "lang" | "languages" | "keyterms">,
): { language?: string; prompt?: string } {
  const fromList = (opts.languages ?? []).map(primaryLang).filter(Boolean)
  const langs = [...new Set(fromList.length ? fromList : [primaryLang(opts.lang)].filter(Boolean))]
  const parts: string[] = []
  const names = langs.map(languageName)
  if (langs.length === 1) {
    parts.push(`The speaker talks in ${names[0]}.`)
  } else if (langs.length > 1) {
    parts.push(
      `The speaker mixes ${joinNames(names)}, often switching mid-sentence. ` +
        "Write every word in the language it was spoken; do not translate.",
    )
  }
  const terms: string[] = []
  let len = 0
  const seen = new Set<string>()
  for (const raw of opts.keyterms ?? []) {
    const t = raw.replace(/\s+/g, " ").trim()
    if (!t || seen.has(t.toLowerCase())) continue
    if (len + t.length + 2 > PROMPT_TERMS_MAX_CHARS) break
    seen.add(t.toLowerCase())
    terms.push(t)
    len += t.length + 2
  }
  if (terms.length) parts.push(`Vocabulary, spelled exactly: ${terms.join(", ")}.`)
  return {
    ...(langs.length === 1 ? { language: langs[0] } : {}),
    ...(parts.length ? { prompt: parts.join(" ") } : {}),
  }
}

/** How much of the earlier transcript goes into the prompt — its tail is what the model needs. */
const PREVIOUS_TEXT_MAX_CHARS = 1_000

/**
 * The prompt of an incremental pass: the usual hints, plus the transcript of the audio before the
 * cut. The model continues it — consistent spelling, casing and language — without repeating it.
 */
export function continuationPrompt(base: string | undefined, previousText: string | undefined): string | undefined {
  const prev = (previousText ?? "").replace(/\s+/g, " ").trim()
  if (!prev) return base
  const tail = prev.length > PREVIOUS_TEXT_MAX_CHARS ? `…${prev.slice(-PREVIOUS_TEXT_MAX_CHARS)}` : prev
  const ctx = `This audio continues a dictation already transcribed as: "${tail}" ` +
    "Transcribe only the new audio, continuing that text; do not repeat it."
  return base ? `${base} ${ctx}` : ctx
}

/** 24 kHz mono s16le: bytes per millisecond. */
const PCM_BYTES_PER_MS = (PCM_RATE * 2) / 1000

function mintBody(model: string, hints: { language?: string; prompt?: string } = {}): string {
  return JSON.stringify({
    session: {
      type: "transcription",
      audio: {
        input: {
          format: { type: "audio/pcm", rate: PCM_RATE },
          transcription: { model, ...hints },
          // Offline file STT: we commit once after the full buffer is sent.
          turn_detection: null,
        },
      },
    },
  })
}

async function mintEphemeralKey(
  fetchFn: FetchFn,
  accessToken: string,
  model: string,
  hints: { language?: string; prompt?: string },
  signal?: AbortSignal,
): Promise<string> {
  const res = await fetchFn(MINT_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: mintBody(model, hints),
    signal,
  })
  if (!res.ok) {
    const detail = await res.text().catch(() => "")
    throw new Error(`codex-realtime: mint ${res.status}${detail ? ` ${detail.slice(0, 200)}` : ""}`)
  }
  const json = await res.json() as any
  const ek = json?.value ?? json?.client_secret?.value
  if (!ek || typeof ek !== "string") throw new Error("codex-realtime: mint missing ephemeral key")
  return ek
}

function extractTranscript(msg: any): string {
  if (typeof msg?.transcript === "string") return msg.transcript
  const content = msg?.item?.content
  if (Array.isArray(content)) {
    for (const c of content) {
      if (typeof c?.transcript === "string") return c.transcript
    }
  }
  return ""
}

/**
 * Open a transcription WebSocket, stream PCM, commit, collect completed
 * transcript(s). Resolves with joined text (may be empty for silence).
 */
function transcribeOverWs(
  connectWs: ConnectWsFn,
  ek: string,
  pcm: Buffer,
  timeoutMs: number,
  signal?: AbortSignal,
): Promise<string> {
  return new Promise((resolve, reject) => {
    let settled = false
    const parts: string[] = []
    let committed = false
    let sawCompleted = false

    const finish = (err?: Error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener?.("abort", onAbort)
      try { ws.close() } catch { /* ignore */ }
      if (err) reject(err)
      else resolve(parts.join(" ").trim())
    }

    const onAbort = () => finish(new Error("codex-realtime: aborted"))
    signal?.addEventListener?.("abort", onAbort)

    const timer = setTimeout(() => {
      finish(new Error(`codex-realtime: timeout after ${timeoutMs}ms`))
    }, timeoutMs)

    let ws: RealtimeWs
    try {
      ws = connectWs(WS_URL, ["realtime", `openai-insecure-api-key.${ek}`])
    } catch (e) {
      finish(e instanceof Error ? e : new Error(String(e)))
      return
    }

    ws.addEventListener("open", () => {
      try {
        for (let i = 0; i < pcm.length; i += PCM_CHUNK_BYTES) {
          const chunk = pcm.subarray(i, Math.min(i + PCM_CHUNK_BYTES, pcm.length))
          ws.send(JSON.stringify({
            type: "input_audio_buffer.append",
            audio: Buffer.from(chunk).toString("base64"),
          }))
        }
        ws.send(JSON.stringify({ type: "input_audio_buffer.commit" }))
        committed = true
      } catch (e) {
        finish(e instanceof Error ? e : new Error(String(e)))
      }
    })

    ws.addEventListener("message", (ev: any) => {
      let msg: any
      try {
        msg = JSON.parse(String(ev.data ?? ev))
      } catch {
        return
      }
      const type = String(msg?.type ?? "")
      if (type === "error" || type === "invalid_request_error") {
        const detail = msg?.error?.message ?? msg?.message ?? JSON.stringify(msg).slice(0, 200)
        finish(new Error(`codex-realtime: ${detail}`))
        return
      }
      if (type === "conversation.item.input_audio_transcription.completed") {
        sawCompleted = true
        const t = extractTranscript(msg)
        if (t) parts.push(t)
        // Single commit + no VAD → one utterance; done.
        finish()
        return
      }
      // Empty audio edge case: committed with nothing to transcribe.
      if (type === "input_audio_buffer.committed" && pcm.length === 0) {
        finish()
      }
    })

    ws.addEventListener("error", (ev: any) => {
      finish(new Error(`codex-realtime: websocket error ${ev?.message ?? ev?.error ?? ""}`.trim()))
    })

    ws.addEventListener("close", (ev: any) => {
      if (settled) return
      if (sawCompleted || (committed && parts.length)) {
        finish()
        return
      }
      const code = ev?.code ?? "?"
      const reason = ev?.reason ?? ""
      finish(new Error(`codex-realtime: websocket closed ${code}${reason ? ` ${reason}` : ""} before transcript`))
    })
  })
}

export function codexRealtimeEngine(opts: CodexRealtimeEngineOpts = {}): SttEngine {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const write: WriteFileFn = opts.writeFileFn ?? ((p, d) => writeFileSync(p, d, "utf8"))
  const authPath = opts.authPath ?? join(homedir(), ".codex", "auth.json")
  const connectWs: ConnectWsFn = opts.connectWs ?? ((url, protocols) => new WebSocket(url, protocols) as unknown as RealtimeWs)
  const modelDefault = opts.model ?? DEFAULT_MODEL
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const connections = opts.connections ?? sharedConnections

  const loadTokens = (): any | null => readJson(read, authPath)?.tokens ?? null

  /** Mint an ephemeral key with the Codex login, refreshing the OAuth token once on a 401. */
  const mintKey = async (model: string, hints: { language?: string; prompt?: string }, signal?: AbortSignal): Promise<string> => {
    const auth = readJson(read, authPath)
    const tokens = auth?.tokens
    if (!tokens?.access_token) throw new Error("codex-realtime: no access_token in auth.json")
    try {
      return await mintEphemeralKey(fetchFn, tokens.access_token, model, hints, signal)
    } catch (e) {
      const msg = String(e)
      if (!msg.includes("mint 401") || !tokens.refresh_token) throw e
      const refreshed = await refreshOAuth(fetchFn, tokens.refresh_token)
      const newAccess = refreshed.access_token
      const newRefresh = refreshed.refresh_token ?? tokens.refresh_token
      if (!newAccess) throw new Error("codex-realtime: refresh returned no access_token")
      const merged = {
        ...(auth ?? {}),
        tokens: {
          ...tokens,
          access_token: newAccess,
          refresh_token: newRefresh,
          id_token: refreshed.id_token ?? tokens.id_token,
        },
        last_refresh: new Date().toISOString(),
      }
      try {
        write(authPath, JSON.stringify(merged, null, 2))
      } catch {
        // best-effort
      }
      return mintEphemeralKey(fetchFn, newAccess, model, hints, signal)
    }
  }

  /** A connection a dictation keeps open across its passes. */
  const openConnection = async (config: TranscriptionConfig, signal?: AbortSignal): Promise<RealtimeConnection> => {
    const { model, ...hints } = config
    const ek = await mintKey(model, hints, signal)
    return RealtimeConnection.open(connectWs(WS_URL, ["realtime", `openai-insecure-api-key.${ek}`]), config, CONNECT_TIMEOUT_MS)
  }

  return {
    name: "codex-realtime",
    prefersCleanup: false,
    supportsOffset: true,

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      return Boolean(loadTokens()?.access_token)
    },

    async warm(stream: string, tOpts: SttTranscribeOpts = {}): Promise<void> {
      const model = resolveModel(tOpts.model, modelDefault)
      const config = { model, ...transcriptionHints(tOpts) }
      await connections.acquire(stream, () => openConnection(config, tOpts.signal))
    },

    endStream(stream: string): void {
      connections.release(stream)
    },

    async transcribe(audioPath: string, tOpts: SttTranscribeOpts = {}): Promise<SttResult> {
      const model = resolveModel(tOpts.model, modelDefault)
      const base = transcriptionHints(tOpts)
      const offset = (tOpts.fromMs ?? 0) > 0
      const prompt = offset
        ? continuationPrompt(base.prompt, tOpts.previousText)
        : tOpts.context ? continuationPrompt(base.prompt, tOpts.context) : base.prompt
      const hints = { ...(base.language ? { language: base.language } : {}), ...(prompt ? { prompt } : {}) }
      log.info("codex_realtime_transcribe", { audioPath, model, language: hints.language ?? "auto", prompt: hints.prompt ?? "", stream: tOpts.stream ?? null })

      const signal = tOpts.signal
      const whole = await readPcm16(audioPath, PCM_RATE)
      const durationMs = Math.floor(whole.length / PCM_BYTES_PER_MS)
      // An incremental pass sends only the audio after the cut; the cut is made in the PCM (exact,
      // whatever rate the recording was made at) rather than by seeking in the file.
      const fromMs = offset ? Math.min(tOpts.fromMs!, durationMs) : 0
      const pcm = fromMs > 0 ? whole.subarray(Math.floor(fromMs * PCM_BYTES_PER_MS) & ~1) : whole
      const result = (text: string): SttResult => ({ text, prefersCleanup: false, model, durationMs, fromMs })
      if (pcm.length === 0) {
        if (tOpts.endStream && tOpts.stream) connections.release(tOpts.stream)
        return result("")
      }

      // A dictation's passes share one open connection; any trouble with it falls back to a
      // fresh one-off connection, so reuse can only make a pass faster, never fail it.
      const stream = tOpts.stream
      if (stream) {
        const config = { model, ...hints }
        try {
          const conn = await connections.acquire(stream, () => openConnection(config, signal))
          return result(await conn.transcribe(pcm, config, REUSED_PASS_TIMEOUT_MS))
        } catch (e) {
          log.warn("codex_realtime_reuse_failed", { stream, err: String(e) })
          connections.release(stream)
        } finally {
          if (tOpts.endStream) connections.release(stream)
        }
      }

      const ek = await mintKey(model, hints, signal)
      return result(await transcribeOverWs(connectWs, ek, pcm, timeoutMs, signal))
    },
  }
}
