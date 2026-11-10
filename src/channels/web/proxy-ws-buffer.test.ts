// The proxy's WebSocket bridge dials the upstream when the client's socket opens, and a client that speaks first (a
// dev-server HMR hello, a "subscribe" frame) sends before that dial has finished. Those frames used to be dropped
// (upstream.send threw while CONNECTING and the catch swallowed it). They are buffered, bounded, and flushed on open.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, PROXY_WS_PENDING_MAX_BYTES, PROXY_WS_PENDING_MAX_MESSAGES } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
let upstream: ReturnType<typeof Bun.serve> | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  if (upstream) { upstream.stop(true); upstream = undefined }
})

/** An upstream whose WebSocket handshake takes a moment, so the client always speaks before it is open. */
async function boot(handshakeMs = 150) {
  const received: string[] = []
  upstream = Bun.serve<{}>({
    port: 0,
    async fetch(req, server) {
      await Bun.sleep(handshakeMs)
      return server.upgrade(req, { data: {} }) ? undefined : new Response("no", { status: 400 })
    },
    websocket: {
      message(_ws, m) { received.push(typeof m === "string" ? m : `bin:${[...m].join(",")}`) },
    },
  })
  const port = upstream.port!
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-proxy-ws-")), "devices.json")
  const devices = new DeviceStore(devicesFile)
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    proxyLookup: (slug) => (slug === "px" ? { port, sessionName: "s", isPublic: false } : undefined),
    proxyAuth: (token) => !!devices.verify(token),
  })
  await channel.start()
  const headers = { authorization: `Bearer ${devices.mint("phone").token}` }
  /** Opens the proxied socket; `sendOnOpen` runs the instant the client side is open. */
  const connect = (sendOnOpen: (ws: WebSocket) => void) => {
    const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/p/px/sock`, { headers } as any)
    const closed = new Promise<number>((resolve) => { ws.onclose = (e) => resolve(e.code) })
    ws.binaryType = "arraybuffer"
    ws.onopen = () => sendOnOpen(ws)
    return { ws, closed }
  }
  const until = async (ok: () => boolean, what: string) => {
    const deadline = Date.now() + 4000
    while (!ok()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}; upstream has ${received.length} messages`)
      await Bun.sleep(10)
    }
  }
  return { received, connect, until }
}

test("a frame the client sends the instant its socket opens reaches the upstream, text and binary, in order", async () => {
  const { received, connect, until } = await boot()
  const { ws } = connect((s) => { s.send("first"); s.send(new Uint8Array([1, 2, 3])); s.send("third") })
  await until(() => received.length === 3, "the three early frames")
  expect(received).toEqual(["first", "bin:1,2,3", "third"])
  // And the bridge still works normally once the upstream is open.
  ws.send("later")
  await until(() => received.length === 4, "a frame sent after the upstream opened")
  expect(received[3]).toBe("later")
  ws.close()
})

test("up to the message bound is buffered, and one past it closes the client with 1009", async () => {
  const fine = await boot()
  const a = fine.connect((s) => { for (let i = 0; i < PROXY_WS_PENDING_MAX_MESSAGES; i++) s.send(`m${i}`) })
  await fine.until(() => fine.received.length === PROXY_WS_PENDING_MAX_MESSAGES, "every message up to the bound")
  expect(fine.received[0]).toBe("m0")
  expect(fine.received[PROXY_WS_PENDING_MAX_MESSAGES - 1]).toBe(`m${PROXY_WS_PENDING_MAX_MESSAGES - 1}`)
  a.ws.close()
  await channel!.stop(); channel = undefined; upstream!.stop(true); upstream = undefined

  const over = await boot()
  const b = over.connect((s) => { for (let i = 0; i <= PROXY_WS_PENDING_MAX_MESSAGES; i++) s.send(`m${i}`) })
  expect(await b.closed).toBe(1009)
  await Bun.sleep(300) // past the upstream handshake: nothing buffered may be delivered after the refusal
  expect(over.received).toEqual([])
})

test("past the byte bound the client is closed with 1009 too", async () => {
  const { received, connect } = await boot()
  const half = new Uint8Array(PROXY_WS_PENDING_MAX_BYTES / 2 + 1)
  const { closed } = connect((s) => { s.send(half); s.send(half) })
  expect(await closed).toBe(1009)
  await Bun.sleep(300)
  expect(received).toEqual([])
})
