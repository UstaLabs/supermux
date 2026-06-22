// Cursor adapter — direct one-shot text completion against Cursor's backend
// (api2.cursor.sh), reusing the logged-in subscription token from
// ~/.config/cursor/auth.json (`accessToken`).
//
// Cursor speaks Connect-RPC (the buf Connect protocol) with PROTOBUF payloads
// over HTTPS — there is no JSON/OpenAI shape here. We talk to
//   /aiserver.v1.AiService/StreamChat  (server-streaming)
// with content-type `application/connect+proto`.
//
// Wire format (verified against everestmz/cursor-rpc's `aiserver.v1` .proto and
// ephraimduncan/opencode-cursor's Connect framing):
//   - Each Connect frame = [1 flag byte][4-byte big-endian length][payload].
//     Request: ONE frame, flag 0x00, payload = the GetChatRequest protobuf.
//     Response: zero+ data frames (flag 0x00, payload = StreamChatResponse
//     protobuf) terminated by ONE end-stream frame (flag bit 0x02, JSON body —
//     `{}` on success or `{"error":{...}}` on failure).
//   - GetChatRequest: conversation (field 2, repeated ConversationMessage) +
//     model_details (field 7, ModelDetails). A ConversationMessage carries
//     text (field 1) + type (field 2; HUMAN=1). ModelDetails.model_name = field 1.
//   - StreamChatResponse.text = field 1 — concatenate across data frames.
//
// We HAND-ROLL a tiny protobuf encoder/decoder for just these few fields rather
// than pulling in protobufjs/@bufbuild — the repo has no protobuf dependency and
// the message subset is small and stable (field numbers are protobuf-compatible).
//
// All I/O is injectable (fetchFn / readFileFn) so the adapter is fully
// unit-testable with no network or disk. On any failure (non-2xx, 401, error
// frame, empty) we THROW so the orchestrator fails soft → falls back to cursor-cli.
//
// LIVE STATUS (2026-06-21): api2.cursor.sh now returns HTTP 200 with an
// end-stream error `ERROR_DEPRECATED` ("This endpoint is deprecated. Please
// upgrade to the latest version of Cursor.") for ANY x-cursor-client-version we
// send — the StreamChat/GetChatRequest protocol has been retired server-side.
// The live replacement is `agent.v1.AgentService/Run`, a much heavier bidi
// protocol (mid-stream blob-store getBlob/setBlob KV handshakes + serialized
// conversation-state checkpoints, requiring real HTTP/2 bidi rather than a
// single fetch). Implementing that is out of scope here. Until it lands, the
// registry keeps the "cursor" engine pointed at cursor-cli; this adapter is
// retained as best-effort and will work again if Cursor un-deprecates StreamChat
// (it already speaks the exact wire format) or as the basis for the Run port.

import { homedir } from "os"
import { join } from "path"
import { makeLogger } from "../../../shared/log"
import { defaultRead, readJson } from "../auth"
import { DEFAULT_TIMEOUT_MS, type AgentApi, type CompleteOpts, type FetchFn, type ReadFileFn } from "../types"

const log = makeLogger("agent-api:cursor")

const HOST = "https://api2.cursor.sh"
const STREAM_CHAT_PATH = "/aiserver.v1.AiService/StreamChat"
// A plausibly-recent Cursor CLI version string; the backend may gate stale
// clients. Override via env if the protocol drifts.
const CLIENT_VERSION = process.env.MUX_CURSOR_CLIENT_VERSION ?? "cli-2026.01.09-231024f"
// A fast default model. Cursor exposes model ids via AvailableModels; this is a
// safe one-shot default and is overridable via opts.model / env.
const DEFAULT_MODEL = process.env.MUX_CURSOR_MODEL ?? "composer-2.5-fast"

const CONNECT_END_STREAM_FLAG = 0x02
const CONNECT_COMPRESSED_FLAG = 0x01

// ----------------------------- protobuf encode -----------------------------

// Encode an unsigned int as a base-128 varint.
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

// A length-delimited (wire type 2) field: tag, length, bytes.
function encodeLenField(field: number, bytes: number[]): number[] {
  return [(field << 3) | 2, ...encodeVarint(bytes.length), ...bytes]
}

function encodeStringField(field: number, s: string): number[] {
  return encodeLenField(field, Array.from(new TextEncoder().encode(s)))
}

// A varint (wire type 0) field.
function encodeVarintField(field: number, value: number): number[] {
  return [(field << 3) | 0, ...encodeVarint(value)]
}

// Build the GetChatRequest protobuf for a one-shot human prompt + model.
function encodeGetChatRequest(prompt: string, model: string): Uint8Array {
  // ConversationMessage { text=1, type=2 (HUMAN=1) }
  const convoMsg = [...encodeStringField(1, prompt), ...encodeVarintField(2, 1)]
  // ModelDetails { model_name=1 }
  const modelDetails = encodeStringField(1, model)
  // GetChatRequest { conversation=2 (repeated), model_details=7 }
  const req = [...encodeLenField(2, convoMsg), ...encodeLenField(7, modelDetails)]
  return new Uint8Array(req)
}

// ----------------------------- protobuf decode -----------------------------

// Read a varint starting at `pos`; returns [value, nextPos].
function decodeVarint(buf: Uint8Array, pos: number): [number, number] {
  let shift = 0
  let result = 0
  let p = pos
  for (;;) {
    if (p >= buf.length) throw new Error("varint overrun")
    const b = buf[p++]!
    result |= (b & 0x7f) << shift
    if ((b & 0x80) === 0) break
    shift += 7
    if (shift > 35) throw new Error("varint too long")
  }
  return [result >>> 0, p]
}

// Extract the first occurrence of a length-delimited string field by number.
// Returns "" if absent (good enough for StreamChatResponse.text per-frame).
function decodeStringField(buf: Uint8Array, field: number): string {
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
      if (f === field) return new TextDecoder().decode(buf.subarray(p, p + len))
      p += len
    } else if (wire === 5) {
      p += 4
    } else if (wire === 1) {
      p += 8
    } else {
      throw new Error(`unsupported wire type ${wire}`)
    }
  }
  return ""
}

// ----------------------------- Connect framing -----------------------------

// Frame one Connect message: [flag][4-byte BE length][payload].
function frameMessage(payload: Uint8Array, flag = 0): Uint8Array {
  const out = new Uint8Array(5 + payload.length)
  out[0] = flag
  new DataView(out.buffer).setUint32(1, payload.length, false)
  out.set(payload, 5)
  return out
}

interface ConnectFrame {
  flag: number
  payload: Uint8Array
}

// Split a complete Connect stream body into its frames.
function deframe(body: Uint8Array): ConnectFrame[] {
  const frames: ConnectFrame[] = []
  let p = 0
  const view = new DataView(body.buffer, body.byteOffset, body.length)
  while (p + 5 <= body.length) {
    const flag = body[p]!
    const len = view.getUint32(p + 1, false)
    const start = p + 5
    const end = start + len
    if (end > body.length) break // truncated trailing frame
    frames.push({ flag, payload: body.subarray(start, end) })
    p = end
  }
  return frames
}

// Generate a cursor checksum. The backend appears to only require *a* value
// (everestmz/cursor-rpc notes the exact contents don't matter). We mirror its
// algorithm: rolling-XOR of a 6-byte ms timestamp, base64, then a machine id.
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

// Flatten a Connect end-stream error JSON into the most useful human message.
// Cursor nests the real reason at error.details[].debug.details.{title,detail}
// (e.g. ERROR_DEPRECATED → "This endpoint is deprecated…").
function connectErrorMessage(err: any): string {
  const code = err?.code ? String(err.code) : ""
  for (const d of err?.details ?? []) {
    const dbg = d?.debug
    if (dbg?.error) {
      const detail = dbg?.details?.detail || dbg?.details?.title
      return detail ? `${dbg.error}: ${detail}` : String(dbg.error)
    }
  }
  return err?.message || code || "stream error"
}

// ----------------------------------- adapter -----------------------------------

export interface CursorAdapterOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  authPath?: string
}

export function cursorAdapter(opts: CursorAdapterOpts = {}): AgentApi {
  const fetchFn = opts.fetchFn ?? fetch
  const read = opts.readFileFn ?? defaultRead
  const authPath = opts.authPath ?? join(homedir(), ".config", "cursor", "auth.json")

  const loadToken = (): string | undefined => {
    const tok = readJson(read, authPath)?.accessToken
    return typeof tok === "string" && tok ? tok : undefined
  }

  return {
    name: "cursor",

    // accessToken present in ~/.config/cursor/auth.json — never a network call.
    isAvailable(): boolean {
      return Boolean(loadToken())
    },

    async complete(prompt: string, complOpts: CompleteOpts = {}): Promise<string> {
      const token = loadToken()
      if (!token) throw new Error("cursor: no accessToken in auth.json")

      const model = complOpts.model ?? DEFAULT_MODEL
      const body = frameMessage(encodeGetChatRequest(prompt, model))

      const timeoutMs = complOpts.timeoutMs ?? DEFAULT_TIMEOUT_MS
      const timer = complOpts.signal ? undefined : AbortSignal.timeout(timeoutMs)
      const signal = complOpts.signal ?? timer

      const res = await fetchFn(`${HOST}${STREAM_CHAT_PATH}`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${token}`,
          "content-type": "application/connect+proto",
          "connect-protocol-version": "1",
          "x-cursor-client-type": "cli",
          "x-ghost-mode": "true",
          "x-cursor-checksum": generateChecksum(),
          "x-cursor-client-version": CLIENT_VERSION,
        },
        body,
        signal,
      })

      // On any non-2xx (incl. 401): fail soft so the orchestrator falls back.
      // The refresh endpoint is unverified/varies; we do not guess it here.
      if (!res.ok) {
        const detail = await res.text().catch(() => "")
        throw new Error(`cursor: StreamChat ${res.status}${detail ? ` ${detail.slice(0, 200)}` : ""}`)
      }

      const raw = new Uint8Array(await res.arrayBuffer())
      const frames = deframe(raw)

      const parts: string[] = []
      for (const f of frames) {
        if (f.flag & CONNECT_COMPRESSED_FLAG) {
          throw new Error("cursor: compressed frames are not supported")
        }
        if (f.flag & CONNECT_END_STREAM_FLAG) {
          // End-stream: JSON body — `{}` on success, `{error:{...}}` on failure.
          let end: any = null
          try {
            end = JSON.parse(new TextDecoder().decode(f.payload) || "{}")
          } catch {
            end = null
          }
          if (end?.error) {
            throw new Error(`cursor: ${connectErrorMessage(end.error)}`)
          }
          continue
        }
        // Data frame: StreamChatResponse — append field 1 (text).
        const text = decodeStringField(f.payload, 1)
        if (text) parts.push(text)
      }

      const out = parts.join("").trim()
      if (!out) {
        log.warn("cursor_empty_completion", { frames: frames.length })
        throw new Error("cursor: empty completion")
      }
      return out
    },
  }
}
