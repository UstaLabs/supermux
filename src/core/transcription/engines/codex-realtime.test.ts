import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import { codexRealtimeEngine, type RealtimeWs } from "./codex-realtime"

/**
 * Fake WS that only plays its script after the engine has attached the
 * "open" listener (mint is async, so a construction-time microtask races).
 */
function fakeWs(script: Array<Record<string, unknown>>): { ws: RealtimeWs; sent: string[] } {
  const sent: string[] = []
  const listeners: Record<string, Array<(ev: any) => void>> = {}
  let started = false
  const start = () => {
    if (started) return
    started = true
    queueMicrotask(() => {
      for (const l of listeners.open ?? []) l({})
      for (const msg of script) {
        for (const l of listeners.message ?? []) l({ data: JSON.stringify(msg) })
      }
    })
  }
  const ws: RealtimeWs = {
    readyState: 1,
    protocol: "realtime",
    send(data: string) { sent.push(data) },
    close() {},
    addEventListener(type, listener) {
      (listeners[type] ??= []).push(listener)
      if (type === "open") start()
    },
  }
  return { ws, sent }
}

test("isAvailable is false without auth token", () => {
  const e = codexRealtimeEngine({
    readFileFn: () => { throw new Error("missing") },
  })
  expect(e.isAvailable()).toBe(false)
  expect(e.name).toBe("codex-realtime")
  expect(e.prefersCleanup).toBe(false)
})

test("isAvailable is true with access_token", () => {
  const e = codexRealtimeEngine({
    readFileFn: () => JSON.stringify({ tokens: { access_token: "at" } }),
  })
  expect(e.isAvailable()).toBe(true)
})

test("transcribe mints, streams PCM, returns completed transcript", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at_test" } })
  let mintCalls = 0
  const { ws, sent } = fakeWs([
    { type: "session.created", session: { id: "s1" } },
    { type: "input_audio_buffer.committed", item_id: "i1" },
    {
      type: "conversation.item.input_audio_transcription.completed",
      transcript: "hello from realtime",
    },
  ])

  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: (p) => {
      if (p === "/fake/auth.json") return auth
      throw new Error(`unexpected read ${p}`)
    },
    spawn: (_cmd, args) => {
      const out = args[args.length - 1]!
      writeFileSync(out, Buffer.alloc(24000 * 2 * 0.2))
      return { exited: Promise.resolve(0) }
    },
    fetchFn: async (url, init) => {
      if (String(url).includes("client_secrets")) {
        mintCalls++
        expect(init?.method).toBe("POST")
        const headers = init?.headers as Record<string, string>
        expect(String(headers.Authorization ?? "")).toContain("at_test")
        return new Response(JSON.stringify({ value: "ek_test" }), { status: 200 })
      }
      throw new Error(`unexpected fetch ${url}`)
    },
    connectWs: (url, protocols) => {
      expect(url).toContain("intent=transcription")
      expect(protocols).toEqual(["realtime", "openai-insecure-api-key.ek_test"])
      return ws
    },
  })

  const r = await e.transcribe("/tmp/in.webm", { model: "gpt-4o-transcribe" })
  expect(r.text).toBe("hello from realtime")
  expect(r.prefersCleanup).toBe(false)
  expect(r.model).toBe("gpt-4o-transcribe")
  expect(mintCalls).toBe(1)
  expect(sent.some((s) => s.includes("input_audio_buffer.append"))).toBe(true)
  expect(sent.some((s) => s.includes("input_audio_buffer.commit"))).toBe(true)
})

test("ignores whisper model paths and uses default gpt-4o-transcribe", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at" } })
  let mintBody = ""
  const { ws } = fakeWs([
    { type: "conversation.item.input_audio_transcription.completed", transcript: "x" },
  ])
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: (p) => (p === "/fake/auth.json" ? auth : ""),
    spawn: (_c, args) => {
      writeFileSync(args[args.length - 1]!, Buffer.alloc(100))
      return { exited: Promise.resolve(0) }
    },
    fetchFn: async (_url, init) => {
      mintBody = String(init?.body ?? "")
      return new Response(JSON.stringify({ value: "ek" }), { status: 200 })
    },
    connectWs: () => ws,
  })
  await e.transcribe("/tmp/a.webm", { model: "/home/u/.cache/whisper-models/ggml-base.bin" })
  expect(mintBody).toContain("gpt-4o-transcribe")
  expect(mintBody).not.toContain("ggml-base")
})

test("surface mint errors", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at" } })
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: () => auth,
    spawn: (_c, args) => {
      writeFileSync(args[args.length - 1]!, Buffer.alloc(100))
      return { exited: Promise.resolve(0) }
    },
    fetchFn: async () => new Response("nope", { status: 403 }),
    connectWs: () => { throw new Error("should not connect") },
  })
  await expect(e.transcribe("/tmp/a.webm")).rejects.toThrow(/mint 403/)
})
