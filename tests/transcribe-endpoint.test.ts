import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, mkdtempSync, rmSync, unlinkSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-transcribe-${process.pid}.json`
let ch: WebChannel
let token: string
let port: number
let tmpRoot: string
let oldHome: string | undefined
let transcribeCalls: Array<{ id: string | undefined; input: { draft?: string; audioPath?: string } }>

// `transcribe` is provided per-test so the "not configured" (503) case can omit it.
async function start(opts: Record<string, unknown>) {
  transcribeCalls = []
  ch = new WebChannel({
    port: 0,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...opts,
  } as any)
  await ch.start()
  port = (ch as any).boundPort as number
}

beforeEach(() => {
  __resetAuthFailures()
  tmpRoot = mkdtempSync(join(tmpdir(), "mux-transcribe-"))
  oldHome = process.env.HOME
  process.env.HOME = tmpRoot
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token
})

afterEach(async () => {
  await ch?.stop()
  if (oldHome === undefined) delete process.env.HOME
  else process.env.HOME = oldHome
  rmSync(tmpRoot, { recursive: true, force: true })
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

function authedJson(headers?: Record<string, string>) {
  return { Cookie: `cmux_token=${token}`, "content-type": "application/json", ...headers }
}

test("POST /sessions/:id/transcribe (JSON draft) → cleaned text + dep called with (id, {draft})", async () => {
  await start({
    transcribe: async (id: string, input: any) => {
      transcribeCalls.push({ id, input })
      return { text: "cleaned" }
    },
  })
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "cleaned" })
  expect(transcribeCalls).toEqual([{ id: "sess-1", input: { draft: "helo" } }])
})

test("POST /sessions/:id/transcribe with missing draft → 400", async () => {
  await start({
    transcribe: async () => ({ text: "should-not-run" }),
  })
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({}),
  })
  expect(res.status).toBe(400)
  expect(transcribeCalls).toHaveLength(0)
})

test("POST /sessions/:id/transcribe without auth → 401", async () => {
  await start({
    transcribe: async () => ({ text: "x" }),
  })
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(401)
})

test("POST /sessions/:id/transcribe with no transcribe dep → 503", async () => {
  await start({}) // transcribe omitted
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(503)
})

test("POST /sessions/:id/transcribe (multipart) → stores audio, passes audioPath to dep", async () => {
  const { FileStore } = await import("../src/core/files/store")
  const { openDb, runMigrations } = await import("../src/core/storage/db")
  const db = openDb(join(tmpRoot, "files.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const fileStore = new FileStore(db, join(tmpRoot, "files"))
  await start({
    fileStore,
    transcribe: async (id: string, input: any) => {
      transcribeCalls.push({ id, input })
      return { text: "from-audio" }
    },
  })
  const form = new FormData()
  form.set("audio", new Blob([new Uint8Array([1, 2, 3, 4])], { type: "audio/ogg" }), "clip.ogg")
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-2/transcribe`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}` },
    body: form,
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "from-audio" })
  expect(transcribeCalls).toHaveLength(1)
  expect(transcribeCalls[0]!.id).toBe("sess-2")
  // The dep gets a real on-disk path produced by the file store (no draft).
  expect(transcribeCalls[0]!.input.draft).toBeUndefined()
  expect(typeof transcribeCalls[0]!.input.audioPath).toBe("string")
  expect(existsSync(transcribeCalls[0]!.input.audioPath!)).toBe(true)
})

// ── Session-LESS transcribe (POST /transcribe) — the pre-spawn launcher path ──
// The session id is optional: it only enriches cleanup context, it's never required.

test("POST /transcribe (JSON draft, no session) → dep called with (undefined, {draft})", async () => {
  await start({
    transcribe: async (id: string | undefined, input: any) => {
      transcribeCalls.push({ id, input })
      return { text: "cleaned" }
    },
  })
  const res = await fetch(`http://127.0.0.1:${port}/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "cleaned" })
  expect(transcribeCalls).toEqual([{ id: undefined, input: { draft: "helo" } }])
})

test("POST /transcribe?session=sess-9 → optional id forwarded to dep", async () => {
  await start({
    transcribe: async (id: string | undefined, input: any) => {
      transcribeCalls.push({ id, input })
      return { text: "cleaned" }
    },
  })
  const res = await fetch(`http://127.0.0.1:${port}/transcribe?session=sess-9`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({ draft: "hi" }),
  })
  expect(res.status).toBe(200)
  expect(transcribeCalls).toEqual([{ id: "sess-9", input: { draft: "hi" } }])
})

test("POST /transcribe with missing draft → 400", async () => {
  await start({ transcribe: async () => ({ text: "should-not-run" }) })
  const res = await fetch(`http://127.0.0.1:${port}/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({}),
  })
  expect(res.status).toBe(400)
  expect(transcribeCalls).toHaveLength(0)
})

test("POST /transcribe without auth → 401", async () => {
  await start({ transcribe: async () => ({ text: "x" }) })
  const res = await fetch(`http://127.0.0.1:${port}/transcribe`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(401)
})

test("POST /transcribe (multipart, no session) → stores audio with no session, passes audioPath", async () => {
  const { FileStore } = await import("../src/core/files/store")
  const { openDb, runMigrations } = await import("../src/core/storage/db")
  const db = openDb(join(tmpRoot, "files.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const fileStore = new FileStore(db, join(tmpRoot, "files"))
  await start({
    fileStore,
    transcribe: async (id: string | undefined, input: any) => {
      transcribeCalls.push({ id, input })
      return { text: "from-audio" }
    },
  })
  const form = new FormData()
  form.set("audio", new Blob([new Uint8Array([1, 2, 3, 4])], { type: "audio/ogg" }), "clip.ogg")
  const res = await fetch(`http://127.0.0.1:${port}/transcribe`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}` },
    body: form,
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "from-audio" })
  expect(transcribeCalls).toHaveLength(1)
  expect(transcribeCalls[0]!.id).toBeUndefined()
  expect(typeof transcribeCalls[0]!.input.audioPath).toBe("string")
  expect(existsSync(transcribeCalls[0]!.input.audioPath!)).toBe(true)
})

// ── Error surface + relay-origin CSRF ────────────────────────────────────────
// Uncaught throws used to become Bun's opaque 500 "Something went wrong!" —
// the "instant 500" clients saw for voice over the relay when STT was
// unavailable (no Codex login + no whisper-cli).

test("POST /transcribe STT-unavailable throw → 503 JSON error (not opaque 500)", async () => {
  await start({
    transcribe: async () => {
      throw new Error("stt fallback unavailable: whisper")
    },
  })
  const res = await fetch(`http://127.0.0.1:${port}/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(503)
  const body = await res.json() as { error: string }
  expect(body.error).toContain("unavailable")
  // Must not be Bun's default body for uncaught handler errors.
  expect(body.error).not.toBe("Something went wrong!")
})

test("POST /sessions/:id/transcribe STT engine throw → 502 JSON error", async () => {
  await start({
    transcribe: async () => {
      throw new Error("codex-realtime: mint 500 boom")
    },
  })
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: authedJson(),
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(502)
  expect(await res.json()).toEqual({ error: "codex-realtime: mint 500 boom" })
})

test("POST /sessions/:id/transcribe accepts cookie + relay Origin when getRelayUrl is set", async () => {
  const relayUrl = "https://h-abc.relay.supermux.dev"
  await start({
    publicUrl: "http://localhost:8787",
    getRelayUrl: () => relayUrl,
    transcribe: async (_id: string | undefined, input: any) => ({ text: input.draft }),
  })
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      Origin: relayUrl,
      "content-type": "application/json",
    },
    body: JSON.stringify({ draft: "via-relay" }),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "via-relay" })
})

test("POST /transcribe (id-less) accepts cookie + relay Origin when getRelayUrl is set", async () => {
  // /transcribe is in API_PREFIXES so CSRF applies; relay origin must be trusted.
  const relayUrl = "https://h-abc.relay.supermux.dev"
  await start({
    publicUrl: "http://localhost:8787",
    getRelayUrl: () => relayUrl,
    transcribe: async (_id: string | undefined, input: any) => ({ text: input.draft }),
  })
  const res = await fetch(`http://127.0.0.1:${port}/transcribe`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      Origin: relayUrl,
      "content-type": "application/json",
    },
    body: JSON.stringify({ draft: "launcher" }),
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ text: "launcher" })
})

test("POST /sessions/:id/transcribe rejects cookie + foreign Origin", async () => {
  await start({
    publicUrl: "http://localhost:8787",
    getRelayUrl: () => "https://h-abc.relay.supermux.dev",
    transcribe: async () => ({ text: "nope" }),
  })
  const res = await fetch(`http://127.0.0.1:${port}/sessions/sess-1/transcribe`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      Origin: "https://evil.example",
      "content-type": "application/json",
    },
    body: JSON.stringify({ draft: "helo" }),
  })
  expect(res.status).toBe(403)
})
