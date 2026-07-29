import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { cursorSttEngine, mimeForAudioPath } from "./cursor-stt"

const auth = JSON.stringify({ accessToken: "cursor-at-test", refreshToken: "rt" })

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

function encodeStringField(field: number, s: string): Uint8Array {
  const bytes = Array.from(new TextEncoder().encode(s))
  return new Uint8Array([(field << 3) | 2, ...encodeVarint(bytes.length), ...bytes])
}

/** Build a minimal TranscribeAudioResponse with text=field1. */
function encodeResponse(text: string): Uint8Array {
  return encodeStringField(1, text)
}

test("isAvailable is false without accessToken", () => {
  const e = cursorSttEngine({
    readFileFn: () => { throw new Error("missing") },
  })
  expect(e.isAvailable()).toBe(false)
  expect(e.name).toBe("cursor-stt")
  expect(e.prefersCleanup).toBe(false)
})

test("isAvailable is true with accessToken", () => {
  const e = cursorSttEngine({
    readFileFn: () => auth,
  })
  expect(e.isAvailable()).toBe(true)
})

test("mimeForAudioPath maps common client formats", () => {
  expect(mimeForAudioPath("/a/b.webm")).toBe("audio/webm")
  expect(mimeForAudioPath("/a/b.wav")).toBe("audio/wav")
  expect(mimeForAudioPath("/a/b.m4a")).toBe("audio/mp4")
  expect(mimeForAudioPath("/a/b.bin")).toBe("audio/mp4")
  expect(mimeForAudioPath("/a/b.oga")).toBe("audio/ogg")
})

test("transcribe posts raw application/proto and returns text", async () => {
  const audioPath = join(tmpdir(), `mux-cursor-stt-${process.pid}.webm`)
  writeFileSync(audioPath, Buffer.from("fake-webm-bytes"))

  let sawUrl = ""
  let sawHeaders: Record<string, string> = {}
  let sawBody: Uint8Array | null = null

  const e = cursorSttEngine({
    authPath: "/fake/auth.json",
    readFileFn: (p) => {
      if (p === "/fake/auth.json") return auth
      throw new Error(`unexpected read ${p}`)
    },
    fetchFn: async (url, init) => {
      sawUrl = String(url)
      sawHeaders = init?.headers as Record<string, string>
      const body = init?.body
      if (body instanceof Uint8Array) sawBody = body
      else if (body instanceof ArrayBuffer) sawBody = new Uint8Array(body)
      else if (typeof body === "string") sawBody = new TextEncoder().encode(body)
      return new Response(encodeResponse("hello from cursor stt"), {
        status: 200,
        headers: { "content-type": "application/proto" },
      })
    },
  })

  const r = await e.transcribe(audioPath, { lang: "en-US" })
  expect(r.text).toBe("hello from cursor stt")
  expect(r.prefersCleanup).toBe(false)
  expect(r.model).toBe("cursor-transcribe")
  expect(sawUrl).toContain("/aiserver.v1.AiService/TranscribeAudio")
  expect(sawHeaders["content-type"]).toBe("application/proto")
  expect(String(sawHeaders.authorization ?? sawHeaders.Authorization ?? "")).toContain("cursor-at-test")
  expect(sawBody).not.toBeNull()
  // Request must include mime_type field 2 with audio/webm.
  const bodyStr = Buffer.from(sawBody!).toString("utf8")
  expect(bodyStr).toContain("audio/webm")
  expect(bodyStr).toContain("fake-webm-bytes")
})

test("transcribe throws on HTTP error with Connect error body", async () => {
  const audioPath = join(tmpdir(), `mux-cursor-stt-err-${process.pid}.wav`)
  writeFileSync(audioPath, Buffer.alloc(8))

  const e = cursorSttEngine({
    authPath: "/fake/auth.json",
    readFileFn: () => auth,
    fetchFn: async () =>
      new Response(JSON.stringify({
        code: "unauthenticated",
        message: "Error",
        details: [{ debug: { error: "ERROR_NOT_LOGGED_IN", details: { detail: "try logging out" } } }],
      }), { status: 401 }),
  })

  await expect(e.transcribe(audioPath)).rejects.toThrow(/ERROR_NOT_LOGGED_IN|try logging out|401/)
})

test("transcribe throws when no token", async () => {
  const e = cursorSttEngine({
    readFileFn: () => JSON.stringify({}),
  })
  await expect(e.transcribe("/tmp/x.wav")).rejects.toThrow(/no accessToken/)
})
