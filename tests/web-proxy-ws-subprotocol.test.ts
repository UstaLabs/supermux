import { test, expect, afterAll } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

// Regression: the proxy must forward the client's WebSocket subprotocol to the
// upstream dev server. Vite's HMR server only speaks on sockets carrying the
// `vite-hmr` subprotocol; if the proxy drops it, HMR silently dies even though
// the socket "opens". See proxy WS handling in src/channels/web/index.ts.

const BASE_DOMAIN = "example.test"
const SUB = "app"
const PORT = 18900
const UPSTREAM_PORT = 18901
const DEV_PATH = `/tmp/devices-proxy-ws-${process.pid}.json`

// Fake upstream that mimics Vite: it reports back which subprotocol it received.
const upstream = Bun.serve<{ proto: string | null }>({
  port: UPSTREAM_PORT,
  fetch(req, server) {
    const proto = req.headers.get("sec-websocket-protocol")
    const ok = server.upgrade(req, { data: { proto } })
    return ok ? undefined : new Response("no upgrade", { status: 400 })
  },
  websocket: {
    open(ws) {
      ws.send(JSON.stringify({ type: "upstream-hello", received: ws.data.proto }))
    },
    message() {},
  },
})

const store = new DeviceStore(DEV_PATH)
store.mint("test")
const ch = new WebChannel({
  port: PORT,
  devicesFile: DEV_PATH,
  publicUrl: `http://${BASE_DOMAIN}:${PORT}`,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  proxyBaseDomain: BASE_DOMAIN,
  proxyLookup: (domain) => (domain === SUB ? { port: UPSTREAM_PORT, sessionName: "test", isPublic: false } : undefined),
  proxyAuth: () => true,
})

afterAll(async () => {
  await ch.stop()
  upstream.stop(true)
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("proxy forwards the client WebSocket subprotocol to the upstream", async () => {
  await ch.start()

  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/`, {
    // @ts-ignore — Bun extension: custom headers on the WS client
    headers: { Host: `${SUB}.${BASE_DOMAIN}`, Cookie: "cmux_token=any" },
    protocols: ["vite-hmr"],
  } as any)

  const firstMsg = await new Promise<string>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for upstream message")), 4000)
    ws.addEventListener("message", (e: any) => {
      clearTimeout(timer)
      resolve(typeof e.data === "string" ? e.data : "<binary>")
    })
    ws.addEventListener("error", () => {
      clearTimeout(timer)
      reject(new Error("client socket errored"))
    })
  })

  const parsed = JSON.parse(firstMsg)
  // The upstream must have seen the vite-hmr subprotocol we forwarded.
  expect(parsed.type).toBe("upstream-hello")
  expect(parsed.received).toBe("vite-hmr")
  // And the broker must echo the selected subprotocol back to the client.
  expect(ws.protocol).toBe("vite-hmr")

  ws.close()
})
