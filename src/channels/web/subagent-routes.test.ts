// POST /sessions/:id/subagents/:subagentId/{message,stop} and the snapshot's `subagents` map,
// over real HTTP/WS against a booted WebChannel.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
const base = () => `http://127.0.0.1:${channel!.boundPort}`

function boot(opts: Partial<WebChannelOpts>): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-subagent-routes-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [{ id: "s1", name: "alpha", workdir: "/w", mute: false, connected: true, agent: "claude" }],
    getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    ...opts,
  })
  return new DeviceStore(devicesFile).mint("test-device").token
}

const post = (token: string, path: string, body?: unknown) => fetch(`${base()}${path}`, {
  method: "POST",
  headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
})

test("message route hands text to messageSubagent and returns via", async () => {
  const calls: unknown[] = []
  const token = boot({ messageSubagent: async (...args) => { calls.push(args); return { ok: true, via: "relay" } } })
  await channel!.start()
  const res = await post(token, "/sessions/s1/subagents/a%2F1/message", { text: "  hi there " })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: true, via: "relay" })
  expect(calls).toEqual([["s1", "a/1", "hi there"]])
})

test("message route: empty text is 400, unsupported is 409 with the error", async () => {
  const token = boot({ messageSubagent: async () => ({ ok: false, status: 409, error: "codex v2 refuses direct input" }) })
  await channel!.start()
  expect((await post(token, "/sessions/s1/subagents/a1/message", { text: " " })).status).toBe(400)
  const res = await post(token, "/sessions/s1/subagents/a1/message", { text: "hi" })
  expect(res.status).toBe(409)
  expect(await res.json()).toEqual({ ok: false, error: "codex v2 refuses direct input" })
})

test("stop route calls stopSubagent", async () => {
  const calls: unknown[] = []
  const token = boot({ stopSubagent: async (...args) => { calls.push(args); return { ok: true } } })
  await channel!.start()
  const res = await post(token, "/sessions/s1/subagents/a1/stop")
  expect(res.status).toBe(200)
  expect(calls).toEqual([["s1", "a1"]])
})

test("snapshot carries subagents per session", async () => {
  const sub = { id: "a1", status: "running", stats: {}, startedAt: 1, lastActivityAt: 1, parentCallId: "toolu_1" }
  const token = boot({ getSessionSubagents: (id) => (id === "s1" ? [sub] : []) })
  await channel!.start()
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
  const snapshot = await new Promise<Record<string, unknown>>((resolve, reject) => {
    ws.onopen = () => ws.send(JSON.stringify({ type: "subscribe" }))
    ws.onmessage = (m) => {
      const frame = JSON.parse(String(m.data))
      if (frame.type === "snapshot") resolve(frame)
    }
    ws.onerror = (e) => reject(e)
    setTimeout(() => reject(new Error("no snapshot")), 3000)
  })
  ws.close()
  expect(snapshot.subagents).toEqual({ s1: [sub] })
})
