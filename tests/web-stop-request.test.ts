// The authenticated local stop request (platform-layer spec, Q8). On Windows every external stop is TerminateProcess,
// so the broker's graceful shutdown never ran and broker.pid, .core.lock and its sockets were left behind. The desktop
// app asks the broker to stop instead, with the per-boot token the broker writes next to its state (stop-token).
import { afterEach, beforeEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, statSync, unlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { STOP_TOKEN_FILE, writeStopToken } from "../src/core/session-manager/stop-token"

const DEV_PATH = `/tmp/devices-stop-${process.pid}.json`
const TOKEN = "s3cret-stop-token"
let ch: WebChannel
let base = ""
let stops = 0
let restarts = 0
let restartable = true
let expected = 0

beforeEach(async () => {
  __resetAuthFailures()
  stops = 0
  restarts = 0
  restartable = true
  expected = 0
  ch = new WebChannel({
    port: 0,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1",
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    stopToken: TOKEN,
    onStopRequest: () => { stops++ },
    onRestartRequest: () => { restarts++; return restartable },
    onExpectRestart: () => { expected++ },
  } as any)
  await ch.start()
  base = `http://127.0.0.1:${ch.boundPort}`
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("the stop token asks the broker to stop gracefully", async () => {
  const res = await fetch(`${base}/internal/stop`, { method: "POST", headers: { "x-supermux-stop-token": TOKEN } })
  expect(res.status).toBe(202)
  await Bun.sleep(20)
  expect(stops).toBe(1)
})

test("a missing or wrong token is refused and stops nothing", async () => {
  for (const headers of [{}, { "x-supermux-stop-token": "nope" }, { "x-supermux-stop-token": "" }] as Record<string, string>[]) {
    const res = await fetch(`${base}/internal/stop`, { method: "POST", headers })
    expect(res.status).toBe(403)
  }
  await Bun.sleep(20)
  expect(stops).toBe(0)
})

test("only a POST stops", async () => {
  const res = await fetch(`${base}/internal/stop`, { headers: { "x-supermux-stop-token": TOKEN } })
  expect(res.status).not.toBe(202)
  await Bun.sleep(20)
  expect(stops).toBe(0)
})

test("each boot writes a new token, readable only by its user", () => {
  const state = mkdtempSync(join(tmpdir(), "mux-stop-"))
  const first = writeStopToken(state)
  const second = writeStopToken(state)
  expect(second).not.toBe(first)
  expect(second).toMatch(/^[0-9a-f]{48}$/)
  expect(readFileSync(join(state, STOP_TOKEN_FILE), "utf8")).toBe(second)
  if (process.platform !== "win32") expect(statSync(join(state, STOP_TOKEN_FILE)).mode & 0o777).toBe(0o600)
})

// `supermux connect --restart`: the broker restarts under whatever runs it (update/apply.ts restartService), which only
// it knows; a broker nothing would bring back says so instead of stopping for good.
test("the stop token asks the broker to restart under what runs it", async () => {
  const res = await fetch(`${base}/internal/restart`, { method: "POST", headers: { "x-supermux-stop-token": TOKEN } })
  expect(res.status).toBe(202)
  expect(restarts).toBe(1)
})

test("a broker nothing would bring back refuses to restart (409), and a missing token restarts nothing", async () => {
  restartable = false
  expect((await fetch(`${base}/internal/restart`, { method: "POST", headers: { "x-supermux-stop-token": TOKEN } })).status).toBe(409)
  restartable = true
  expect((await fetch(`${base}/internal/restart`, { method: "POST" })).status).toBe(403)
  expect(restarts).toBe(1)
})

// #29: the desktop app restarts its broker itself (systemctl restart, launchctl kickstart -k, a child it stops and
// starts again), so it says so first. The broker then leaves the agents running when that stop comes.
test("the stop token tells the broker its next stop is a restart, and stops nothing", async () => {
  const res = await fetch(`${base}/internal/expect-restart`, { method: "POST", headers: { "x-supermux-stop-token": TOKEN } })
  expect(res.status).toBe(204)
  await Bun.sleep(20)
  expect(expected).toBe(1)
  expect(stops + restarts).toBe(0)
})

test("a restart announced without the token, or with a GET, is refused", async () => {
  for (const headers of [{}, { "x-supermux-stop-token": "nope" }] as Record<string, string>[]) {
    expect((await fetch(`${base}/internal/expect-restart`, { method: "POST", headers })).status).toBe(403)
  }
  expect((await fetch(`${base}/internal/expect-restart`, { headers: { "x-supermux-stop-token": TOKEN } })).status).toBe(405)
  expect(expected).toBe(0)
})
