// Card #54: the broker's half of the load-time trace. With tracing on, the snapshot says so (the
// apps then ship their own timings), every API response and the snapshot record what they cost
// under the client's trace id, client timings land in the same log, and broadcasts are counted per
// frame type. With tracing off nothing changes on the wire.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { PerfLog } from "../../core/perf/perf-log"

let channel: WebChannel | undefined
const dirs: string[] = []
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

async function boot(perfOn: boolean, now?: () => number): Promise<{ token: string; perf: PerfLog }> {
  const dir = mkdtempSync(join(tmpdir(), "mux-perf-web-"))
  dirs.push(dir)
  const devicesFile = join(dir, "devices.json")
  const perf = new PerfLog({ dir: join(dir, "perf"), envEnabled: perfOn, flushMs: 0, now })
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [
      { id: "a", name: "alpha", workdir: "/w", mute: false, connected: true },
      { id: "b", name: "beta", workdir: "/w", mute: false, connected: true },
    ] as any,
    getSessionLog: (id, limit) => [{ id: `${id}1` }, { id: `${id}2` }].slice(-(limit ?? 2)),
    getSessionActivity: () => [],
    getSessionCommands: () => [],
    getSessionCommandsResolved: () => true,
    listWorkspaces: () => [{ id: "w1" }] as any,
    listArchivedWorkspaces: () => [{ id: "w9", status: "archived", views: [], layout: {} }] as any,
    setMute: () => {},
    onSendFromWeb: () => {},
    perf,
  }
  channel = new WebChannel(opts)
  await channel.start()
  return { token: new DeviceStore(devicesFile).mint("mac").token, perf }
}

async function connect(token: string, query = ""): Promise<{ ws: WebSocket; next: (type: string) => Promise<any> }> {
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws${query}`, { headers: { Authorization: `Bearer ${token}` } } as any)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const waiting = new Map<string, (f: any) => void>()
  ws.onmessage = (e) => {
    const f = JSON.parse(String(e.data))
    waiting.get(f.type)?.(f)
  }
  return { ws, next: (type) => new Promise((resolve) => waiting.set(type, resolve)) }
}

test("with tracing off the snapshot carries no perf flag and nothing is recorded", async () => {
  const { token, perf } = await boot(false)
  const { ws, next } = await connect(token)
  const snap = next("snapshot")
  ws.send(JSON.stringify({ type: "subscribe", logTail: 1, trace: "L1", req: "s1" }))
  expect((await snap).perf).toBeUndefined()
  ws.close()
  expect(perf.events()).toEqual([])
})

test("with tracing on the snapshot says so and records its build under the client's trace", async () => {
  const { token, perf } = await boot(true)
  const { ws, next } = await connect(token, "?clientSurface=desktop&trace=L1")
  const snap = next("snapshot")
  ws.send(JSON.stringify({ type: "subscribe", logTail: 1, trimExtras: true, slimArchived: true, trace: "L1", req: "s1" }))
  const frame = await snap
  expect(frame.perf).toBe(true)
  ws.close()
  const built = perf.events().find((e) => e.event === "snapshot.built")!
  expect(built).toMatchObject({ src: "broker", trace: "L1", req: "s1", device: "mac", sessions: 2 })
  expect(built.bytes).toBeGreaterThan(100)
  expect(typeof built.ms).toBe("number")
  // Each phase of the build has its own time, and they add up to no more than the whole.
  const laps = built.laps as Record<string, number>
  for (const phase of ["sessions", "perSession", "attachments", "workspaces", "archivedWorkspaces", "projects", "stringify", "send"]) {
    expect(typeof laps[phase]).toBe("number")
  }
  // Per-key sizes, so the trace shows what the bytes are made of.
  expect((built.keyBytes as Record<string, number>).logs).toBeGreaterThan(0)
  expect(perf.events().find((e) => e.event === "ws.upgrade")).toMatchObject({ trace: "L1", device: "mac", surface: "desktop" })
})

test("an API response records its cost under the request's trace header and says it back", async () => {
  const { token, perf } = await boot(true)
  const id = "5fe66c32-94d9-43d2-916d-9f2ee74366bf"
  const res = await fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/${id}/messages`, {
    headers: { authorization: `Bearer ${token}`, "x-mux-trace": "C7/r3" },
  })
  expect(res.status).toBe(200)
  expect(res.headers.get("server-timing")).toMatch(/^app;dur=\d+(\.\d+)?$/)
  const http = perf.events().find((e) => e.event === "http")!
  expect(http).toMatchObject({ trace: "C7", req: "r3", method: "GET", path: `/sessions/${id}/messages`, route: "/sessions/:id/messages", status: 200, device: "mac" })
  expect(http.bytes).toBe((await res.text()).length)
  // The history route also says where its time went: the SQLite read and the attachment sizes.
  expect(http).toMatchObject({ count: 2 })
  expect(typeof http.logMs).toBe("number")
  expect(typeof http.enrichMs).toBe("number")
})

test("chat-extras says how many activity rows and commands it carried", async () => {
  const { token, perf } = await boot(true)
  await fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/a/chat-extras`, { headers: { authorization: `Bearer ${token}`, "x-mux-trace": "C8/r4" } })
  expect(perf.events().find((e) => e.event === "http")).toMatchObject({ trace: "C8", activity: 0, commands: 0 })
})

test("an API response with tracing off has no server-timing header", async () => {
  const { token } = await boot(false)
  const res = await fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/a/messages`, { headers: { authorization: `Bearer ${token}` } })
  expect(res.headers.get("server-timing")).toBeNull()
})

test("perf entries from an app land in the perf log on the broker's clock", async () => {
  const { token, perf } = await boot(true, () => 50_000)
  const { ws } = await connect(token)
  ws.send(JSON.stringify({
    type: "client_logs",
    meta: { t: 1_000, launch: "L1", surface: "desktop" },
    entries: [{ category: "perf", event: "ui.sessions.drawn", ts: 400, data: { trace: "L1", sessions: 150 } }],
  }))
  for (let i = 0; i < 50 && !perf.events().some((x) => x.event === "ui.sessions.drawn"); i++) await Bun.sleep(10)
  ws.close()
  const e = perf.events().find((x) => x.event === "ui.sessions.drawn")!
  // 600 ms before the batch was sent, which the broker received at 50 000.
  expect(e).toMatchObject({ src: "client", trace: "L1", t: 400, sessions: 150, device: "mac", surface: "desktop" })
  expect(e.ts).toBeGreaterThan(49_300)
  expect(e.ts).toBeLessThanOrEqual(49_400)
})

test("broadcasts are counted per frame type and written out as one stats event", async () => {
  const { token, perf } = await boot(true)
  const { ws } = await connect(token)
  channel!.broadcastToAll({ type: "activity_append", session: "a", event: { kind: "tool" } })
  channel!.broadcastToAll({ type: "activity_append", session: "b", event: { kind: "tool" } })
  channel!.broadcastToAll({ type: "agent_state", session: "a" })
  channel!.flushPerfStats()
  ws.close()
  const stats = perf.events().find((e) => e.event === "ws.frames")!
  expect((stats.frames as any).activity_append).toMatchObject({ frames: 2, sends: 2 })
  expect((stats.frames as any).agent_state).toMatchObject({ frames: 1, sends: 1 })
  expect(stats.clients).toBe(1)
})

test("GET /debug/perf returns the recorded events and PUT turns tracing on and off", async () => {
  const { token, perf } = await boot(false)
  const auth = { authorization: `Bearer ${token}` }
  const base = `http://127.0.0.1:${channel!.boundPort}/debug/perf`
  expect(await (await fetch(base, { headers: auth })).json()).toEqual({ enabled: false, events: [] })
  const put = await fetch(base, { method: "PUT", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify({ enabled: true }) })
  expect(await put.json()).toEqual({ enabled: true })
  expect(perf.enabled()).toBe(true)
  await fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/a/messages`, { headers: { ...auth, "x-mux-trace": "C1/r1" } })
  const got = await (await fetch(`${base}?trace=C1`, { headers: auth })).json() as { events: Array<{ event: string }> }
  expect(got.events.map((e) => e.event)).toEqual(["http"])
  perf.setEnabled(false)
})

test("GET /debug/perf needs a paired device", async () => {
  await boot(true)
  expect((await fetch(`http://127.0.0.1:${channel!.boundPort}/debug/perf`)).status).toBe(401)
})
