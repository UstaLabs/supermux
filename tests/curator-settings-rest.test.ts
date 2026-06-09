import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import type { CuratorConfig } from "../src/core/settings/curator-config"
import { parseCuratorConfig } from "../src/core/settings/curator-config"

const DEV_PATH = `/tmp/devices-curator-${process.pid}.json`
const PORT = 18796
let ch: WebChannel
let token: string
let cfg: CuratorConfig
let runs = 0

const base = (extra: object) => ({
  port: PORT,
  devicesFile: DEV_PATH,
  publicUrl: "http://127.0.0.1:" + PORT,
  staticDir: undefined,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  ...extra,
})

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("d").token
  runs = 0
  cfg = { enabled: false, hour: 1, minute: 0 }
  ch = new WebChannel(
    base({
      getCuratorSettings: () => ({ config: cfg, nextRun: cfg.enabled ? "2026-06-01T00:00:00.000Z" : null }),
      setCuratorSettings: (raw: unknown) => {
        cfg = parseCuratorConfig(raw, cfg)
        return { config: cfg, nextRun: cfg.enabled ? "2026-06-01T00:00:00.000Z" : null }
      },
      runCuratorNow: async () => { runs++ },
      listChatIds: () => ["web:Mobile Pwa", "web:phone"],
    }),
  )
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}`, "content-type": "application/json" })

test("GET /settings/curator → config + nextRun + chats (auth required)", async () => {
  expect((await fetch(`http://127.0.0.1:${PORT}/settings/curator`)).status).toBe(401)
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/curator`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.config.enabled).toBe(false)
  expect(body.nextRun).toBeNull()
  expect(body.chats).toEqual(["web:Mobile Pwa", "web:phone"])
})

test("PUT /settings/curator persists + clamps", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/curator`, {
    method: "PUT", headers: auth(), body: JSON.stringify({ enabled: true, hour: 99, minute: 30 }),
  })
  const body = await res.json() as any
  expect(body.config).toEqual({ enabled: true, hour: 23, minute: 30 })
  expect(body.nextRun).not.toBeNull()
})

test("PUT cross-origin → 403", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/curator`, {
    method: "PUT", headers: { Cookie: `cmux_token=${token}`, Origin: "https://evil.com", "content-type": "application/json" }, body: "{}",
  })
  expect(res.status).toBe(403)
})

test("POST run-now triggers the run fn", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/curator/run-now`, { method: "POST", headers: auth() })
  expect(res.status).toBe(200)
  await new Promise((r) => setTimeout(r, 10))
  expect(runs).toBe(1)
})
