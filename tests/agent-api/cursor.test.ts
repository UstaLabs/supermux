import { expect, test } from "bun:test"
import { cursorAdapter } from "../../src/core/agent-api/adapters/cursor"

// ---------------------------------------------------------------------------
// Self-contained protobuf + Connect-framing helpers for the TEST side, so the
// assertions don't lean on the adapter's own encoder. These mirror the wire
// format the adapter must speak:
//   - protobuf: tag = (field << 3) | wireType; LEN fields = tag, varint len, bytes
//   - Connect stream frame: [1 flag byte][4-byte big-endian length][payload]
// ---------------------------------------------------------------------------

function varint(n: number): number[] {
  const out: number[] = []
  let v = n >>> 0
  while (v > 0x7f) {
    out.push((v & 0x7f) | 0x80)
    v >>>= 7
  }
  out.push(v)
  return out
}

function lenField(field: number, bytes: Uint8Array | number[]): number[] {
  const tag = (field << 3) | 2
  const arr = Array.from(bytes)
  return [tag, ...varint(arr.length), ...arr]
}

function strField(field: number, s: string): number[] {
  return lenField(field, Array.from(new TextEncoder().encode(s)))
}

function varintField(field: number, value: number): number[] {
  const tag = (field << 3) | 0
  return [tag, ...varint(value)]
}

// Build a StreamChatResponse protobuf carrying just `text` (field 1).
function streamChatResponse(text: string): Uint8Array {
  return new Uint8Array(strField(1, text))
}

// Frame one Connect message: flag + 4-byte BE length + payload.
function frame(payload: Uint8Array, flag = 0): Uint8Array {
  const out = new Uint8Array(5 + payload.length)
  out[0] = flag
  new DataView(out.buffer).setUint32(1, payload.length, false)
  out.set(payload, 5)
  return out
}

// Concatenate several frames into one response body.
function concatFrames(...frames: Uint8Array[]): Uint8Array {
  const total = frames.reduce((n, f) => n + f.length, 0)
  const out = new Uint8Array(total)
  let off = 0
  for (const f of frames) {
    out.set(f, off)
    off += f.length
  }
  return out
}

// ---- A minimal protobuf reader for asserting the request body's fields. ----
type Field = { field: number; wire: number; value: number | Uint8Array }

function readVarint(buf: Uint8Array, pos: number): [number, number] {
  let shift = 0
  let result = 0
  let p = pos
  for (;;) {
    const b = buf[p++]!
    result |= (b & 0x7f) << shift
    if ((b & 0x80) === 0) break
    shift += 7
  }
  return [result >>> 0, p]
}

function readMessage(buf: Uint8Array): Field[] {
  const fields: Field[] = []
  let p = 0
  while (p < buf.length) {
    let tag: number
    ;[tag, p] = readVarint(buf, p)
    const field = tag >>> 3
    const wire = tag & 0x7
    if (wire === 0) {
      let v: number
      ;[v, p] = readVarint(buf, p)
      fields.push({ field, wire, value: v })
    } else if (wire === 2) {
      let len: number
      ;[len, p] = readVarint(buf, p)
      fields.push({ field, wire, value: buf.subarray(p, p + len) })
      p += len
    } else {
      throw new Error(`unsupported wire type ${wire}`)
    }
  }
  return fields
}

// Unwrap a single Connect frame → its payload bytes.
function unframe(body: Uint8Array): { flag: number; payload: Uint8Array } {
  const flag = body[0]!
  const len = new DataView(body.buffer, body.byteOffset, body.length).getUint32(1, false)
  return { flag, payload: body.subarray(5, 5 + len) }
}

const dec = new TextDecoder()
function fieldStr(fields: Field[], n: number): string | undefined {
  const f = fields.find((x) => x.field === n && x.wire === 2)
  return f ? dec.decode(f.value as Uint8Array) : undefined
}
function fieldMsg(fields: Field[], n: number): Field[] | undefined {
  const f = fields.find((x) => x.field === n && x.wire === 2)
  return f ? readMessage(f.value as Uint8Array) : undefined
}

// auth.json shape: { accessToken, refreshToken }
const authFile = (over: Record<string, any> = {}): string =>
  JSON.stringify({ accessToken: "tok_access", refreshToken: "tok_refresh", ...over })

// A two-chunk streamed response (data frames) + an end-stream frame (flag 0x02, JSON body).
const okStream = (...chunks: string[]): Response => {
  const dataFrames = chunks.map((c) => frame(streamChatResponse(c)))
  const end = frame(new TextEncoder().encode("{}"), 0x02)
  const body = concatFrames(...dataFrames, end)
  return new Response(body, { status: 200, headers: { "Content-Type": "application/connect+proto" } })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test("name is cursor", () => {
  const a = cursorAdapter({ readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(a.name).toBe("cursor")
})

test("isAvailable() is true when auth.json has accessToken", () => {
  const a = cursorAdapter({ readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(a.isAvailable()).toBe(true)
})

test("isAvailable() is false when the file is missing", () => {
  const a = cursorAdapter({
    readFileFn: () => {
      throw new Error("ENOENT")
    },
    authPath: "/fake/auth.json",
  })
  expect(a.isAvailable()).toBe(false)
})

test("isAvailable() is false when accessToken is absent", () => {
  const a = cursorAdapter({ readFileFn: () => authFile({ accessToken: undefined }), authPath: "/fake/auth.json" })
  expect(a.isAvailable()).toBe(false)
})

test("complete() POSTs StreamChat with auth + x-cursor headers and a protobuf body; decodes streamed frames", async () => {
  let seen: { url?: string; init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (url: any, init: any) => {
    seen = { url, init }
    return okStream("hello ", "world")
  }) as unknown as typeof fetch

  const a = cursorAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  const out = await a.complete("Correct: helo wrld", { model: "gpt-4" })

  // Streamed frames concatenate to text.
  expect(out).toBe("hello world")

  // Endpoint + method.
  expect(seen.url).toBe("https://api2.cursor.sh/aiserver.v1.AiService/StreamChat")
  expect(seen.init?.method).toBe("POST")

  // Headers: auth + cursor-specific + connect content-type.
  const headers = seen.init?.headers as Record<string, string>
  expect(headers["authorization"]).toBe("Bearer tok_access")
  expect(headers["x-cursor-client-type"]).toBe("cli")
  expect(headers["x-ghost-mode"]).toBe("true")
  expect(typeof headers["x-cursor-checksum"]).toBe("string")
  expect(headers["x-cursor-checksum"]!.length).toBeGreaterThan(0)
  expect(typeof headers["x-cursor-client-version"]).toBe("string")
  expect(headers["x-cursor-client-version"]!.length).toBeGreaterThan(0)
  expect(headers["content-type"]).toBe("application/connect+proto")

  // Request body is a Connect frame (flag 0x00) wrapping a GetChatRequest protobuf.
  const body = new Uint8Array(seen.init?.body as ArrayBuffer)
  const { flag, payload } = unframe(body)
  expect(flag).toBe(0)
  const req = readMessage(payload)

  // conversation (field 2, repeated message): first message has text=1, type=2 (HUMAN=1).
  const convo = fieldMsg(req, 2)
  expect(convo).toBeDefined()
  expect(fieldStr(convo!, 1)).toBe("Correct: helo wrld")
  const typeField = convo!.find((x) => x.field === 2 && x.wire === 0)
  expect(typeField?.value).toBe(1)

  // model_details (field 7) → model_name (field 1).
  const model = fieldMsg(req, 7)
  expect(model).toBeDefined()
  expect(fieldStr(model!, 1)).toBe("gpt-4")
})

test("complete() concatenates many text chunks in order", async () => {
  const fetchFn: typeof fetch = (async () =>
    okStream("The ", "quick ", "brown ", "fox")) as unknown as typeof fetch
  const a = cursorAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(await a.complete("x")).toBe("The quick brown fox")
})

test("complete() throws on a non-2xx HTTP status (fail-soft → orchestrator falls back)", async () => {
  const fetchFn: typeof fetch = (async () =>
    new Response("unauthorized", { status: 401 })) as unknown as typeof fetch
  const a = cursorAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws on an end-stream error frame", async () => {
  const errBody = JSON.stringify({ error: { code: "permission_denied", message: "nope" } })
  const body = concatFrames(frame(new TextEncoder().encode(errBody), 0x02))
  const fetchFn: typeof fetch = (async () =>
    new Response(body, { status: 200, headers: { "Content-Type": "application/connect+proto" } })) as unknown as typeof fetch
  const a = cursorAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws on empty streamed text", async () => {
  const fetchFn: typeof fetch = (async () => okStream("", "  ")) as unknown as typeof fetch
  const a = cursorAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws when accessToken is missing", async () => {
  const fetchFn: typeof fetch = (async () => okStream("ok")) as unknown as typeof fetch
  const a = cursorAdapter({
    fetchFn,
    readFileFn: () => authFile({ accessToken: undefined }),
    authPath: "/fake/auth.json",
  })
  await expect(a.complete("x")).rejects.toThrow()
})
