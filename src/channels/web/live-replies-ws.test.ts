// Card #76: live text through the real channel. Only an app that asked (`subscribe.streams`) and shows the
// chat gets reply_stream frames; an old app gets none; a reconnect gets open streams in its snapshot.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot(): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "mux-live-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [{ id: "a", name: "alpha", workdir: "/w", mute: false, connected: true }] as any,
    getSessionLog: () => [],
    getSessionActivity: () => [],
    getSessionCommands: () => [],
    getSessionCommandsResolved: () => true,
    setMute: () => {},
    onSendFromWeb: () => {},
  }
  channel = new WebChannel(opts)
  await channel.start()
  return new DeviceStore(devicesFile).mint("test-device").token
}

type Client = { ws: WebSocket; frames: any[]; snapshot: Promise<any> }

async function connect(token: string, subscribe: object): Promise<Client> {
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws`, { headers: { Authorization: `Bearer ${token}` } } as any)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const frames: any[] = []
  let gotSnapshot!: (f: any) => void
  const snapshot = new Promise<any>((r) => { gotSnapshot = r })
  ws.onmessage = (e) => {
    const f = JSON.parse(String(e.data))
    if (f.type === "snapshot") gotSnapshot(f)
    else frames.push(f)
  }
  ws.send(JSON.stringify({ type: "subscribe", ...subscribe }))
  await snapshot
  return { ws, frames, snapshot }
}

async function until(check: () => boolean, ms = 2000): Promise<void> {
  const end = Date.now() + ms
  while (!check()) {
    if (Date.now() > end) throw new Error("timed out")
    await Bun.sleep(10)
  }
}

const live = (c: Client) => c.frames.filter((f) => f.type === "reply_stream" || f.type === "reply_stream_end")

test("an app that asked and shows the chat gets the reply as it is written; an old app gets nothing", async () => {
  const token = await boot()
  const app = await connect(token, { streams: 1 })
  const old = await connect(token, {})
  app.ws.send(JSON.stringify({ type: "viewing", sessions: ["a"], visible: true }))
  old.ws.send(JSON.stringify({ type: "viewing", sessions: ["a"], visible: true }))
  await Bun.sleep(30)
  channel!.liveAppend("a", "k1", "reply", "Hel")
  channel!.liveAppend("a", "k1", "reply", "lo")
  await until(() => live(app).length >= 2)
  expect(live(app)).toEqual([
    { type: "reply_stream", session: "a", stream: "k1", at: 0, text: "Hel" },
    { type: "reply_stream", session: "a", stream: "k1", at: 3, text: "lo" },
  ])
  channel!.liveClose("k1", false)
  await until(() => live(app).length >= 3)
  expect(live(app)[2]).toEqual({ type: "reply_stream_end", session: "a", stream: "k1" })
  await Bun.sleep(80)
  expect(live(old)).toEqual([])
  app.ws.close(); old.ws.close()
})

test("a chat off screen gets nothing until it is shown, then the text so far", async () => {
  const token = await boot()
  const app = await connect(token, { streams: 1 })
  app.ws.send(JSON.stringify({ type: "viewing", session: null, visible: true }))
  await Bun.sleep(30)
  channel!.liveAppend("a", "k1", "reply", "one two")
  await Bun.sleep(80)
  expect(live(app)).toEqual([])
  app.ws.send(JSON.stringify({ type: "viewing", session: "a", visible: true }))
  await until(() => live(app).length >= 1)
  expect(live(app)[0]).toEqual({ type: "reply_stream", session: "a", stream: "k1", at: 0, text: "one two" })
  // Leaving the chat (single-chat form, visible: false) stops the frames.
  app.ws.send(JSON.stringify({ type: "viewing", session: "a", visible: false }))
  await Bun.sleep(30)
  channel!.liveAppend("a", "k1", "reply", " three")
  await Bun.sleep(80)
  expect(live(app).length).toBe(1)
  app.ws.close()
})

test("a reconnect gets the open streams of its on-screen chats in the snapshot", async () => {
  const token = await boot()
  channel!.liveAppend("a", "k1", "reply", "so far")
  channel!.liveAppend("a", "t1", "thinking", "hm")
  const app = await connect(token, { streams: 1, logTail: 1, fullLogs: ["a"] })
  const snap = await app.snapshot
  expect(snap.streams).toEqual({ a: [{ stream: "k1", kind: "reply", text: "so far" }, { stream: "t1", kind: "thinking", text: "hm" }] })
  const old = await connect(token, { logTail: 1, fullLogs: ["a"] })
  expect((await old.snapshot).streams).toBeUndefined()
  app.ws.close(); old.ws.close()
})
