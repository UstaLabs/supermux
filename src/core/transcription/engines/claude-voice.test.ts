import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import {
  claudeVoiceEngine,
  sanitizeKeytermsForHeader,
  type VoiceWs,
} from "./claude-voice"

/**
 * Fake WS that fires "open" after the open listener is attached, then delivers
 * a script of JSON messages. Captures binary + string sends.
 */
function fakeWs(script: Array<Record<string, unknown>>): {
  ws: VoiceWs
  sent: Array<string | Buffer>
  headers?: Record<string, string>
} {
  const sent: Array<string | Buffer> = []
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
  const ws: VoiceWs = {
    readyState: 1,
    send(data) {
      if (typeof data === "string") sent.push(data)
      else if (Buffer.isBuffer(data)) sent.push(data)
      else if (data instanceof ArrayBuffer) sent.push(Buffer.from(data))
      else sent.push(Buffer.from(data as ArrayBufferView as any))
    },
    close() {},
    addEventListener(type, listener) {
      ;(listeners[type] ??= []).push(listener)
      if (type === "open") start()
    },
  }
  return { ws, sent }
}

const auth = JSON.stringify({
  claudeAiOauth: {
    accessToken: "sk-ant-oat01-test",
    refreshToken: "refresh-test",
    expiresAt: Date.now() + 3600_000,
  },
})

test("isAvailable is false without accessToken", () => {
  const e = claudeVoiceEngine({
    readFileFn: () => { throw new Error("missing") },
  })
  expect(e.isAvailable()).toBe(false)
  expect(e.name).toBe("claude-voice")
  expect(e.prefersCleanup).toBe(false)
})

test("isAvailable is true with claudeAiOauth.accessToken", () => {
  const e = claudeVoiceEngine({
    readFileFn: () => auth,
  })
  expect(e.isAvailable()).toBe(true)
})

test("sanitizeKeytermsForHeader joins and caps terms", () => {
  expect(sanitizeKeytermsForHeader([])).toBeUndefined()
  expect(sanitizeKeytermsForHeader(["  Supermux ", "", "codex"])).toBe("Supermux,codex")
  const long = Array.from({ length: 50 }, (_, i) => `term${i}-${"x".repeat(40)}`)
  const h = sanitizeKeytermsForHeader(long)!
  expect(h.length).toBeLessThanOrEqual(512)
})

test("transcribe streams PCM, sends KeepAlive/CloseStream, returns final transcript", async () => {
  const { ws, sent } = fakeWs([
    { type: "TranscriptInterim", data: "hello from " },
    { type: "TranscriptText", data: "hello from claude" },
    { type: "TranscriptEndpoint" },
  ])
  let connectedUrl = ""
  let connectedHeaders: Record<string, string> = {}

  const e = claudeVoiceEngine({
    credsPath: "/fake/creds.json",
    readFileFn: (p) => {
      if (p === "/fake/creds.json") return auth
      throw new Error(`unexpected read ${p}`)
    },
    spawn: (_cmd, args) => {
      const out = args[args.length - 1]!
      writeFileSync(out, Buffer.alloc(16_000 * 2 * 0.2)) // 200ms PCM
      return { exited: Promise.resolve(0) }
    },
    sleep: async () => {},
    paceFactor: 0,
    connectWs: (url, opts) => {
      connectedUrl = url
      connectedHeaders = opts.headers
      return ws
    },
  })

  const r = await e.transcribe("/tmp/in.webm", {
    lang: "en-US",
    keyterms: ["Supermux", "codex-realtime"],
  })
  expect(r.text).toBe("hello from claude")
  expect(r.prefersCleanup).toBe(false)
  expect(r.model).toBe("deepgram-nova3")

  expect(connectedUrl).toContain("wss://api.anthropic.com/api/ws/speech_to_text/voice_stream")
  expect(connectedUrl).toContain("encoding=linear16")
  expect(connectedUrl).toContain("sample_rate=16000")
  expect(connectedUrl).toContain("stt_provider=deepgram-nova3")
  expect(connectedUrl).toContain("language=en")
  expect(connectedHeaders.Authorization).toBe("Bearer sk-ant-oat01-test")
  expect(connectedHeaders["x-app"]).toBe("cli")
  expect(connectedHeaders["x-config-keyterms"]).toBe("Supermux,codex-realtime")

  const strings = sent.filter((s): s is string => typeof s === "string")
  expect(strings.some((s) => s.includes("KeepAlive"))).toBe(true)
  expect(strings.some((s) => s.includes("CloseStream"))).toBe(true)
  expect(sent.some((s) => Buffer.isBuffer(s) || (typeof s !== "string" && (s as any).length))).toBe(true)
})

test("promotes pending interim when stream closes without TranscriptEndpoint", async () => {
  const sent: Array<string | Buffer> = []
  const listeners: Record<string, Array<(ev: any) => void>> = {}
  let started = false
  const ws: VoiceWs = {
    readyState: 1,
    send(data) {
      if (typeof data === "string") sent.push(data)
      else sent.push(Buffer.from(data as any))
      // After CloseStream, simulate server close with only an interim delivered.
      if (typeof data === "string" && data.includes("CloseStream")) {
        queueMicrotask(() => {
          for (const l of listeners.message ?? []) {
            l({ data: JSON.stringify({ type: "TranscriptInterim", data: "partial only" }) })
          }
          for (const l of listeners.close ?? []) l({ code: 1000, reason: "" })
        })
      }
    },
    close() {},
    addEventListener(type, listener) {
      ;(listeners[type] ??= []).push(listener)
      if (type === "open" && !started) {
        started = true
        queueMicrotask(() => {
          for (const l of listeners.open ?? []) l({})
        })
      }
    },
  }

  const e = claudeVoiceEngine({
    credsPath: "/fake/creds.json",
    readFileFn: () => auth,
    spawn: (_c, args) => {
      writeFileSync(args[args.length - 1]!, Buffer.alloc(100))
      return { exited: Promise.resolve(0) }
    },
    sleep: async () => {},
    paceFactor: 0,
    connectWs: () => ws,
  })
  const r = await e.transcribe("/tmp/a.webm")
  expect(r.text).toBe("partial only")
})

test("surfaces TranscriptError", async () => {
  const { ws } = fakeWs([
    { type: "TranscriptError", data: "no speech" },
  ])
  const e = claudeVoiceEngine({
    credsPath: "/fake/creds.json",
    readFileFn: () => auth,
    spawn: (_c, args) => {
      writeFileSync(args[args.length - 1]!, Buffer.alloc(100))
      return { exited: Promise.resolve(0) }
    },
    sleep: async () => {},
    paceFactor: 0,
    connectWs: () => ws,
  })
  await expect(e.transcribe("/tmp/a.webm")).rejects.toThrow(/TranscriptError/)
})

test("ffmpeg failure throws", async () => {
  const e = claudeVoiceEngine({
    credsPath: "/fake/creds.json",
    readFileFn: () => auth,
    spawn: () => ({ exited: Promise.resolve(1) }),
    connectWs: () => { throw new Error("should not connect") },
  })
  await expect(e.transcribe("/tmp/a.webm")).rejects.toThrow(/ffmpeg failed/)
})
