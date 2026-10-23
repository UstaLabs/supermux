import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-telemetry-web-${process.pid}.json`
const PORT = 18911
let ch: WebChannel
let token: string
let recorded: { event: string; props: Record<string, unknown> }[]
let enabled: boolean

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  token = new DeviceStore(DEV_PATH).mint("d").token
  recorded = []
  enabled = true
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    createWorkspace: async () => ({ id: "w1", name: "w", workdir: "/tmp" }) as any,
    getSessionAgent: () => ({ agent: "claude" }),
    switchPermissionMode: async () => ({ ok: true, applied: "now" }) as any,
    telemetry: {
      record: (event, props) => { recorded.push({ event, props }) },
      getState: () => ({ enabled, source: "default", noticeShown: false }),
      setEnabled: (v) => { enabled = v; return { enabled, source: "setting", noticeShown: false } },
      recent: () => [{ event: "broker.started" }],
    },
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}`, "content-type": "application/json" })

function openWs(query = ""): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const w = new WebSocket(`ws://127.0.0.1:${PORT}/ws${query}`, { headers: { Cookie: `cmux_token=${token}` } } as any)
    w.onopen = () => resolve(w)
    w.onerror = (e) => reject(e)
  })
}

const until = async (cond: () => boolean) => {
  for (let i = 0; i < 50 && !cond(); i++) await Bun.sleep(10)
}

test("GET/PUT /settings/telemetry and /recent", async () => {
  const get = await fetch(`http://127.0.0.1:${PORT}/settings/telemetry`, { headers: auth() })
  expect(await get.json()).toMatchObject({ enabled: true, source: "default" })

  const put = await fetch(`http://127.0.0.1:${PORT}/settings/telemetry`, { method: "PUT", headers: auth(), body: JSON.stringify({ enabled: false }) })
  expect(put.status).toBe(200)
  expect(enabled).toBe(false)

  const bad = await fetch(`http://127.0.0.1:${PORT}/settings/telemetry`, { method: "PUT", headers: auth(), body: JSON.stringify({ enabled: "no" }) })
  expect(bad.status).toBe(400)

  const recent = await fetch(`http://127.0.0.1:${PORT}/settings/telemetry/recent`, { headers: auth() })
  expect(await recent.json()).toEqual({ events: [{ event: "broker.started" }] })
})

test("/settings/telemetry needs auth", async () => {
  expect((await fetch(`http://127.0.0.1:${PORT}/settings/telemetry`)).status).toBe(401)
})

test("/ws with client params records client.connected; invalid params record nothing", async () => {
  const w = await openWs("?clientSurface=android&clientVersion=0.14.0&clientOs=android&clientOsMajor=16")
  expect(recorded).toContainEqual({ event: "client.connected", props: { surface: "android", clientVersion: "0.14.0", clientOs: "android", clientOsMajor: 16 } })
  w.close()
  recorded = []
  const w2 = await openWs("?clientSurface=fridge")
  expect(recorded).toEqual([])
  w2.close()
})

test("a successful POST /workspaces records feature.used with the device's surface", async () => {
  const w = await openWs("?clientSurface=desktop")
  const res = await fetch(`http://127.0.0.1:${PORT}/workspaces`, { method: "POST", headers: auth(), body: JSON.stringify({ workdir: "/tmp" }) })
  expect(res.status).toBeLessThan(400)
  expect(recorded).toContainEqual({ event: "feature.used", props: { feature: "workspace.create", surface: "desktop" } })
  w.close()
})

test("a failed request records nothing", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/workspaces`, { method: "POST", headers: auth(), body: "{}" })
  expect(res.status).toBeGreaterThanOrEqual(400)
  expect(recorded.filter((r) => r.event === "feature.used")).toEqual([])
})

test("editor_open and set_permission_mode frames record features", async () => {
  const w = await openWs("?clientSurface=web")
  w.send(JSON.stringify({ type: "editor_open", session: "s", path: "a.ts" }))
  w.send(JSON.stringify({ type: "set_permission_mode", session: "s", mode: "plan" }))
  await until(() => recorded.filter((r) => r.event === "feature.used").length >= 2)
  expect(recorded).toContainEqual({ event: "feature.used", props: { feature: "editor.open", surface: "web" } })
  expect(recorded).toContainEqual({ event: "feature.used", props: { feature: "permission_mode.set", variant: "plan", agent: "claude", surface: "web" } })
  w.close()
})
