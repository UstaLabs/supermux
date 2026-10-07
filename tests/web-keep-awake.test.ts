// "Keep the computer awake while hosting": the web surface (settings routes, /host, the frame,
// the heartbeat across a sleep and the wake resync).
import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, WS_CLOSE_RESYNC, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import type { KeepAwakeState } from "../src/core/power/keep-awake"
import type { KeepAwakeSettings } from "../src/core/settings/keep-awake-config"

const DEV_PATH = `/tmp/devices-keep-awake-${process.pid}.json`
const PORT = 18824
let ch: WebChannel
let token: string
let state: KeepAwakeState
let setCalls: Array<Partial<KeepAwakeSettings>>

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  token = new DeviceStore(DEV_PATH).mint("d").token
  state = { enabled: true, onBattery: true, active: true, supported: true }
  setCalls = []
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getHostInfo: () => ({ hostId: "h1", name: "n", platform: "linux", version: "1.5.0", protocolVersion: 1, keepAwake: state }),
    getKeepAwake: () => state,
    setKeepAwake: (patch: Partial<KeepAwakeSettings>) => {
      setCalls.push(patch)
      state = { ...state, ...patch, active: patch.enabled ?? state.enabled }
      return state
    },
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const base = `http://127.0.0.1:${PORT}`
const authed = () => ({ Cookie: `cmux_token=${token}`, Origin: base, "content-type": "application/json" })
/** The same request as it arrives through the relay (frpc adds X-Forwarded-For). */
const relayed = () => ({ ...authed(), "x-forwarded-for": "203.0.113.9" })

// ── GET /settings/keep-awake ────────────────────────────────────────────────────────────────

test("GET needs auth", async () => {
  expect((await fetch(`${base}/settings/keep-awake`)).status).toBe(401)
})

test("GET answers any authed caller, local or relayed", async () => {
  for (const headers of [authed(), relayed()]) {
    const res = await fetch(`${base}/settings/keep-awake`, { headers })
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual(state)
  }
})

// ── PUT /settings/keep-awake: only on this computer ─────────────────────────────────────────

test("PUT from the host computer itself (direct loopback) saves and returns the new state", async () => {
  const res = await fetch(`${base}/settings/keep-awake`, { method: "PUT", headers: authed(), body: JSON.stringify({ enabled: false }) })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ enabled: false, onBattery: true, active: false, supported: true })
  expect(setCalls).toEqual([{ enabled: false }])
})

test("PUT from a phone through the relay is refused: 403 only on this computer", async () => {
  const res = await fetch(`${base}/settings/keep-awake`, { method: "PUT", headers: relayed(), body: JSON.stringify({ enabled: false }) })
  expect(res.status).toBe(403)
  expect(await res.json()).toEqual({ error: "only on this computer" })
  expect(setCalls).toHaveLength(0)
})

test("PUT from loopback with a foreign Host (DNS rebinding) is refused", async () => {
  const res = await fetch(`${base}/settings/keep-awake`, {
    method: "PUT", headers: { ...authed(), Host: "evil.example" }, body: JSON.stringify({ enabled: false }),
  })
  expect([403]).toContain(res.status)
  expect(setCalls).toHaveLength(0)
})

test("PUT needs auth and the same-origin CSRF rule", async () => {
  let res = await fetch(`${base}/settings/keep-awake`, { method: "PUT", headers: { Origin: base, "content-type": "application/json" }, body: "{\"enabled\":true}" })
  expect(res.status).toBe(401)
  res = await fetch(`${base}/settings/keep-awake`, {
    method: "PUT", headers: { ...authed(), Origin: "https://evil.com" }, body: "{\"enabled\":true}",
  })
  expect(res.status).toBe(403)
  expect(setCalls).toHaveLength(0)
})

test("PUT rejects a non-boolean or empty body with 400", async () => {
  for (const body of ['{"enabled":"yes"}', "{}", "not json"]) {
    const res = await fetch(`${base}/settings/keep-awake`, { method: "PUT", headers: authed(), body })
    expect(res.status).toBe(400)
  }
  expect(setCalls).toHaveLength(0)
})

// ── GET /host ───────────────────────────────────────────────────────────────────────────────

test("GET /host carries keepAwake for authed and direct-loopback callers, not for the public", async () => {
  const local = await (await fetch(`${base}/host`)).json() as any
  expect(local.keepAwake).toEqual(state)
  const viaRelay = await (await fetch(`${base}/host`, { headers: { Cookie: `cmux_token=${token}`, "x-forwarded-for": "203.0.113.9" } })).json() as any
  expect(viaRelay.keepAwake).toEqual(state)
  const pub = await (await fetch(`${base}/host`, { headers: { "x-forwarded-for": "203.0.113.9" } })).json() as any
  expect(pub.keepAwake).toBeUndefined()
})

// ── the keep_awake frame and the wake resync ────────────────────────────────────────────────

async function openWs(): Promise<WebSocket> {
  return new Promise<WebSocket>((resolve, reject) => {
    const w = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
    w.onopen = () => resolve(w)
    w.onerror = (e) => reject(e)
  })
}

test("a subscribing client gets keep_awake right after the snapshot", async () => {
  const ws = await openWs()
  const frames: any[] = []
  const done = new Promise<void>((resolve) => {
    ws.onmessage = (e) => {
      const f = JSON.parse(String(e.data))
      if (f.type === "snapshot" || f.type === "keep_awake") frames.push(f)
      if (frames.length === 2) resolve()
    }
  })
  ws.send(JSON.stringify({ type: "subscribe" }))
  await done
  expect(frames.map((f) => f.type)).toEqual(["snapshot", "keep_awake"])
  expect(frames[1].keepAwake).toEqual(state)
  ws.close()
})

test("resyncClients closes main sockets with the resync code so clients resubscribe", async () => {
  const ws = await openWs()
  const closed = new Promise<{ code: number; reason: string }>((resolve) => { ws.onclose = (e) => resolve({ code: e.code, reason: e.reason }) })
  await Bun.sleep(20)
  expect(ch.resyncClients("resync after wake")).toBe(1)
  expect(await closed).toEqual({ code: WS_CLOSE_RESYNC, reason: "resync after wake" })
})

test("sleep is not silence: the first heartbeat after a long gap keeps sockets that could not pong", async () => {
  const ws = await openWs()
  let closed = false
  ws.onclose = () => { closed = true }
  await Bun.sleep(20)
  const t0 = Date.now()
  ch.pingAll(t0)
  ch.pingAll(t0 + 30_000 + 3_600_000) // an hour asleep between two heartbeats
  await Bun.sleep(50)
  expect(closed).toBe(false)
  // A socket that stays silent after the wake is still closed on the normal schedule.
  ch.pingAll(t0 + 30_000 + 3_600_000 + 30_000)
  ch.pingAll(t0 + 30_000 + 3_600_000 + 90_000)
  await Bun.sleep(50)
  expect(closed).toBe(true)
})
