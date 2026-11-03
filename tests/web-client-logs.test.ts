import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-client-logs-${process.pid}.json`
// Port 0: the OS picks a free one, read back once the channel listens (a fixed port collides
// with whatever else holds it, a leftover listener included).
let PORT = 0
let ch: WebChannel
let store: DeviceStore
let token: string

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token
  ch = new WebChannel({
    port: 0,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1",
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
  })
  await ch.start()
  PORT = ch.boundPort
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("POST /client-logs ingests and GET /debug/client-logs returns them", async () => {
  const base = `http://127.0.0.1:${PORT}`
  const post = await fetch(`${base}/client-logs`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      "content-type": "application/json",
      // The app's own origin: the configured public URL.
      Origin: "http://127.0.0.1",
    },
    body: JSON.stringify({
      entries: [{ ts: 1, category: "lsp", event: "test.event", data: { ok: true } }],
      meta: { buildId: "abc" },
    }),
  })
  expect(post.status).toBe(200)

  const get = await fetch(`${base}/debug/client-logs?category=lsp`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(get.status).toBe(200)
  const body = await get.json() as { entries: Array<{ event: string; category: string }> }
  expect(body.entries.some((e) => e.event === "test.event" && e.category === "lsp")).toBe(true)
})
