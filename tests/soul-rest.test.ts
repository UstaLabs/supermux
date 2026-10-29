import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-soul-rest-${process.pid}.json`
const PORT = 18793
let ch: WebChannel
let token: string

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
  const deviceStore = new DeviceStore(DEV_PATH)
  token = deviceStore.mint("d").token
  ch = new WebChannel(base({}))
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}` })

for (const method of ["GET", "PUT"]) {
  test(`${method} /settings/soul is gone (410) and points at Agent memory`, async () => {
    const res = await fetch(`http://127.0.0.1:${PORT}/settings/soul`, { method, headers: auth(), ...(method === "PUT" ? { body: "x" } : {}) })
    expect(res.status).toBe(410)
    expect((await res.json() as any).error).toContain("agent-memory")
  })
}

test("GET /settings/soul with no auth cookie → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/soul`)
  expect(res.status).toBe(401)
})
