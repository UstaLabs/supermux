import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { request } from "node:http"
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
      build: "1.5.0 (abc)", mode: "binary", managedBy: "desktop", stateDir: "/s", gitAvailable: false,
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
  expect(body.mode).toBe("binary")
  expect(body.platform).toBe("darwin")
  expect(body.gitAvailable).toBe(false)
})

test("a loopback request that went through the relay (X-Forwarded-For) does not", async () => {
  const body = await (await fetch(`http://127.0.0.1:${PORT}/host`, {
    headers: { "x-forwarded-for": "203.0.113.9" },
  })).json() as any
  expect(body.gitAvailable).toBeUndefined()
  expect(body.managedBy).toBeUndefined()
  expect(body.stateDir).toBeUndefined()
})

test("a loopback request that carries CF-Connecting-IP does not", async () => {
  const body = await (await fetch(`http://127.0.0.1:${PORT}/host`, {
    headers: { "cf-connecting-ip": "203.0.113.9" },
  })).json() as any
  expect(body.managedBy).toBeUndefined()
  expect(body.stateDir).toBeUndefined()
  expect(body.build).toBeUndefined()
})

// DNS rebinding: a page on evil.example resolved to 127.0.0.1 arrives from a loopback peer with
// no forwarding headers, but its Host header is the attacker's domain.
test("a loopback request with a non-loopback Host header does not", async () => {
  const body = await new Promise<any>((resolve, reject) => {
    const r = request({ host: "127.0.0.1", port: PORT, path: "/host", headers: { host: `evil.example:${PORT}` } }, res => {
      let d = ""
      res.on("data", c => (d += c))
      res.on("end", () => resolve(JSON.parse(d)))
    })
    r.on("error", reject)
    r.end()
  })
  expect(body.hostId).toBe("h1")
  expect(body.managedBy).toBeUndefined()
  expect(body.stateDir).toBeUndefined()
  expect(body.build).toBeUndefined()
})
