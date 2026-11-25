// A <video> on macOS/iOS (WebKit) asks for byte ranges and plays only from a 206 with Content-Range and Content-Length;
// Chromium tolerates a plain 200. The preview proxy must carry a range request and its 206 through as they are.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { handleProxyRequest, parseByteRange, RANGE_PIECE_BYTES } from "./proxy"

const cleanups: (() => void)[] = []
afterEach(() => { while (cleanups.length) cleanups.pop()!() })

function rangeServer(bytes: Uint8Array) {
  const dir = mkdtempSync(join(tmpdir(), "mux-proxy-range-"))
  writeFileSync(join(dir, "clip.mp4"), bytes)
  const server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: () => new Response(Bun.file(join(dir, "clip.mp4"))) })
  cleanups.push(() => { server.stop(true); rmSync(dir, { recursive: true, force: true }) })
  return server
}

test("a range request goes through the proxy and its 206 comes back with Content-Range and Content-Length", async () => {
  const bytes = new Uint8Array(1000).map((_, i) => i % 251)
  const server = rangeServer(bytes)
  const res = await handleProxyRequest(
    new Request("http://broker.test/p/clips/clip.mp4", { headers: { range: "bytes=10-19" } }),
    { port: server.port, sessionName: "s" },
    { prefix: "/p/clips", upstreamPath: "/clip.mp4" },
  )
  expect(res.status).toBe(206)
  expect(res.headers.get("content-range")).toBe("bytes 10-19/1000")
  expect(res.headers.get("content-length")).toBe("10")
  expect(res.headers.get("accept-ranges")).toBe("bytes")
  expect(Array.from(new Uint8Array(await res.arrayBuffer()))).toEqual(Array.from(bytes.slice(10, 20)))
})

test("a whole file keeps its Content-Length through the proxy (a media player needs the size)", async () => {
  const server = rangeServer(new Uint8Array(1000))
  const res = await handleProxyRequest(
    new Request("http://broker.test/p/clips/clip.mp4"),
    { port: server.port, sessionName: "s" },
    { prefix: "/p/clips", upstreamPath: "/clip.mp4" },
  )
  expect(res.status).toBe(200)
  expect(res.headers.get("content-length")).toBe("1000")
  await res.arrayBuffer()
})

/** Python's http.server, as the growth board's video host: HTTP/1.0, no Range support, the whole file every time. */
function noRangeServer(bytes: Uint8Array) {
  const server = Bun.serve({
    port: 0, hostname: "127.0.0.1",
    fetch: () => new Response(bytes, { headers: { "content-type": "video/mp4", "content-length": String(bytes.length) } }),
  })
  cleanups.push(() => server.stop(true))
  return server
}

const proxied = (port: number, range?: string) => handleProxyRequest(
  new Request("http://broker.test/p/clips/clip.mp4", { headers: range ? { range } : {} }),
  { port, sessionName: "s" },
  { prefix: "/p/clips", upstreamPath: "/clip.mp4" },
)

test("an upstream that ignores Range still gives WebKit its 206: the proxy answers the range itself", async () => {
  const bytes = new Uint8Array(5 * 1024 * 1024).map((_, i) => (i * 7) % 253)
  const server = noRangeServer(bytes)

  const probe = await proxied(server.port, "bytes=0-1") // WebKit's first media request
  expect(probe.status).toBe(206)
  expect(probe.headers.get("content-range")).toBe(`bytes 0-1/${bytes.length}`)
  expect(probe.headers.get("content-length")).toBe("2")
  expect(probe.headers.get("accept-ranges")).toBe("bytes")
  expect(Array.from(new Uint8Array(await probe.arrayBuffer()))).toEqual([0, 7])

  const mid = await proxied(server.port, "bytes=3000000-3000009")
  expect(mid.status).toBe(206)
  expect(Array.from(new Uint8Array(await mid.arrayBuffer()))).toEqual(Array.from(bytes.slice(3000000, 3000010)))

  // Open-ended: a piece of at most RANGE_PIECE_BYTES, its length known; the player asks on from where it ends.
  const open = await proxied(server.port, "bytes=100-")
  expect(open.status).toBe(206)
  expect(open.headers.get("content-range")).toBe(`bytes 100-${100 + RANGE_PIECE_BYTES - 1}/${bytes.length}`)
  expect(open.headers.get("content-length")).toBe(String(RANGE_PIECE_BYTES))
  await open.arrayBuffer()

  const tail = await proxied(server.port, "bytes=-4")
  expect(tail.headers.get("content-range")).toBe(`bytes ${bytes.length - 4}-${bytes.length - 1}/${bytes.length}`)
  await tail.arrayBuffer()

  const past = await proxied(server.port, `bytes=${bytes.length}-`)
  expect(past.status).toBe(416)
  expect(past.headers.get("content-range")).toBe(`bytes */${bytes.length}`)
})

test("without a range the whole file goes through as before", async () => {
  const server = noRangeServer(new Uint8Array(1000))
  const res = await proxied(server.port)
  expect(res.status).toBe(200)
  expect((await res.arrayBuffer()).byteLength).toBe(1000)
})

test("byte ranges parse as HTTP has them", () => {
  expect(parseByteRange("bytes=0-1", 10)).toEqual([0, 1])
  expect(parseByteRange("bytes=5-", 10)).toEqual([5, 9])
  expect(parseByteRange("bytes=-3", 10)).toEqual([7, 9])
  expect(parseByteRange("bytes=8-100", 10)).toEqual([8, 9])
  expect(parseByteRange("bytes=10-", 10)).toBeNull()
  expect(parseByteRange("bytes=0-1,5-6", 10)).toBeNull()
  expect(parseByteRange("items=0-1", 10)).toBeNull()
})
