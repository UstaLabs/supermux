import { test, expect, beforeEach, afterEach, mock } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const childProcess = require("child_process")

const DEV_PATH = `/tmp/devices-system-restart-${process.pid}.json`
// Port 0: the OS picks a free port, read back once the channel listens. A fixed one collides with
// whatever else holds it (another run, a listener a crashed test left behind) and fails for no
// reason of the code under test.
let PORT = 0
let ch: WebChannel
let token: string

const spawnCalls: Array<{ cmd: string; args: string[]; opts: Record<string, unknown> }> = []

const base = (extra: object) => ({
  port: 0,
  devicesFile: DEV_PATH,
  publicUrl: "http://127.0.0.1",
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
  spawnCalls.length = 0
  ch = new WebChannel(base({}))
  await ch.start()
  PORT = ch.boundPort
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  mock.restore()
})

const auth = () => ({
  Cookie: `cmux_token=${token}`,
  Origin: "http://127.0.0.1",
  "content-type": "application/json",
})

test("POST /system/restart returns 401 without auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/system/restart`, {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "http://127.0.0.1" },
  })
  expect(res.status).toBe(401)
})

test("POST /system/restart returns 403 cross-origin", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/system/restart`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, Origin: "https://evil.com", "content-type": "application/json" },
    body: "{}",
  })
  expect(res.status).toBe(403)
})

test("POST /system/restart refuses with 409 when not under a service manager", async () => {
  // Clearing both gates guarantees restartService() returns false without
  // spawning anything — safe even when the test runner lives in mux.service.
  const savedInv = process.env.INVOCATION_ID
  const savedXpc = process.env.XPC_SERVICE_NAME
  delete process.env.INVOCATION_ID
  delete process.env.XPC_SERVICE_NAME
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/system/restart`, {
      method: "POST",
      headers: auth(),
      body: "{}",
    })
    expect(res.status).toBe(409)
    const body = await res.json() as any
    expect(body.ok).toBe(false)
  } finally {
    if (savedInv !== undefined) process.env.INVOCATION_ID = savedInv
    if (savedXpc !== undefined) process.env.XPC_SERVICE_NAME = savedXpc
  }
})

test.skip("POST /system/restart spawns systemctl and returns ok", async () => {
  // DANGER: this test cannot be safely mocked.
  // The broker uses `await import("child_process")` (ESM dynamic import)
  // but Bun test mocks only affect `require("child_process")` (CJS).
  // These are different module objects, so the mock is silently ignored
  // and the REAL systemctl restart is executed.
  //
  // To make this testable, the broker needs a `restartBroker` hook in
  // WebChannel opts so the test can inject a no-op instead.
  const res = await fetch(`http://127.0.0.1:${PORT}/system/restart`, {
    method: "POST",
    headers: auth(),
    body: "{}",
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.ok).toBe(true)
})
