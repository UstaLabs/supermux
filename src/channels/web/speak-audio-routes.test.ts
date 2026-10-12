// POST /speak/start (authed) → id; GET /speak/audio/<id>.mp3 (NO auth — native media players
// cannot send the bearer header, so the id is the capability).
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { TtsAudioStreams } from "../../core/tts/audio-stream"
import type { TtsEngine } from "../../core/tts/tts-types"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }

const engine: TtsEngine = {
  name: "codex",
  isAvailable: () => true,
  speak: async () => { throw new Error("unused") },
  async speakStream(text) {
    async function* body() { yield new TextEncoder().encode(`mp3(${text})`) }
    return { mime: "audio/mpeg", body: body() }
  },
}

async function setup(): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "mux-speak-audio-"))
  const devicesFile = join(dir, "devices.json")
  const streams = new TtsAudioStreams()
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    speakAudio: {
      start: (input) => input.engine === "platform"
        ? { error: "platform", status: 400 }
        : { id: streams.start(engine, input.text) },
      open: (id) => streams.open(id),
    },
  }
  channel = new WebChannel(opts)
  await channel.start()
  return new DeviceStore(devicesFile).mint("test-device").token
}

test("start needs auth; the audio URL does not", async () => {
  const token = await setup()
  const unauthed = await fetch(`${base()}/speak/start`, { method: "POST", body: JSON.stringify({ text: "hi" }) })
  expect(unauthed.status).toBe(401)

  const res = await fetch(`${base()}/speak/start`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ text: "**Hello** there." }),
  })
  expect(res.status).toBe(200)
  const { url, mime } = await res.json() as { url: string; mime: string }
  expect(mime).toBe("audio/mpeg")
  expect(url).toMatch(/^\/speak\/audio\/[A-Za-z0-9_-]+\.mp3$/)

  // A player's Range probe gets the whole stream, and a second GET replays it.
  for (const headers of [{ range: "bytes=0-1" }, {}] as Record<string, string>[]) {
    const audio = await fetch(base() + url, { headers })
    expect(audio.status).toBe(200)
    expect(audio.headers.get("content-type")).toBe("audio/mpeg")
    expect(await audio.text()).toBe("mp3(Hello there.)")
  }
})

test("unknown or malformed ids are 404, and platform is refused", async () => {
  const token = await setup()
  expect((await fetch(`${base()}/speak/audio/abcdefghijklmnopqrstuvwx.mp3`)).status).toBe(404)
  expect((await fetch(`${base()}/speak/audio/..%2Fetc`)).status).toBe(404)
  const res = await fetch(`${base()}/speak/start`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ text: "hi", engine: "platform" }),
  })
  expect(res.status).toBe(400)
})
