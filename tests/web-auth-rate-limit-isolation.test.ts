// tests/web-auth-rate-limit-isolation.test.ts
// Auth-failure rate limiting must be scoped to a single WebChannel instance,
// not shared process-wide. Two channels in one process (as happens constantly
// under `bun test`, where every test file hits 127.0.0.1 with no forwarded-for
// header — so clientIp() collapses to "unknown") must keep independent
// rate-limit buckets. Otherwise one channel's auth failures starve another
// channel's *valid* authenticated requests with a spurious 401.
import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let tmpDir: string
let channelA: WebChannel
let channelB: WebChannel
let portA: number
let portB: number
let tokenB: string

function makeChannel(port: number, devicesFile: string): WebChannel {
  return new WebChannel({
    port,
    devicesFile,
    publicUrl: `http://127.0.0.1:${port}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
  } as any)
}

beforeEach(async () => {
  __resetAuthFailures()
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-ratelimit-iso-"))
  channelA = makeChannel(0, join(tmpDir, "devicesA.json"))
  // Mint a valid device for channel B *before* constructing it so its internal
  // device store reads the freshly-minted token from disk.
  const dsB = new DeviceStore(join(tmpDir, "devicesB.json"))
  tokenB = dsB.mint("iphone").token
  channelB = makeChannel(0, join(tmpDir, "devicesB.json"))
  await channelA.start()
  await channelB.start()
  // OS-assigned unique ports, read after start. Fixed/random ports collide across
  // the full suite and let Bun's fetch pool reuse a stale keep-alive socket to a
  // stopped channel — producing the very cross-instance 401 this test guards.
  portA = channelA.boundPort
  portB = channelB.boundPort
})

afterEach(async () => {
  await channelA.stop()
  await channelB.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("flooding one channel's auth-failure limit does not block a valid auth on another channel", async () => {
  // Trip channel A's auth-failure rate limit (RATE_LIMIT_MAX = 16) with
  // unauthenticated requests. All share the clientIp() key "unknown".
  for (let i = 0; i < 20; i++) {
    await fetch(`http://127.0.0.1:${portA}/me`)
  }

  // Channel B is a *separate* WebChannel instance holding a valid device.
  // Its authenticated request must succeed — channel A's failures must not
  // bleed across instances through shared module-global rate-limit state.
  const res = await fetch(`http://127.0.0.1:${portB}/me`, {
    headers: { Cookie: `cmux_token=${tokenB}` },
  })
  expect(res.status).toBe(200)
})
