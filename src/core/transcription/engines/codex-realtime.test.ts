import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { encodeWav16 } from "../wav"
import { codexRealtimeEngine, continuationPrompt, transcriptionHints, type RealtimeWs } from "./codex-realtime"

/** A real WAV clip of [ms] milliseconds at 16 kHz — the format every client uploads. */
function clip(ms: number, name = "clip"): string {
  const p = join(tmpdir(), `${name}-${process.pid}-${ms}.wav`)
  writeFileSync(p, encodeWav16(new Uint8Array((16000 * ms / 1000) * 2), 16000))
  return p
}

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

  const r = await e.transcribe(clip(200), { model: "gpt-4o-transcribe" })
  expect(r.text).toBe("hello from realtime")
  expect(r.prefersCleanup).toBe(false)
  expect(r.model).toBe("gpt-4o-transcribe")
  expect(mintCalls).toBe(1)
  expect(sent.some((s) => s.includes("input_audio_buffer.append"))).toBe(true)
  expect(sent.some((s) => s.includes("input_audio_buffer.commit"))).toBe(true)
})

test("ignores whisper model paths and uses default gpt-transcribe", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at" } })
  let mintBody = ""
  const { ws } = fakeWs([
    { type: "conversation.item.input_audio_transcription.completed", transcript: "x" },
  ])
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: (p) => (p === "/fake/auth.json" ? auth : ""),
    fetchFn: async (_url, init) => {
      mintBody = String(init?.body ?? "")
      return new Response(JSON.stringify({ value: "ek" }), { status: 200 })
    },
    connectWs: () => ws,
  })
  await e.transcribe(clip(10), { model: "/home/u/.cache/whisper-models/ggml-base.bin" })
  expect(mintBody).toContain("gpt-transcribe")
  expect(mintBody).not.toContain("ggml-base")
})

test("surface mint errors", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at" } })
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: () => auth,
    fetchFn: async () => new Response("nope", { status: 403 }),
    connectWs: () => { throw new Error("should not connect") },
  })
  await expect(e.transcribe(clip(10))).rejects.toThrow(/mint 403/)
})

test("transcriptionHints: several languages → prompt only, no language pin", () => {
  const h = transcriptionHints({ languages: ["tr", "en"], keyterms: ["Supermux", "Kotlin"] })
  expect(h.language).toBeUndefined()
  expect(h.prompt).toContain("Turkish and English")
  expect(h.prompt).toContain("Supermux, Kotlin")
})

test("transcriptionHints: one language → pin + prompt; glossary always included", () => {
  const h = transcriptionHints({ languages: ["tr-TR"], keyterms: ["Codex", "codex", " "] })
  expect(h.language).toBe("tr")
  expect(h.prompt).toContain("Turkish")
  expect(h.prompt).toContain("Vocabulary, spelled exactly: Codex.")
})

test("transcriptionHints: falls back to lang; auto with no glossary sends nothing", () => {
  expect(transcriptionHints({ lang: "en" }).language).toBe("en")
  expect(transcriptionHints({ lang: "auto" })).toEqual({})
  const glossaryOnly = transcriptionHints({ keyterms: ["Supermux"] })
  expect(glossaryOnly.language).toBeUndefined()
  expect(glossaryOnly.prompt).toBe("Vocabulary, spelled exactly: Supermux.")
})

test("mint body carries the language hints", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at" } })
  let mintBody = ""
  const { ws } = fakeWs([
    { type: "conversation.item.input_audio_transcription.completed", transcript: "x" },
  ])
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: (p) => (p === "/fake/auth.json" ? auth : ""),
    fetchFn: async (_url, init) => {
      mintBody = String(init?.body ?? "")
      return new Response(JSON.stringify({ value: "ek" }), { status: 200 })
    },
    connectWs: () => ws,
  })
  await e.transcribe(clip(10), { languages: ["tr", "en"], keyterms: ["Supermux"] })
  const t = JSON.parse(mintBody).session.audio.input.transcription
  expect(t.model).toBe("gpt-transcribe")
  expect(t.language).toBeUndefined()
  expect(t.prompt).toContain("Turkish and English")
  expect(t.prompt).toContain("Supermux")
})

test("continuationPrompt: carries the earlier transcript after the usual hints", () => {
  expect(continuationPrompt("The speaker talks in Turkish.", "")).toBe("The speaker talks in Turkish.")
  const p = continuationPrompt("The speaker talks in Turkish.", "  Merhaba   dünya. ")!
  expect(p.startsWith("The speaker talks in Turkish. ")).toBe(true)
  expect(p).toContain('"Merhaba dünya."')
  expect(p).toContain("do not repeat it")
  const long = continuationPrompt(undefined, "x".repeat(5_000))!
  expect(long.length).toBeLessThan(1_300)
})

test("an incremental pass sends only the audio after fromMs and reports the whole length", async () => {
  const auth = JSON.stringify({ tokens: { access_token: "at_test" } })
  const { ws, sent } = fakeWs([
    { type: "input_audio_buffer.committed", item_id: "i1" },
    { type: "conversation.item.input_audio_transcription.completed", transcript: "the new words" },
  ])
  let mintBody = ""
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: () => auth,
    fetchFn: async (_url, init) => { mintBody = String(init?.body); return new Response(JSON.stringify({ value: "ek" }), { status: 200 }) },
    connectWs: () => ws,
  })
  const r = await e.transcribe(clip(2_000), { fromMs: 1_500, previousText: "earlier words" })
  expect(r.text).toBe("the new words")
  expect(r.durationMs).toBe(2_000)
  expect(r.fromMs).toBe(1_500)
  expect(mintBody).toContain("earlier words")
  // Only 0.5 s of audio went over the socket.
  const appended = sent
    .map((s) => JSON.parse(s))
    .filter((m) => m.type === "input_audio_buffer.append")
    .reduce((n, m) => n + Buffer.from(m.audio, "base64").length, 0)
  expect(appended).toBe(24000 * 2 * 0.5)
})
