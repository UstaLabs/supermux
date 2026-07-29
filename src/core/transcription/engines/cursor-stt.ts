// Cursor subscription STT engine.
//
// Calls the unary Connect-RPC method used by Cursor IDE Voice Mode:
//   POST https://api2.cursor.sh/aiserver.v1.AiService/TranscribeAudio
// with Content-Type `application/proto` (raw protobuf body — not framed
// application/connect+proto; that returns 415 for this unary method).
//
// Auth: logged-in Cursor token from ~/.config/cursor/auth.json (`accessToken`),
// same store as the voice-cleanup cursor adapter.
//
// Protobuf (from cursor-agent / IDE bundles, aiserver.v1):
//   TranscribeAudioRequest  { audio=1 bytes, mime_type=2 string, language=3 string opt }
//   TranscribeAudioResponse { text=1 string, transcription_time_ms=2 int64 }
//
// prefersCleanup is false (cloud STT). Whisper remains the universal fallback
// when the token is missing/expired or the RPC fails.
//
// Live probe 2026-07-29: correct content-type reaches the auth gate; expired
// session tokens return ERROR_NOT_LOGGED_IN (re-login via cursor-agent login).

import { readFile } from "fs/promises"
import { homedir } from "os"
import { extname } from "path"
import { makeLogger } from "../../../shared/log"
import { authCredPath } from "../../agents/detect"
import type { SttEngine, SttResult, SttTranscribeOpts } from "../stt-types"

const log = makeLogger("stt-cursor")

const HOST = "https://api2.cursor.sh"
const TRANSCRIBE_PATH = "/aiserver.v1.AiService/TranscribeAudio"
const CLIENT_VERSION = process.env.MUX_CURSOR_CLIENT_VERSION ?? "cli-2026.06.24-00-45-58-9f61de7"
const DEFAULT_TIMEOUT_MS = 60_000

export type FetchFn = (input: string | URL | Request, init?: RequestInit) => Promise<Response>
export type ReadFileFn = (path: string) => string

export interface CursorSttEngineOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  authPath?: string
  /** Override availability probe (tests). */
  isAvailable?: () => boolean
  timeoutMs?: number
  clientVersion?: string
}

// ----------------------------- protobuf helpers -----------------------------

function encodeVarint(n: number): number[] {
  const out: number[] = []
  let v = n >>> 0
  while (v > 0x7f) {
    out.push((v & 0x7f) | 0x80)
    v >>>= 7
  }
  out.push(v)
  return out
}

function encodeLenField(field: number, bytes: Uint8Array | number[]): number[] {
  const arr = bytes instanceof Uint8Array ? Array.from(bytes) : bytes
  return [(field << 3) | 2, ...encodeVarint(arr.length), ...arr]
}

function encodeStringField(field: number, s: string): number[] {
  return encodeLenField(field, Array.from(new TextEncoder().encode(s)))
}

function encodeTranscribeRequest(audio: Uint8Array, mimeType: string, language?: string): Uint8Array {
  const parts = [
    ...encodeLenField(1, audio),
    ...encodeStringField(2, mimeType),
  ]
  if (language) parts.push(...encodeStringField(3, language))
  return new Uint8Array(parts)
}

function decodeVarint(buf: Uint8Array, pos: number): [number, number] {
  let shift = 0
  let result = 0
  let p = pos
  for (;;) {
    if (p >= buf.length) throw new Error("cursor-stt: varint overrun")
    const b = buf[p++]!
    result |= (b & 0x7f) << shift
    if ((b & 0x80) === 0) break
    shift += 7
    if (shift > 35) throw new Error("cursor-stt: varint too long")
  }
  return [result >>> 0, p]
}

/** Extract field 1 (text) from TranscribeAudioResponse. */
function decodeTextField(buf: Uint8Array): string {
  let p = 0
  while (p < buf.length) {
    let tag: number
    ;[tag, p] = decodeVarint(buf, p)
    const f = tag >>> 3
    const wire = tag & 0x7
    if (wire === 0) {
      ;[, p] = decodeVarint(buf, p)
    } else if (wire === 2) {
      let len: number
      ;[len, p] = decodeVarint(buf, p)
      if (f === 1) return new TextDecoder().decode(buf.subarray(p, p + len))
      p += len
    } else if (wire === 5) {
      p += 4
    } else if (wire === 1) {
      p += 8
    } else {
      throw new Error(`cursor-stt: unsupported wire type ${wire}`)
    }
  }
  return ""
}

// ----------------------------- mime + auth -----------------------------

/** Map a local recording path to a MIME type Cursor accepts. */
export function mimeForAudioPath(audioPath: string): string {
  const ext = extname(audioPath).toLowerCase()
  switch (ext) {
    case ".wav": return "audio/wav"
    case ".webm": return "audio/webm"
    case ".ogg": case ".oga": return "audio/ogg"
    case ".mp3": return "audio/mpeg"
    case ".m4a": case ".mp4": return "audio/mp4"
    case ".aac": return "audio/aac"
    case ".flac": return "audio/flac"
    case ".bin": return "audio/mp4" // Android often stages m4a as .bin
    default: return "application/octet-stream"
  }
}

function defaultRead(path: string): string {
  // Sync read only for small auth.json; audio uses async readFile.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require("fs").readFileSync(path, "utf8")
}

function readJson(read: ReadFileFn, path: string): any | null {
  try {
    return JSON.parse(read(path))
  } catch {
    return null
  }
}

function resolveLanguage(lang?: string): string | undefined {
  if (!lang || lang === "auto") return undefined
  return lang.split(/[-_]/)[0]!.toLowerCase() || undefined
}

function connectErrorMessage(body: string): string {
  try {
    const j = JSON.parse(body)
    const code = j?.code ? String(j.code) : ""
    for (const d of j?.details ?? []) {
      const dbg = d?.debug
      if (dbg?.error) {
        const detail = dbg?.details?.detail || dbg?.details?.title
        return detail ? `${dbg.error}: ${detail}` : String(dbg.error)
      }
    }
    return j?.message || code || body.slice(0, 200)
  } catch {
    return body.slice(0, 200) || "request failed"
  }
}

// Checksum: same algorithm as agent-api/adapters/cursor.ts (backend requires *a* value).
function generateChecksum(): string {
  const ts = Date.now()
  const bytes = [
    (ts / 2 ** 40) & 0xff,
    (ts / 2 ** 32) & 0xff,
    (ts >>> 24) & 0xff,
    (ts >>> 16) & 0xff,
    (ts >>> 8) & 0xff,
    ts & 0xff,
  ]
  let w = 165
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = ((bytes[i]! ^ w) + (i % 256)) & 0xff
    w = bytes[i]!
  }
  return Buffer.from(bytes).toString("base64") + "supermux"
}

// ----------------------------- engine -----------------------------

export function cursorSttEngine(opts: CursorSttEngineOpts = {}): SttEngine {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const authPath = opts.authPath ?? authCredPath("cursor", {
    home: homedir(),
    xdgConfigHome: process.env.XDG_CONFIG_HOME,
    appData: process.env.APPDATA,
    platform: process.platform,
  })
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const clientVersion = opts.clientVersion ?? CLIENT_VERSION

  const loadToken = (): string | undefined => {
    const tok = readJson(read, authPath)?.accessToken
    return typeof tok === "string" && tok ? tok : undefined
  }

  return {
    name: "cursor-stt",
    prefersCleanup: false,

    isAvailable() {
      if (opts.isAvailable) return opts.isAvailable()
      return Boolean(loadToken())
    },

    async transcribe(audioPath: string, tOpts: SttTranscribeOpts = {}): Promise<SttResult> {
      const token = loadToken()
      if (!token) throw new Error("cursor-stt: no accessToken in auth.json")

      const audio = await readFile(audioPath)
      const mimeType = mimeForAudioPath(audioPath)
      const language = resolveLanguage(tOpts.lang)
      log.info("cursor_stt_transcribe", { audioPath, mimeType, language: language ?? "auto", bytes: audio.length })

      const body = encodeTranscribeRequest(new Uint8Array(audio), mimeType, language)
      const signal = tOpts.signal ?? AbortSignal.timeout(timeoutMs)

      const res = await fetchFn(`${HOST}${TRANSCRIBE_PATH}`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${token}`,
          "content-type": "application/proto",
          "connect-protocol-version": "1",
          "x-cursor-client-type": "cli",
          "x-ghost-mode": "true",
          "x-cursor-checksum": generateChecksum(),
          "x-cursor-client-version": clientVersion,
        },
        body,
        signal,
      })

      if (!res.ok) {
        const detail = await res.text().catch(() => "")
        throw new Error(`cursor-stt: TranscribeAudio ${res.status} ${connectErrorMessage(detail)}`.trim())
      }

      const raw = new Uint8Array(await res.arrayBuffer())
      // Some error responses still return 200 with JSON (Connect unary).
      if (raw.length && raw[0] === 0x7b /* '{' */) {
        const msg = connectErrorMessage(new TextDecoder().decode(raw))
        throw new Error(`cursor-stt: ${msg}`)
      }

      const text = decodeTextField(raw).trim()
      return {
        text,
        prefersCleanup: false,
        model: "cursor-transcribe",
      }
    },
  }
}
