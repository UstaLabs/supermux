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
import type { TtsEngine, TtsResult, TtsSpeakOpts } from "../tts-types"

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

async function synthesizeOnce(
  fetchFn: FetchFn,
  accessToken: string,
  accountId: string | undefined,
  text: string,
  lang: string,
  speed: number,
  signal?: AbortSignal,
): Promise<{ bytes: Uint8Array; mime: string }> {
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
  const buf = new Uint8Array(await res.arrayBuffer())
  const mime = res.headers.get("content-type") || "audio/mpeg"
  return { bytes: buf, mime }
}

export function codexPronunciationEngine(opts: CodexPronunciationEngineOpts = {}): TtsEngine {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const write: WriteFileFn = opts.writeFileFn ?? ((p, d) => writeFileSync(p, d, "utf8"))
  const authPath = opts.authPath ?? join(homedir(), ".codex", "auth.json")
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS

  const loadAuth = (): any | null => readJson(read, authPath)
  const loadTokens = (): any | null => loadAuth()?.tokens ?? null

  return {
    name: "codex",

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      return Boolean(loadTokens()?.access_token)
    },

    async speak(text: string, tOpts: TtsSpeakOpts = {}): Promise<TtsResult> {
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
      const signal = tOpts.signal
        ? (() => {
            const onAbort = () => controller.abort()
            tOpts.signal!.addEventListener("abort", onAbort, { once: true })
            return controller.signal
          })()
        : controller.signal

      try {
        const trySynth = (access: string) =>
          synthesizeOnce(fetchFn, access, accountId, plain, lang, speed, signal)

        try {
          const { bytes, mime } = await trySynth(tokens.access_token)
          log.info("codex_tts_ok", { chars: plain.length, bytes: bytes.byteLength, mime })
          return { audio: bytes, mime, engine: "codex" }
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
          const { bytes, mime } = await trySynth(newAccess)
          log.info("codex_tts_ok_refreshed", { chars: plain.length, bytes: bytes.byteLength })
          return { audio: bytes, mime, engine: "codex" }
        }
      } finally {
        clearTimeout(timer)
      }
    },
  }
}
