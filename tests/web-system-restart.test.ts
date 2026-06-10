import { test, expect, beforeEach, afterEach, mock } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const childProcess = require("child_process")

const DEV_PATH = `/tmp/devices-system-restart-${process.pid}.json`
const PORT = 18797
let ch: WebChannel
let token: string

const spawnCalls: Array<{ cmd: string; args: string[]; opts: Record<string, unknown> }> = []

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
  spawnCalls.length = 0
  ch = new WebChannel(base({}))
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  mock.restore()
})

const auth = () => ({
  Cookie: `cmux_token=${token}`,
  Origin: `http://127.0.0.1:${PORT}`,
  "content-type": "application/json",
})

test("POST /system/restart returns 401 without auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/system/restart`, {
    method: "POST",
    headers: { "content-type": "application/json", Origin: `http://127.0.0.1:${PORT}` },
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

test("POST /system/restart spawns systemctl and returns ok", async () => {
  const originalSpawn = childProcess.spawn
  const spawnMock = mock((cmd: string, args: string[], opts: Record<string, unknown>) => {
    spawnCalls.push({ cmd, args, opts })
    return { unref: () => {} }
  })
  // Override the exported spawn directly instead of mock.module() which leaks
  // across test files in Bun's parallel test runner.
  Object.defineProperty(childProcess, "spawn", { value: spawnMock, writable: true, configurable: true })

  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/system/restart`, {
      method: "POST",
      headers: auth(),
      body: "{}",
    })
    expect(res.status).toBe(200)
    const body = await res.json() as any
    expect(body.ok).toBe(true)
    expect(spawnCalls.length).toBe(1)
    expect(spawnCalls[0]!.cmd).toBe("systemctl")
    expect(spawnCalls[0]!.args).toEqual(["--user", "restart", "mux.service"])
    expect(spawnCalls[0]!.opts.detached).toBe(true)
  } finally {
    Object.defineProperty(childProcess, "spawn", { value: originalSpawn, writable: true, configurable: true })
    mock.restore()
  }
})
