import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-client-logs-${process.pid}.json`
const PORT = 18788
let ch: WebChannel
let store: DeviceStore
let token: string

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
  })
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("POST /client-logs ingests and GET /debug/client-logs returns them", async () => {
  const origin = `http://127.0.0.1:${PORT}`
  const post = await fetch(`${origin}/client-logs`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      "content-type": "application/json",
      Origin: origin,
    },
    body: JSON.stringify({
      entries: [{ ts: 1, category: "lsp", event: "test.event", data: { ok: true } }],
      meta: { buildId: "abc" },
    }),
  })
  expect(post.status).toBe(200)

  const get = await fetch(`${origin}/debug/client-logs?category=lsp`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(get.status).toBe(200)
  const body = await get.json() as { entries: Array<{ event: string; category: string }> }
  expect(body.entries.some((e) => e.event === "test.event" && e.category === "lsp")).toBe(true)
})
