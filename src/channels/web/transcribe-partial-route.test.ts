// POST /transcribe?partial=1 — the live dictation pass: audio goes to a scratch file that is gone
// once the answer is back, and the pipeline is told it is a partial. GET /settings/config carries
// the probed voice capabilities the client picks its default dictation mode from.
import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }

async function setup(extra: Partial<WebChannelOpts>): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "mux-transcribe-partial-"))
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

function audioForm(): FormData {
  const form = new FormData()
  form.append("audio", new Blob([new Uint8Array([1, 2, 3])], { type: "audio/wav" }), "live.wav")
  return form
}

test("a partial pass is flagged, uses a scratch file, and the file is removed afterwards", async () => {
  let seen: { audioPath?: string; partial?: boolean } | undefined
  let existedDuring = false
  const token = await setup({
    transcribe: async (_id, input) => {
      seen = input
      existedDuring = !!input.audioPath && existsSync(input.audioPath)
      return { text: "hello so far" }
    },
  })
  const res = await fetch(`${base()}/transcribe?partial=1`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
    body: audioForm(),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "hello so far" })
  expect(seen?.partial).toBe(true)
  expect(seen?.audioPath?.endsWith(".wav")).toBe(true)
  expect(existedDuring).toBe(true)
  expect(existsSync(seen!.audioPath!)).toBe(false)
})

test("the scratch file is removed even when the pass fails", async () => {
  let path: string | undefined
  const token = await setup({
    transcribe: async (_id, input) => { path = input.audioPath; throw new Error("socket closed") },
  })
  const res = await fetch(`${base()}/sessions/s1/transcribe?partial=1`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
    body: audioForm(),
  })
  expect(res.status).toBe(502)
  expect(path).toBeDefined()
  expect(existsSync(path!)).toBe(false)
})

test("a non-partial upload is not flagged partial", async () => {
  let partial: boolean | undefined = true
  const token = await setup({
    transcribe: async (_id, input) => { partial = input.partial; return { text: "x" } },
  })
  const res = await fetch(`${base()}/transcribe`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ draft: "x" }),
  })
  expect(res.status).toBe(200)
  expect(partial).toBeUndefined()
})

test("/settings/config carries the probed voice capabilities", async () => {
  const token = await setup({
    getAppConfig: () => ({ telegramBotToken: "", claudeOauthToken: "", anthropicApiKey: "", codexApiKey: "", cursorApiKey: "", whatsappGowaBasicAuth: "", whatsappWebhookSecret: "" }) as any,
    voiceCaps: () => ({ codexAvailable: true, livePartials: false }),
  })
  const res = await fetch(`${base()}/settings/config`, { headers: { authorization: `Bearer ${token}` } })
  expect(res.status).toBe(200)
  const body = await res.json() as Record<string, unknown>
  expect(body.voiceCodexAvailable).toBe(true)
  expect(body.voiceLivePartials).toBe(false)
})
