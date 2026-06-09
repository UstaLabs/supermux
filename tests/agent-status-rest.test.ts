import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-agent-status-${process.pid}.json`
const PORT = 18792
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
  ch = new WebChannel(
    base({
      getAgentStatuses: () => [
        { kind: "claude", installed: true, authed: true },
        { kind: "codex", installed: true, authed: false },
        { kind: "cursor", installed: false, authed: false },
      ],
    }),
  )
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}`, "content-type": "application/json" })

test("GET /agents/status returns 200 with agent statuses array", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/agents/status`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.json() as any[]
  expect(Array.isArray(body)).toBe(true)
  expect(body.length).toBe(3)
  expect(body[0].kind).toBe("claude")
  expect(body[1].authed).toBe(false)
})

test("GET /agents/status requires auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/agents/status`)
  expect(res.status).toBe(401)
})
