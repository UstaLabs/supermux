import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-http-${process.pid}.json`
// Port 0: the OS picks a free port, read back once the channel listens. A fixed one collides with
// whatever else holds it (another run, a listener a crashed test left behind) and fails for no
// reason of the code under test.
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
    getSessionsSnapshot: () => [{ name: "ana", workdir: "/h", mute: false, connected: true, agent: "claude" as const }],
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

test("GET /sessions without token → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`)
  expect(res.status).toBe(401)
})

test("GET /sessions with bad token → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, { headers: { Cookie: `cmux_token=not-real` } })
  expect(res.status).toBe(401)
})

test("GET /sessions with valid token → snapshot", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(200)
  const body = await res.json()
  // The watch-facing /sessions route enriches each row (phase/preview/unread); with no
  // agent-state/log/reads mocked here, only the always-present `unread` flag is added.
  expect(body).toEqual([{ name: "ana", workdir: "/h", mute: false, connected: true, agent: "claude", unread: false }])
})

test("GET /devices returns list (no tokens included)", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/devices`, { headers: { Cookie: `cmux_token=${token}` } })
  const body = await res.json() as unknown[]
  expect(body.length).toBe(1)
  expect(body[0]).toHaveProperty("name", "test-device")
  expect(body[0]).not.toHaveProperty("token_hash")
})

test("POST /devices mints + returns URL", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/devices`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ name: "laptop" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as { url: string; name: string }
  expect(body.url).toMatch(/\/pair\?t=/)
  expect(body.name).toBe("laptop")
})

test("DELETE /devices/:name revokes", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/devices/test-device`, {
    method: "DELETE",
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(204)
  // subsequent auth with that token should fail
  const after = await fetch(`http://127.0.0.1:${PORT}/sessions`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(after.status).toBe(401)
})
