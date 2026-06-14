import { test, expect, afterAll } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const PORT = 18920
const UPSTREAM_PORT = 18921
const DEV_PATH = `/tmp/devices-proxy-path-${process.pid}.json`

// Upstream echoes the path it received, over both HTTP and WS.
const upstream = Bun.serve<{ path: string }>({
  port: UPSTREAM_PORT,
  fetch(req, server) {
    const path = new URL(req.url).pathname
    if (req.headers.get("upgrade")?.toLowerCase() === "websocket") {
      return server.upgrade(req, { data: { path } }) ? undefined : new Response("no", { status: 400 })
    }
    return new Response(JSON.stringify({ path }), { headers: { "content-type": "application/json" } })
  },
  websocket: {
    open(ws) { ws.send(JSON.stringify({ path: ws.data.path })) },
    message() {},
  },
})

const store = new DeviceStore(DEV_PATH)
store.mint("test")

// No proxyBaseDomain → path mode active.
const ch = new WebChannel({
  port: PORT,
  devicesFile: DEV_PATH,
  publicUrl: `http://127.0.0.1:${PORT}`,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  proxyLookup: (slug) => {
    if (slug === "app") return { port: UPSTREAM_PORT, sessionName: "test", isPublic: false }
    if (slug === "pub") return { port: UPSTREAM_PORT, sessionName: "test", isPublic: true }
    return undefined
  },
  proxyAuth: (token) => token === "good",
})

afterAll(async () => {
  await ch.stop()
  upstream.stop(true)
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("forwards with the /p/<slug> prefix stripped", async () => {
  await ch.start()
  const res = await fetch(`http://127.0.0.1:${PORT}/p/pub/hello/world?q=1`)
  expect(res.status).toBe(200)
  expect((await res.json() as { path: string }).path).toBe("/hello/world")
})

test("bare /p/<slug> 301-redirects to /p/<slug>/", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/p/pub`, { redirect: "manual" })
  expect(res.status).toBe(301)
  expect(res.headers.get("location")).toBe("/p/pub/")
})

test("private proxy requires auth; valid cookie passes", async () => {
  const noAuth = await fetch(`http://127.0.0.1:${PORT}/p/app/`)
  expect(noAuth.status).toBe(401)

  const authed = await fetch(`http://127.0.0.1:${PORT}/p/app/x`, {
    headers: { Cookie: "cmux_token=good" },
  })
  expect(authed.status).toBe(200)
  expect((await authed.json() as { path: string }).path).toBe("/x")
})

test("bare /p/<slug> 301 preserves the query string", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/p/pub?x=1`, { redirect: "manual" })
  expect(res.status).toBe(301)
  expect(res.headers.get("location")).toBe("/p/pub/?x=1")
})

test("private proxy rejects a WebSocket upgrade without auth", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/p/app/ws`)  // private slug, no cookie
  const opened = await new Promise<boolean>((resolve) => {
    const t = setTimeout(() => resolve(false), 2000)
    ws.addEventListener("open", () => { clearTimeout(t); resolve(true) })
    ws.addEventListener("message", () => { clearTimeout(t); resolve(true) })
    ws.addEventListener("error", () => { clearTimeout(t); resolve(false) })
    ws.addEventListener("close", () => { clearTimeout(t); resolve(false) })
  })
  try { ws.close() } catch {}
  expect(opened).toBe(false)
})

test("WebSocket proxies with the prefix stripped", async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/p/pub/ws/hmr`)
  const first = await new Promise<string>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timed out")), 4000)
    ws.addEventListener("message", (e: any) => { clearTimeout(t); resolve(String(e.data)) })
    ws.addEventListener("error", () => { clearTimeout(t); reject(new Error("ws error")) })
  })
  expect(JSON.parse(first).path).toBe("/ws/hmr")
  ws.close()
})
