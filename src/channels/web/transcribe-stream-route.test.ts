// /transcribe/stream — a streamed dictation over a real socket: binary frames are mic audio, text
// frames are JSON control messages, and what the stream sends comes back as JSON text frames.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
const http = () => `http://127.0.0.1:${channel!.boundPort}`
const ws = () => `ws://127.0.0.1:${channel!.boundPort}`

async function setup(extra: Partial<WebChannelOpts>): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "mux-transcribe-stream-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...extra,
  })
  await channel.start()
  return new DeviceStore(devicesFile).mint("test-device").token
}

/** A fake stream that records what reached it and answers each control message. */
function fakeStream() {
  const seen = { opened: [] as { dictationId: string; sessionId?: string }[], audio: [] as number[], control: [] as unknown[], closed: 0 }
  const open: NonNullable<WebChannelOpts["openDictationStream"]> = ({ dictationId, sessionId, send, close }) => {
    seen.opened.push({ dictationId, ...(sessionId ? { sessionId } : {}) })
    return {
      audio: (b) => { seen.audio.push(...b); send({ type: "text", text: `heard ${seen.audio.length} bytes` }) },
      control: (m) => {
        seen.control.push(m)
        if (m.type === "stop") { send({ type: "final", text: "all done" }); send({ type: "done" }); close() }
      },
      closed: () => { seen.closed++ },
    }
  }
  return { seen, open }
}

function nextMessage(sock: WebSocket): Promise<any> {
  return new Promise((resolve, reject) => {
    sock.addEventListener("message", (e) => resolve(JSON.parse(String(e.data))), { once: true })
    sock.addEventListener("close", () => reject(new Error("closed")), { once: true })
  })
}

test("needs a device token and a dictation id", async () => {
  const { open } = fakeStream()
  const token = await setup({ openDictationStream: open })
  expect((await fetch(`${http()}/transcribe/stream?dictationId=d1`)).status).toBe(401)
  const res = await fetch(`${http()}/transcribe/stream`, { headers: { authorization: `Bearer ${token}`, upgrade: "websocket" } })
  expect(res.status).toBe(400)
})

test("audio and control reach the stream; its events come back; Stop ends with the socket closed", async () => {
  const { seen, open } = fakeStream()
  const token = await setup({ openDictationStream: open })
  const sock = new WebSocket(`${ws()}/transcribe/stream?dictationId=d1&session=s1`, { headers: { authorization: `Bearer ${token}` } } as any)
  await new Promise((r) => sock.addEventListener("open", r, { once: true }))
  expect(seen.opened).toEqual([{ dictationId: "d1", sessionId: "s1" }])

  const first = nextMessage(sock)
  sock.send(new Uint8Array([1, 2, 3, 4]))
  expect(await first).toEqual({ type: "text", text: "heard 4 bytes" })
  expect(seen.audio).toEqual([1, 2, 3, 4])

  const events: any[] = []
  sock.addEventListener("message", (e) => events.push(JSON.parse(String(e.data))))
  const closed = new Promise((r) => sock.addEventListener("close", r, { once: true }))
  sock.send(JSON.stringify({ type: "edit", text: "Mine." }))
  sock.send("not json")
  sock.send(JSON.stringify({ type: "stop" }))
  await closed
  expect(seen.control).toEqual([{ type: "edit", text: "Mine." }, { type: "stop" }])
  expect(events).toEqual([{ type: "final", text: "all done" }, { type: "done" }])
  await Bun.sleep(20)
  expect(seen.closed).toBe(1)
})

test("a client that goes away is reported as closed", async () => {
  const { seen, open } = fakeStream()
  const token = await setup({ openDictationStream: open })
  const sock = new WebSocket(`${ws()}/transcribe/stream?dictationId=d2`, { headers: { authorization: `Bearer ${token}` } } as any)
  await new Promise((r) => sock.addEventListener("open", r, { once: true }))
  sock.close()
  for (let i = 0; i < 50 && seen.closed === 0; i++) await Bun.sleep(10)
  expect(seen.closed).toBe(1)
})

test("/settings/config says whether the apps may stream", async () => {
  const { open } = fakeStream()
  const caps = () => ({ codexAvailable: true, livePartials: true, streaming: true })
  const getAppConfig = () => ({ telegramBotToken: "", claudeOauthToken: "", anthropicApiKey: "", codexApiKey: "", cursorApiKey: "", whatsappGowaBasicAuth: "", whatsappWebhookSecret: "" }) as any
  let token = await setup({ openDictationStream: open, voiceCaps: caps, getAppConfig })
  let cfg = await (await fetch(`${http()}/settings/config`, { headers: { authorization: `Bearer ${token}` } })).json() as Record<string, unknown>
  expect(cfg.voiceStreaming).toBe(true)
  await channel!.stop()
  token = await setup({ voiceCaps: caps, getAppConfig })
  cfg = await (await fetch(`${http()}/settings/config`, { headers: { authorization: `Bearer ${token}` } })).json() as Record<string, unknown>
  expect(cfg.voiceStreaming).toBe(false)
})
