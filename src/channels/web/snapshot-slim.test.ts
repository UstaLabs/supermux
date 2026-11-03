// Card #54: a lighter startup snapshot for apps that ask for it, and gzip on the HTTP API.
// `slimSubagents` (with `trimExtras`) sends only RUNNING subagents for chats not on screen — the
// sidebar needs the running count; a chat gets its full list from chat-extras when it opens.
// `lazyArchived` leaves archived workspaces (and their project links) out of the snapshot; the app
// fetches the slim `/archived-workspaces?slim=1` right after. Old apps send neither and get
// today's snapshot.
import { afterEach, expect, test } from "bun:test"
import { gunzipSync } from "zlib"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

const SUBAGENTS: Record<string, { id: string; status: string; prompt?: string }[]> = {
  a: [{ id: "a-run", status: "running" }, { id: "a-done", status: "completed", prompt: "p".repeat(500) }],
  b: [{ id: "b-done", status: "completed" }],
}
const ACTIVE = [{ id: "w1", name: "live", status: "active", workdir: "/w" }]
const ARCHIVED = [{ id: "w9", name: "old", status: "archived", workdir: "/w", layout: { kind: "leaf" }, views: [{ id: "v1" }], project_id: "p1" }]

let archivedCalls: Array<{ slim?: boolean } | undefined> = []
let membershipCalls: Array<{ includeArchived?: boolean } | undefined> = []

async function boot(extra: Partial<WebChannelOpts> = {}): Promise<string> {
  archivedCalls = []
  membershipCalls = []
  const dir = mkdtempSync(join(tmpdir(), "mux-snapshot-slim-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [
      { id: "a", name: "alpha", workdir: "/w", mute: false, connected: true },
      { id: "b", name: "beta", workdir: "/w", mute: false, connected: true },
    ] as any,
    getSessionLog: () => [],
    getSessionActivity: () => [],
    getSessionCommands: () => [],
    getSessionCommandsResolved: () => true,
    getSessionSubagents: (id) => SUBAGENTS[id] ?? [],
    listWorkspaces: () => ACTIVE as any,
    listArchivedWorkspaces: (o) => {
      archivedCalls.push(o)
      return (o?.slim ? ARCHIVED.map(({ views: _v, layout: _l, ...rest }) => rest) : ARCHIVED) as any
    },
    listProjectCatalog: () => [{ id: "p1", name: "Proj" }],
    getProjectMembership: (o) => {
      membershipCalls.push(o)
      const m: Record<string, string> = o?.includeArchived === false ? { w1: "p1" } : { w1: "p1", w9: "p1" }
      return m
    },
    setMute: () => {},
    onSendFromWeb: () => {},
    ...extra,
  }
  channel = new WebChannel(opts)
  await channel.start()
  return new DeviceStore(devicesFile).mint("test-device").token
}

async function snapshot(subscribe: object): Promise<any> {
  const token = await boot()
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws`, { headers: { Authorization: `Bearer ${token}` } } as any)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const snap = new Promise<any>((resolve) => {
    ws.onmessage = (e) => {
      const f = JSON.parse(String(e.data))
      if (f.type === "snapshot") resolve(f)
    }
  })
  ws.send(JSON.stringify({ type: "subscribe", ...subscribe }))
  const frame = await snap
  ws.close()
  return frame
}

test("slimSubagents sends only running subagents for chats not on screen", async () => {
  const frame = await snapshot({ logTail: 1, fullLogs: ["b"], trimExtras: true, slimSubagents: true })
  expect(frame.subagents.a.map((s: any) => s.id)).toEqual(["a-run"])
  expect(frame.subagents.b.map((s: any) => s.id)).toEqual(["b-done"])
})

test("without slimSubagents every subagent is sent, as before", async () => {
  const frame = await snapshot({ logTail: 1, fullLogs: ["b"], trimExtras: true })
  expect(frame.subagents.a.map((s: any) => s.id)).toEqual(["a-run", "a-done"])
})

test("chat-extras carries the session's full subagent list", async () => {
  const token = await boot()
  const res = await fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/a/chat-extras`, { headers: { authorization: `Bearer ${token}` } })
  const body = await res.json() as { subagents: Array<{ id: string }> }
  expect(body.subagents.map((s) => s.id)).toEqual(["a-run", "a-done"])
})

test("lazyArchived leaves archived workspaces and their project links out of the snapshot", async () => {
  const frame = await snapshot({ logTail: 1, trimExtras: true, slimArchived: true, lazyArchived: true })
  expect(frame.archivedWorkspaces).toBeUndefined()
  expect(frame.archivedLazy).toBe(true)
  expect(frame.projectMembership).toEqual({ w1: "p1" })
  expect(archivedCalls).toEqual([])
  expect(membershipCalls).toEqual([{ includeArchived: false }])
})

test("without lazyArchived the snapshot still carries archived workspaces", async () => {
  const frame = await snapshot({ logTail: 1, slimArchived: true })
  expect(frame.archivedWorkspaces).toEqual([{ id: "w9", name: "old", status: "archived", workdir: "/w", project_id: "p1" }])
  expect(frame.archivedLazy).toBeUndefined()
  expect(frame.projectMembership).toEqual({ w1: "p1", w9: "p1" })
})

test("GET /archived-workspaces?slim=1 skips views and layout", async () => {
  const token = await boot()
  const res = await fetch(`http://127.0.0.1:${channel!.boundPort}/archived-workspaces?slim=1`, { headers: { authorization: `Bearer ${token}` } })
  expect(await res.json()).toEqual({ workspaces: [{ id: "w9", name: "old", status: "archived", workdir: "/w", project_id: "p1" }] })
  expect(archivedCalls).toEqual([{ slim: true }])
})

test("a big JSON answer is gzipped for a client that accepts it", async () => {
  const big = Array.from({ length: 400 }, (_, i) => ({ id: `m${i}`, ts: "2026-11-04T00:00:00Z", direction: "outbound", text: "hello ".repeat(20) }))
  const token = await boot({ getSessionLog: () => big })
  const url = `http://127.0.0.1:${channel!.boundPort}/sessions/a/messages`
  const raw = await fetch(url, { headers: { authorization: `Bearer ${token}`, "accept-encoding": "gzip" }, decompress: false } as any)
  expect(raw.headers.get("content-encoding")).toBe("gzip")
  expect(raw.headers.get("vary")).toContain("accept-encoding")
  const bytes = new Uint8Array(await raw.arrayBuffer())
  expect(bytes.length).toBeLessThan(JSON.stringify(big).length / 4)
  expect(JSON.parse(gunzipSync(bytes).toString())).toEqual(big)
})

test("a small answer, or a client that does not accept gzip, gets plain JSON", async () => {
  const big = Array.from({ length: 400 }, (_, i) => ({ id: `m${i}`, text: "hello ".repeat(20) }))
  const token = await boot({ getSessionLog: (id) => (id === "big" ? big : [{ id: "x" }]) })
  const base = `http://127.0.0.1:${channel!.boundPort}/sessions`
  const small = await fetch(`${base}/a/messages`, { headers: { authorization: `Bearer ${token}`, "accept-encoding": "gzip" }, decompress: false } as any)
  expect(small.headers.get("content-encoding")).toBeNull()
  const plain = await fetch(`${base}/big/messages`, { headers: { authorization: `Bearer ${token}`, "accept-encoding": "identity" }, decompress: false } as any)
  expect(plain.headers.get("content-encoding")).toBeNull()
  expect(await plain.json()).toEqual(big)
})
