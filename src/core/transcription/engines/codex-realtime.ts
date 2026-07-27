// Codex / ChatGPT-subscription Realtime STT engine.
//
// Mints an ephemeral transcription session with the logged-in Codex OAuth token
// (~/.codex/auth.json), then streams 24 kHz mono PCM over the public Realtime
// WebSocket (`intent=transcription`). No platform `sk-` key required.
//
// Higher quality than local whisper-base → prefersCleanup is false (skip LLM
// rewrite). Whisper remains the universal fallback when this engine is
// unavailable or throws.

import { spawn as bunSpawn } from "bun"
import { readFileSync, writeFileSync } from "fs"
import { readFile, unlink } from "fs/promises"
import { homedir, tmpdir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import type { SpawnFn, SttEngine, SttResult, SttTranscribeOpts } from "../stt-types"

const log = makeLogger("stt-codex-realtime")

const OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
const OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
const MINT_URL = "https://api.openai.com/v1/realtime/client_secrets"
const WS_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
const DEFAULT_MODEL = "gpt-4o-transcribe"
const PCM_RATE = 24_000
/** ~100 ms of 24 kHz mono s16le. */
const PCM_CHUNK_BYTES = (PCM_RATE * 2) / 10
const DEFAULT_TIMEOUT_MS = 60_000
const FFMPEG_BIN = process.env.MUX_FFMPEG_BIN ?? "ffmpeg"

/** Known Realtime transcription model ids — ignore whisper path-like model opts. */
const KNOWN_MODELS = new Set([
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
  spawn?: SpawnFn
  connectWs?: ConnectWsFn
  /** Override availability probe (tests). */
  isAvailable?: () => boolean
  /** Default transcription model when opts.model is absent/unknown. */
  model?: string
  timeoutMs?: number
  ffmpegBin?: string
}

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

async function toPcm24k(
  audioPath: string,
  spawn: SpawnFn,
  ffmpegBin: string,
): Promise<{ pcmPath: string; cleanup: () => Promise<void> }> {
  const pcmPath = join(tmpdir(), `mux-stt-rt-${process.pid}-${Date.now()}.pcm`)
  const ff = spawn(ffmpegBin, [
    "-y",
    "-i", audioPath,
    "-ac", "1",
    "-ar", String(PCM_RATE),
    "-f", "s16le",
    pcmPath,
  ])
  if ((await ff.exited) !== 0) throw new Error("codex-realtime: ffmpeg failed")
  return {
    pcmPath,
    cleanup: async () => { await unlink(pcmPath).catch(() => {}) },
  }
}

function mintBody(model: string): string {
  return JSON.stringify({
    session: {
      type: "transcription",
      audio: {
        input: {
          format: { type: "audio/pcm", rate: PCM_RATE },
          transcription: { model },
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
  signal?: AbortSignal,
): Promise<string> {
  const res = await fetchFn(MINT_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: mintBody(model),
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
  const spawn: SpawnFn = opts.spawn ?? ((cmd, args) => bunSpawn([cmd, ...args], { stdout: "ignore", stderr: "ignore" }))
  const connectWs: ConnectWsFn = opts.connectWs ?? ((url, protocols) => new WebSocket(url, protocols) as unknown as RealtimeWs)
  const modelDefault = opts.model ?? DEFAULT_MODEL
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const ffmpegBin = opts.ffmpegBin ?? FFMPEG_BIN

  const loadTokens = (): any | null => readJson(read, authPath)?.tokens ?? null

  return {
    name: "codex-realtime",
    prefersCleanup: false,

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      return Boolean(loadTokens()?.access_token)
    },

    async transcribe(audioPath: string, tOpts: SttTranscribeOpts = {}): Promise<SttResult> {
      const model = resolveModel(tOpts.model, modelDefault)
      log.info("codex_realtime_transcribe", { audioPath, model })

      const auth = readJson(read, authPath)
      let tokens = auth?.tokens
      if (!tokens?.access_token) throw new Error("codex-realtime: no access_token in auth.json")

      const signal = tOpts.signal
      const { pcmPath, cleanup } = await toPcm24k(audioPath, spawn, ffmpegBin)
      try {
        const pcm = await readFile(pcmPath)

        let ek: string
        try {
          ek = await mintEphemeralKey(fetchFn, tokens.access_token, model, signal)
        } catch (e) {
          // On mint 401, refresh once and retry.
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
          tokens = merged.tokens
          ek = await mintEphemeralKey(fetchFn, newAccess, model, signal)
        }

        const text = await transcribeOverWs(connectWs, ek, pcm, timeoutMs, signal)
        return {
          text,
          prefersCleanup: false,
          model,
        }
      } finally {
        await cleanup()
      }
    },
  }
}
