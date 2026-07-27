// Claude Code voice_stream STT engine.
//
// Replays the private WebSocket Claude Code `/voice` uses against
// Anthropic's speech_to_text service (Deepgram Nova 3 behind the scenes),
// authenticated with the logged-in Claude.ai OAuth token from
// ~/.claude/.credentials.json (`claudeAiOauth.accessToken`).
//
// Same product shape as codex-realtime: subscription STT for users who already
// run Claude Code. Protocol is reconstructed from Claude Code v2.1.x; it is a
// private CLI endpoint (not a public product API) — treat as best-effort parity
// and fall back to whisper on failure.
//
// Audio: 16 kHz mono linear16 PCM streamed as binary WS frames.
// Control: {"type":"KeepAlive"} every 8s, {"type":"CloseStream"} to finalize.
// Server: TranscriptInterim/TranscriptText (partial), TranscriptEndpoint (final),
//         TranscriptError / error.
// prefersCleanup is false (cloud STT quality + coding keyterms).

import { spawn as bunSpawn } from "bun"
import { readFileSync, writeFileSync } from "fs"
import { readFile, unlink } from "fs/promises"
import { homedir, tmpdir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import type { SpawnFn, SttEngine, SttResult, SttTranscribeOpts } from "../stt-types"

const log = makeLogger("stt-claude-voice")

const DEFAULT_BASE = "https://api.anthropic.com"
const WS_PATH = "/api/ws/speech_to_text/voice_stream"
const OAUTH_TOKEN_URL = "https://api.anthropic.com/v1/oauth/token"
/** Claude Code OAuth client id (device-flow / CLI). */
const OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
const PCM_RATE = 16_000
/** ~100 ms of 16 kHz mono s16le. */
const PCM_CHUNK_BYTES = (PCM_RATE * 2) / 10
const KEEPALIVE_MS = 8_000
const DEFAULT_TIMEOUT_MS = 60_000
const FFMPEG_BIN = process.env.MUX_FFMPEG_BIN ?? "ffmpeg"
const DEFAULT_MODEL = "deepgram-nova3"
const DEFAULT_CREDS = () => join(homedir(), ".claude", ".credentials.json")

export type FetchFn = (input: string | URL | Request, init?: RequestInit) => Promise<Response>
export type ReadFileFn = (path: string) => string
export type WriteFileFn = (path: string, data: string) => void

/** Minimal WebSocket surface (Bun/browser + `ws` package). */
export interface VoiceWs {
  readonly readyState: number
  send(data: string | ArrayBuffer | Uint8Array | Buffer): void
  close(code?: number, reason?: string): void
  addEventListener(type: "open" | "message" | "error" | "close", listener: (ev: any) => void): void
  removeEventListener?(type: string, listener: (ev: any) => void): void
}

export interface ConnectWsOpts {
  headers: Record<string, string>
}

export type ConnectWsFn = (url: string, opts: ConnectWsOpts) => VoiceWs
/** Injected sleep for paced PCM streaming (tests use a no-op). */
export type SleepFn = (ms: number) => Promise<void>

export interface ClaudeVoiceEngineOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  writeFileFn?: WriteFileFn
  credsPath?: string
  spawn?: SpawnFn
  connectWs?: ConnectWsFn
  sleep?: SleepFn
  /**
   * Fraction of real-time for offline file streaming (1 = real-time).
   * The Anthropic voice_stream rejects a full-buffer dump; ~0.85–1.0 is reliable.
   */
  paceFactor?: number
  /** Override availability probe (tests). */
  isAvailable?: () => boolean
  /** Base API origin (https://…); WS is derived. Env VOICE_STREAM_BASE_URL wins. */
  baseUrl?: string
  timeoutMs?: number
  ffmpegBin?: string
  /** Default language when opts.lang is absent/auto. */
  language?: string
  /** Default keyterms (glossary) when not passed per call. */
  keyterms?: string[]
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

function loadOauth(read: ReadFileFn, credsPath: string): any | null {
  const raw = readJson(read, credsPath)
  const oauth = raw?.claudeAiOauth
  if (!oauth || typeof oauth !== "object") return null
  return oauth
}

function resolveWsBase(override?: string): string {
  // Claude Code: VOICE_STREAM_BASE_URL or BASE_API_URL with https→wss.
  const env = process.env.VOICE_STREAM_BASE_URL?.trim()
  const base = (env || override || DEFAULT_BASE).replace(/\/$/, "")
  if (base.startsWith("wss://") || base.startsWith("ws://")) return base
  if (base.startsWith("https://")) return "wss://" + base.slice("https://".length)
  if (base.startsWith("http://")) return "ws://" + base.slice("http://".length)
  return "wss://" + base
}

function resolveLanguage(lang?: string, fallback = "en"): string {
  if (!lang || lang === "auto") return fallback
  // BCP-47 → primary subtag lowercased (en-US → en).
  return lang.split(/[-_]/)[0]!.toLowerCase() || fallback
}

/** Claude Code sanitizes keyterms for a header; keep it conservative. */
export function sanitizeKeytermsForHeader(terms: string[]): string | undefined {
  const cleaned = terms
    .map((t) => t.trim())
    .filter(Boolean)
    .map((t) => t.replace(/[\r\n\t]+/g, " ").slice(0, 64))
    .filter(Boolean)
  if (!cleaned.length) return undefined
  // Cap total header size.
  let out = ""
  for (const t of cleaned) {
    const next = out ? `${out},${t}` : t
    if (next.length > 512) break
    out = next
  }
  return out || undefined
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
  if (!res.ok) throw new Error(`claude-voice: oauth refresh ${res.status}`)
  return res.json()
}

async function toPcm16k(
  audioPath: string,
  spawn: SpawnFn,
  ffmpegBin: string,
): Promise<{ pcmPath: string; cleanup: () => Promise<void> }> {
  const pcmPath = join(tmpdir(), `mux-stt-cv-${process.pid}-${Date.now()}.pcm`)
  const ff = spawn(ffmpegBin, [
    "-y",
    "-i", audioPath,
    "-ac", "1",
    "-ar", String(PCM_RATE),
    "-f", "s16le",
    pcmPath,
  ])
  if ((await ff.exited) !== 0) throw new Error("claude-voice: ffmpeg failed")
  return {
    pcmPath,
    cleanup: async () => { await unlink(pcmPath).catch(() => {}) },
  }
}

function buildUrl(wsBase: string, language: string): string {
  const params = new URLSearchParams({
    encoding: "linear16",
    sample_rate: String(PCM_RATE),
    channels: "1",
    endpointing_ms: "300",
    utterance_end_ms: "1000",
    language,
    use_conversation_engine: "true",
    forward_interims: "typed",
    stt_provider: DEFAULT_MODEL,
  })
  return `${wsBase}${WS_PATH}?${params.toString()}`
}

function defaultConnectWs(url: string, opts: ConnectWsOpts): VoiceWs {
  // Bun accepts headers on the options object; browser WS ignores them.
  return new WebSocket(url, { headers: opts.headers } as any) as unknown as VoiceWs
}

/**
 * Stream PCM over the Claude voice_stream WS and collect finals.
 * Offline file path: append all audio, CloseStream once, wait for endpoint.
 */
function transcribeOverWs(
  connectWs: ConnectWsFn,
  url: string,
  headers: Record<string, string>,
  pcm: Buffer,
  timeoutMs: number,
  sleep: SleepFn,
  paceFactor: number,
  signal?: AbortSignal,
): Promise<string> {
  return new Promise((resolve, reject) => {
    let settled = false
    const finals: string[] = []
    let pending = ""
    let closedStream = false
    let keepAlive: ReturnType<typeof setInterval> | null = null

    const finish = (err?: Error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (keepAlive) clearInterval(keepAlive)
      signal?.removeEventListener?.("abort", onAbort)
      try { ws.close() } catch { /* ignore */ }
      if (err) reject(err)
      else {
        // Promote last interim if server never sent TranscriptEndpoint.
        if (pending.trim()) finals.push(pending.trim())
        resolve(finals.join(" ").replace(/\s+/g, " ").trim())
      }
    }

    const onAbort = () => finish(new Error("claude-voice: aborted"))
    signal?.addEventListener?.("abort", onAbort)

    const timer = setTimeout(() => {
      finish(new Error(`claude-voice: timeout after ${timeoutMs}ms`))
    }, timeoutMs)

    let ws: VoiceWs
    try {
      ws = connectWs(url, { headers })
    } catch (e) {
      finish(e instanceof Error ? e : new Error(String(e)))
      return
    }

    const sendKeepAlive = () => {
      try {
        if (!settled) ws.send(JSON.stringify({ type: "KeepAlive" }))
      } catch { /* ignore */ }
    }

    // voice_stream is a live STT socket (Deepgram behind Anthropic). Dumping the
    // whole file then CloseStream yields empty transcripts; pace near real-time.
    const pumpAudio = async () => {
      try {
        for (let i = 0; i < pcm.length; i += PCM_CHUNK_BYTES) {
          if (settled) return
          const chunk = pcm.subarray(i, Math.min(i + PCM_CHUNK_BYTES, pcm.length))
          ws.send(chunk)
          // Duration of this chunk in ms at PCM_RATE mono s16le.
          const chunkMs = (chunk.length / 2 / PCM_RATE) * 1000
          const wait = Math.max(0, chunkMs * paceFactor)
          if (wait > 0) await sleep(wait)
        }
        closedStream = true
        ws.send(JSON.stringify({ type: "CloseStream" }))
      } catch (e) {
        finish(e instanceof Error ? e : new Error(String(e)))
      }
    }

    ws.addEventListener("open", () => {
      sendKeepAlive()
      keepAlive = setInterval(sendKeepAlive, KEEPALIVE_MS)
      void pumpAudio()
    })

    ws.addEventListener("message", (ev: any) => {
      let raw = ev?.data ?? ev
      if (typeof raw !== "string") {
        // Binary server frames are not part of the known protocol; ignore.
        if (raw instanceof ArrayBuffer) raw = Buffer.from(raw).toString("utf8")
        else if (ArrayBuffer.isView(raw)) raw = Buffer.from(raw.buffer, raw.byteOffset, raw.byteLength).toString("utf8")
        else return
      }
      let msg: any
      try {
        msg = JSON.parse(String(raw))
      } catch {
        return
      }
      const type = String(msg?.type ?? "")
      if (type === "TranscriptInterim" || type === "TranscriptText") {
        const data = typeof msg.data === "string" ? msg.data : ""
        if (data) pending = data
        return
      }
      if (type === "TranscriptEndpoint") {
        if (pending.trim()) {
          finals.push(pending.trim())
          pending = ""
        }
        // Single offline utterance after CloseStream → done.
        if (closedStream) finish()
        return
      }
      if (type === "TranscriptError") {
        const detail = typeof msg.data === "string" ? msg.data : JSON.stringify(msg).slice(0, 200)
        finish(new Error(`claude-voice: TranscriptError ${detail}`))
        return
      }
      if (type === "error") {
        const detail = msg.message ?? JSON.stringify(msg).slice(0, 200)
        finish(new Error(`claude-voice: ${detail}`))
      }
    })

    ws.addEventListener("error", (ev: any) => {
      finish(new Error(`claude-voice: websocket error ${ev?.message ?? ev?.error ?? ""}`.trim()))
    })

    ws.addEventListener("close", (ev: any) => {
      if (settled) return
      // Promote pending on clean close after we closed the stream.
      if (closedStream) {
        finish()
        return
      }
      const code = ev?.code ?? "?"
      const reason = ev?.reason ?? ""
      finish(new Error(`claude-voice: websocket closed ${code}${reason ? ` ${reason}` : ""} before transcript`))
    })
  })
}

export function claudeVoiceEngine(opts: ClaudeVoiceEngineOpts = {}): SttEngine {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const write: WriteFileFn = opts.writeFileFn ?? ((p, d) => writeFileSync(p, d, "utf8"))
  const credsPath = opts.credsPath ?? DEFAULT_CREDS()
  const spawn: SpawnFn = opts.spawn ?? ((cmd, args) => bunSpawn([cmd, ...args], { stdout: "ignore", stderr: "ignore" }))
  const connectWs = opts.connectWs ?? defaultConnectWs
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const ffmpegBin = opts.ffmpegBin ?? FFMPEG_BIN
  const languageDefault = opts.language ?? "en"
  const keytermsDefault = opts.keyterms ?? []

  return {
    name: "claude-voice",
    prefersCleanup: false,

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      const oauth = loadOauth(read, credsPath)
      return Boolean(oauth?.accessToken && typeof oauth.accessToken === "string")
    },

    async transcribe(audioPath: string, tOpts: SttTranscribeOpts = {}): Promise<SttResult> {
      log.info("claude_voice_transcribe", { audioPath, model: DEFAULT_MODEL })

      let oauth = loadOauth(read, credsPath)
      if (!oauth?.accessToken) throw new Error("claude-voice: no accessToken in .credentials.json")

      const language = resolveLanguage(tOpts.lang, languageDefault)
      const keyterms = tOpts.keyterms ?? keytermsDefault
      const wsBase = resolveWsBase(opts.baseUrl)
      const url = buildUrl(wsBase, language)

      const { pcmPath, cleanup } = await toPcm16k(audioPath, spawn, ffmpegBin)
      try {
        const pcm = await readFile(pcmPath)

        const buildHeaders = (accessToken: string): Record<string, string> => {
          const headers: Record<string, string> = {
            Authorization: `Bearer ${accessToken}`,
            "User-Agent": `claude-cli/2.1.220 (external, supermux)`,
            "x-app": "cli",
            "anthropic-client-platform": process.platform,
          }
          const kt = sanitizeKeytermsForHeader(keyterms)
          if (kt) headers["x-config-keyterms"] = kt
          return headers
        }

        const sleep: SleepFn = opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)))
        // 0.9× realtime is reliable in live probes (~full quality, slightly faster than wall clock).
        const paceFactor = opts.paceFactor ?? 0.9
        const runOnce = (accessToken: string) =>
          transcribeOverWs(
            connectWs, url, buildHeaders(accessToken), pcm, timeoutMs, sleep, paceFactor, tOpts.signal,
          )

        let text: string
        try {
          text = await runOnce(oauth.accessToken)
        } catch (e) {
          // On auth failure, refresh once and retry (mirror codex-realtime).
          const msg = String(e)
          const looksAuth = /401|403|unauthorized|auth|closed 10[0-3]/i.test(msg)
          if (!looksAuth || !oauth.refreshToken) throw e
          log.info("claude_voice_refresh_retry", {})
          const refreshed = await refreshOAuth(fetchFn, oauth.refreshToken)
          const newAccess = refreshed.access_token ?? refreshed.accessToken
          const newRefresh = refreshed.refresh_token ?? refreshed.refreshToken ?? oauth.refreshToken
          if (!newAccess) throw new Error("claude-voice: refresh returned no access_token")
          const expiresIn = Number(refreshed.expires_in ?? refreshed.expiresIn ?? 0)
          const merged = {
            claudeAiOauth: {
              ...oauth,
              accessToken: newAccess,
              refreshToken: newRefresh,
              expiresAt: expiresIn ? Date.now() + expiresIn * 1000 : oauth.expiresAt,
            },
          }
          try {
            // Preserve any sibling keys in the credentials file.
            const prev = readJson(read, credsPath) ?? {}
            write(credsPath, JSON.stringify({ ...prev, ...merged }, null, 2) + "\n")
          } catch {
            // best-effort
          }
          oauth = merged.claudeAiOauth
          text = await runOnce(newAccess)
        }

        return {
          text,
          prefersCleanup: false,
          model: DEFAULT_MODEL,
        }
      } finally {
        await cleanup()
      }
    },
  }
}
