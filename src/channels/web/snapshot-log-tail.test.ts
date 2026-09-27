// The WS snapshot's per-session log size. A client that sends `logTail` gets only the newest
// `logTail` entries for every session it did not list in `fullLogs`, and the snapshot names those
// sessions in `partialLogs`. A client that sends neither field gets full logs (old clients).
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

const LOGS: Record<string, { id: string }[]> = {
  a: [{ id: "a1" }, { id: "a2" }, { id: "a3" }],
  b: [{ id: "b1" }, { id: "b2" }],
}

const ARCHIVED = [{ id: "w9", name: "old", status: "archived", workdir: "/w", layout: { kind: "leaf" }, views: [{ id: "v1" }] }]

async function boot(): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "mux-snapshot-tail-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [
      { id: "a", name: "alpha", workdir: "/w", mute: false, connected: true },
      { id: "b", name: "beta", workdir: "/w", mute: false, connected: true },
    ] as any,
    getSessionLog: (id, limit) => {
      const all = LOGS[id] ?? []
      return limit === undefined ? all : all.slice(-limit)
    },
    getSessionActivity: (id) => [{ kind: "tool", seq: 1, session: id }],
    getSessionCommands: (id) => [{ name: `cmd-${id}` }],
    getSessionCommandsResolved: () => true,
    listArchivedWorkspaces: () => ARCHIVED as any,
    setMute: () => {},
    onSendFromWeb: () => {},
  }
  channel = new WebChannel(opts)
  await channel.start()
  return new DeviceStore(devicesFile).mint("test-device").token
}

async function snapshot(subscribe: object): Promise<any> {
  const token = await boot()
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
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

test("a plain subscribe gets every session's full log and no partialLogs", async () => {
  const frame = await snapshot({})
  expect(frame.logs).toEqual(LOGS)
  expect(frame.partialLogs).toBeUndefined()
})

test("logTail trims the sessions not listed in fullLogs and names them in partialLogs", async () => {
  const frame = await snapshot({ logTail: 1, fullLogs: ["b"] })
  expect(frame.logs).toEqual({ a: [{ id: "a3" }], b: LOGS.b })
  expect(frame.partialLogs).toEqual(["a"])
})

test("logTail with no fullLogs trims every session", async () => {
  const frame = await snapshot({ logTail: 2 })
  expect(frame.logs).toEqual({ a: [{ id: "a2" }, { id: "a3" }], b: LOGS.b })
  expect(frame.partialLogs).toEqual(["a", "b"])
})

test("a nonsense logTail falls back to full logs", async () => {
  for (const logTail of [0, -1, "3", null, 1.5]) {
    const frame = await snapshot({ logTail })
    expect(frame.logs).toEqual(LOGS)
    expect(frame.partialLogs).toBeUndefined()
    await channel!.stop(); channel = undefined
  }
})

test("trimExtras drops activity and commands for sessions not on screen and names them", async () => {
  const frame = await snapshot({ logTail: 1, fullLogs: ["b"], trimExtras: true })
  expect(Object.keys(frame.activity)).toEqual(["b"])
  expect(Object.keys(frame.commands)).toEqual(["b"])
  expect(Object.keys(frame.commandsResolved)).toEqual(["b"])
  expect(frame.partialExtras).toEqual(["a"])
})

test("trimExtras without logTail changes nothing", async () => {
  const frame = await snapshot({ trimExtras: true })
  expect(Object.keys(frame.activity)).toEqual(["a", "b"])
  expect(frame.partialExtras).toBeUndefined()
})

test("slimArchived strips views and layout from archived workspaces only", async () => {
  const slim = await snapshot({ slimArchived: true })
  expect(slim.archivedWorkspaces).toEqual([{ id: "w9", name: "old", status: "archived", workdir: "/w" }])
  await channel!.stop(); channel = undefined
  const full = await snapshot({})
  expect(full.archivedWorkspaces).toEqual(ARCHIVED)
})

test("GET /sessions/:id/chat-extras returns a session's activity and commands", async () => {
  const token = await boot()
  const res = await fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/a/chat-extras`, { headers: { authorization: `Bearer ${token}` } })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({
    activity: [{ kind: "tool", seq: 1, session: "a" }],
    commands: [{ name: "cmd-a" }],
    commandsResolved: true,
  })
})
