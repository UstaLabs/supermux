import { test, expect, afterEach } from "bun:test"
import { useUploader } from "./useUploader"

const realFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = realFetch
})

type Captured = { url: string; init: RequestInit }
function mockFetch(response: unknown, status = 200): { calls: Captured[] } {
  const calls: Captured[] = []
  globalThis.fetch = (async (url: any, init?: any) => {
    calls.push({ url: String(url), init: init ?? {} })
    return new Response(JSON.stringify(response), {
      status,
      headers: { "content-type": "application/json" },
    })
  }) as any
  return { calls }
}

test("upload streams the raw body with octet-stream + X-Mux-* headers", async () => {
  const { calls } = mockFetch({ file_id: "f1", size: 3, mime: "video/mp4", name: "clip.mp4" })
  const file = new File([new Uint8Array([1, 2, 3])], "clip.mp4", { type: "video/mp4" })
  const { upload } = useUploader()

  const result = await upload("sess-1", file)

  expect(calls).toHaveLength(1)
  const { url, init } = calls[0]
  expect(url).toBe("/upload")
  expect(init.method).toBe("POST")
  const headers = new Headers(init.headers as HeadersInit)
  expect(headers.get("Content-Type")).toBe("application/octet-stream")
  expect(headers.get("X-Mux-Session")).toBe("sess-1")
  expect(headers.get("X-Mux-Mime")).toBe("video/mp4")
  expect(headers.get("X-Mux-Filename")).toBe("clip.mp4")
  // No kind hint → header omitted (server infers "video" from the mime).
  expect(headers.has("X-Mux-Kind")).toBe(false)
  // Raw file body, NOT multipart FormData.
  expect(init.body).toBe(file)
  expect(init.body instanceof FormData).toBe(false)
  // Returned JSON is passed through unchanged.
  expect(result).toEqual({ file_id: "f1", size: 3, mime: "video/mp4", name: "clip.mp4" })
})

test("upload percent-encodes the filename and forwards a kind hint", async () => {
  const { calls } = mockFetch({ file_id: "f2", size: 1, mime: "audio/webm", name: "my note.webm" })
  const file = new File([new Uint8Array([0])], "my note.webm", { type: "audio/webm" })
  const { upload } = useUploader()

  await upload("sess-2", file, "voice")

  const headers = new Headers(calls[0].init.headers as HeadersInit)
  expect(headers.get("X-Mux-Filename")).toBe("my%20note.webm")
  expect(headers.get("X-Mux-Kind")).toBe("voice")
})

test("upload throws on a non-ok response (e.g. 413 too large)", async () => {
  mockFetch("file too large", 413)
  const file = new File([new Uint8Array([9])], "big.mp4", { type: "video/mp4" })
  const { upload } = useUploader()

  await expect(upload("sess-3", file)).rejects.toThrow(/413/)
})
