import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"

const DEV_PATH = `/tmp/devices-host-local-${process.pid}.json`
const PORT = 18798
let ch: WebChannel

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getHostInfo: () => ({
      hostId: "h1", name: "n", platform: "darwin", version: "1.5.0", protocolVersion: 1,
      build: "1.5.0 (abc)", mode: "binary", managedBy: "desktop", stateDir: "/s",
    }),
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("a direct loopback request sees managedBy and stateDir", async () => {
  const body = await (await fetch(`http://127.0.0.1:${PORT}/host`)).json() as any
  expect(body.managedBy).toBe("desktop")
  expect(body.stateDir).toBe("/s")
  expect(body.build).toBe("1.5.0 (abc)")
})

test("a loopback request that went through the relay (X-Forwarded-For) does not", async () => {
  const body = await (await fetch(`http://127.0.0.1:${PORT}/host`, {
    headers: { "x-forwarded-for": "203.0.113.9" },
  })).json() as any
  expect(body.managedBy).toBeUndefined()
  expect(body.stateDir).toBeUndefined()
})
