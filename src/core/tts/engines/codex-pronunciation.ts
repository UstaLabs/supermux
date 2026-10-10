// ChatGPT subscription TTS via the Codex Desktop pronunciation path.
//
//   POST https://chatgpt.com/backend-api/pronunciation/synthesize?format=mp3
//   Auth: ChatGPT OAuth from ~/.codex/auth.json (same as codex-realtime STT)
//   Body: { text, pronunciation_language, speed }
//
// Hard limit ~1200 chars (text_too_long). Caller should chunk longer text.
// Live probe 2026-07-29: ChatGPT OAuth works; platform sk- path is quota-billed.

import { readFileSync, writeFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import type { TtsByteStream, TtsEngine, TtsResult, TtsSpeakOpts } from "../tts-types"

const log = makeLogger("tts-codex")

const OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
const OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
const SYNTH_URL = "https://chatgpt.com/backend-api/pronunciation/synthesize?format=mp3"
const DEFAULT_TIMEOUT_MS = 60_000

export type FetchFn = (input: string | URL | Request, init?: RequestInit) => Promise<Response>
export type ReadFileFn = (path: string) => string
export type WriteFileFn = (path: string, data: string) => void

export interface CodexPronunciationEngineOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  writeFileFn?: WriteFileFn
  authPath?: string
  isAvailable?: () => boolean
  timeoutMs?: number
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
  if (!res.ok) throw new Error(`codex-tts: oauth refresh ${res.status}`)
  return res.json()
}

/** POST the text; resolves once the response headers are in, with the MP3 body still streaming. */
async function synthesizeOnce(
  fetchFn: FetchFn,
  accessToken: string,
  accountId: string | undefined,
  text: string,
  lang: string,
  speed: number,
  signal?: AbortSignal,
): Promise<Response> {
  const res = await fetchFn(SYNTH_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
      Accept: "*/*",
      "User-Agent": "ChatGPTDesktop/1.0",
      ...(accountId ? { "ChatGPT-Account-ID": accountId } : {}),
    },
    body: JSON.stringify({
      text,
      pronunciation_language: lang,
      speed,
    }),
    signal,
  })
  if (!res.ok) {
    const detail = await res.text().catch(() => "")
    throw new Error(`codex-tts: synthesize ${res.status}${detail ? ` ${detail.slice(0, 240)}` : ""}`)
  }
  return res
}

async function* bodyChunks(res: Response): AsyncGenerator<Uint8Array> {
  if (!res.body) {
    yield new Uint8Array(await res.arrayBuffer())
    return
  }
  const reader = res.body.getReader()
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) return
      if (value?.byteLength) yield value
    }
  } finally {
    reader.releaseLock()
  }
}

export function codexPronunciationEngine(opts: CodexPronunciationEngineOpts = {}): TtsEngine {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const write: WriteFileFn = opts.writeFileFn ?? ((p, d) => writeFileSync(p, d, "utf8"))
  const authPath = opts.authPath ?? join(homedir(), ".codex", "auth.json")
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS

  const loadAuth = (): any | null => readJson(read, authPath)
  const loadTokens = (): any | null => loadAuth()?.tokens ?? null

  /**
   * Start one synthesis: auth, the 401 → refresh → retry, and a timeout that keeps running until
   * the caller has read the body and calls `clear` (the body is where the slow part streams).
   */
  async function open(
    text: string,
    tOpts: TtsSpeakOpts,
  ): Promise<{ res: Response; clear: () => void; chars: number }> {
    const plain = text.trim()
    if (!plain) throw new Error("codex-tts: empty text")

    const auth = loadAuth()
    let tokens = auth?.tokens
    if (!tokens?.access_token) throw new Error("codex-tts: no access_token in auth.json")
    const accountId: string | undefined =
      typeof tokens.account_id === "string" ? tokens.account_id : undefined
    const lang = (tOpts.lang && tOpts.lang.trim()) || "en"
    const speed = typeof tOpts.speed === "number" && tOpts.speed > 0 ? tOpts.speed : 1

    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    const clear = () => clearTimeout(timer)
    if (tOpts.signal) {
      if (tOpts.signal.aborted) controller.abort()
      else tOpts.signal.addEventListener("abort", () => controller.abort(), { once: true })
    }
    const signal = controller.signal

    try {
      const trySynth = (access: string) =>
        synthesizeOnce(fetchFn, access, accountId, plain, lang, speed, signal)

      try {
        return { res: await trySynth(tokens.access_token), clear, chars: plain.length }
      } catch (e) {
        const msg = String(e)
        if (!msg.includes("synthesize 401") || !tokens.refresh_token) throw e
        const refreshed = await refreshOAuth(fetchFn, tokens.refresh_token)
        const newAccess = refreshed.access_token
        const newRefresh = refreshed.refresh_token ?? tokens.refresh_token
        if (!newAccess) throw new Error("codex-tts: refresh returned no access_token")
        const merged = {
          ...auth,
          tokens: {
            ...tokens,
            access_token: newAccess,
            refresh_token: newRefresh,
            id_token: refreshed.id_token ?? tokens.id_token,
          },
        }
        write(authPath, JSON.stringify(merged, null, 2))
        tokens = merged.tokens
        log.info("codex_tts_refreshed", { chars: plain.length })
        return { res: await trySynth(newAccess), clear, chars: plain.length }
      }
    } catch (e) {
      clear()
      throw e
    }
  }

  return {
    name: "codex",

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      return Boolean(loadTokens()?.access_token)
    },

    async speak(text: string, tOpts: TtsSpeakOpts = {}): Promise<TtsResult> {
      const { res, clear, chars } = await open(text, tOpts)
      try {
        const bytes = new Uint8Array(await res.arrayBuffer())
        const mime = res.headers.get("content-type") || "audio/mpeg"
        log.info("codex_tts_ok", { chars, bytes: bytes.byteLength, mime })
        return { audio: bytes, mime, engine: "codex" }
      } finally {
        clear()
      }
    },

    async speakStream(text: string, tOpts: TtsSpeakOpts = {}): Promise<TtsByteStream> {
      const { res, clear, chars } = await open(text, tOpts)
      const t0 = Date.now()
      async function* body(): AsyncGenerator<Uint8Array> {
        let n = 0
        try {
          for await (const c of bodyChunks(res)) {
            n += c.byteLength
            yield c
          }
          log.info("codex_tts_stream_ok", { chars, bytes: n, ms: Date.now() - t0 })
        } finally {
          clear()
        }
      }
      return { mime: res.headers.get("content-type") || "audio/mpeg", body: body() }
    },
  }
}
