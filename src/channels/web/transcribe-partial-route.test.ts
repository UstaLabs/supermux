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

test("an incremental pass forwards fromMs and previousText", async () => {
  let seen: { fromMs?: number; previousText?: string } | undefined
  const token = await setup({
    transcribe: async (_id, input) => { seen = input; return { text: "more", fromMs: 1200, durationMs: 3000 } },
  })
  const form = audioForm()
  form.append("fromMs", "1200")
  form.append("previousText", "so far")
  const res = await fetch(`${base()}/transcribe?partial=1`, { method: "POST", headers: { authorization: `Bearer ${token}` }, body: form })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "more", fromMs: 1200, durationMs: 3000 })
  expect(seen?.fromMs).toBe(1200)
  expect(seen?.previousText).toBe("so far")
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

test("POST /transcribe/edit forwards text + newWords and answers the edit (null on failure)", async () => {
  let seen: { text: string; newWords: string } | undefined
  let fail = false
  const token = await setup({
    dictationEdit: async (input) => { seen = input; if (fail) throw new Error("luna down"); return { text: "Meet on Tuesday." } },
  })
  const post = () => fetch(`${base()}/transcribe/edit`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ text: "Meet on Monday.", newWords: "make it Tuesday" }),
  })
  const ok = await post()
  expect(await ok.json()).toEqual({ text: "Meet on Tuesday." })
  expect(seen).toEqual({ text: "Meet on Monday.", newWords: "make it Tuesday" })
  fail = true
  expect(await (await post()).json()).toEqual({ text: null })
})

test("a pass forwards its dictation id, and the last one says it is final", async () => {
  const seen: Array<{ dictationId?: string; final?: boolean }> = []
  const token = await setup({ transcribe: async (_id, input) => { seen.push(input); return { text: "x" } } })
  for (const final of [false, true]) {
    const form = audioForm()
    form.append("dictationId", "rec-1")
    if (final) form.append("final", "1")
    const res = await fetch(`${base()}/transcribe?partial=1`, { method: "POST", headers: { authorization: `Bearer ${token}` }, body: form })
    expect(res.status).toBe(200)
  }
  expect(seen.map((s) => [s.dictationId, s.final ?? false])).toEqual([["rec-1", false], ["rec-1", true]])
})

test("POST /transcribe/warm starts warming and answers at once", async () => {
  const warmed: string[] = []
  let release!: () => void
  const token = await setup({
    transcribeWarm: (id) => { warmed.push(id); return new Promise<boolean>((r) => { release = () => r(true) }) },
  })
  const res = await fetch(`${base()}/transcribe/warm`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ dictationId: "rec-1" }),
  })
  expect(res.status).toBe(202)
  expect(warmed).toEqual(["rec-1"])
  release()
})

test("POST /transcribe/warm needs a dictation id", async () => {
  const token = await setup({ transcribeWarm: async () => true })
  const res = await fetch(`${base()}/transcribe/warm`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: "{}",
  })
  expect(res.status).toBe(400)
})
