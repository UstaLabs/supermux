import { test, expect } from "bun:test"
import { selectStt, runStt, STT_ENGINES, FALLBACK_STT_ENGINE } from "./stt"
import type { SttEngine } from "./stt-types"

function fakeEngine(name: string, opts: {
  available?: boolean
  text?: string
  prefersCleanup?: boolean
  fail?: boolean
} = {}): SttEngine {
  return {
    name,
    prefersCleanup: opts.prefersCleanup ?? true,
    isAvailable: () => opts.available !== false,
    async transcribe() {
      if (opts.fail) throw new Error(`${name} boom`)
      return { text: opts.text ?? `from-${name}`, prefersCleanup: opts.prefersCleanup ?? true, model: "m" }
    },
  }
}

test("STT_ENGINES includes codex-realtime + claude-voice + cursor-stt + whisper; whisper is fallback", () => {
  expect(STT_ENGINES).toContain("codex-realtime")
  expect(STT_ENGINES).toContain("claude-voice")
  expect(STT_ENGINES).toContain("cursor-stt")
  expect(STT_ENGINES).toContain("whisper")
  expect(FALLBACK_STT_ENGINE).toBe("whisper")
})

test("selectStt('codex-realtime') returns that engine", () => {
  const e = selectStt("codex-realtime", {
    codexRealtime: { isAvailable: () => true },
  })
  expect(e.name).toBe("codex-realtime")
  expect(e.prefersCleanup).toBe(false)
})

test("runStt falls back to whisper when codex-realtime is unavailable", async () => {
  const r = await runStt("/tmp/a.webm", {
    engine: "codex-realtime",
    select: {
      overrides: {
        "codex-realtime": fakeEngine("codex-realtime", { available: false, prefersCleanup: false }),
        whisper: fakeEngine("whisper", { text: "local" }),
      },
    },
  })
  expect(r.text).toBe("local")
  expect(r.engine).toBe("whisper")
  expect(r.fellBack).toBe(true)
})

test("selectStt('whisper') returns a whisper engine", () => {
  const e = selectStt("whisper", { whisper: { isAvailable: () => true } })
  expect(e.name).toBe("whisper")
  expect(e.prefersCleanup).toBe(true)
})

test("selectStt unknown name degrades to whisper", () => {
  const e = selectStt("not-real", { whisper: { isAvailable: () => true } })
  expect(e.name).toBe("whisper")
})

test("runStt uses the primary engine when available", async () => {
  const r = await runStt("/tmp/a.webm", {
    engine: "whisper",
    select: {
      overrides: {
        whisper: fakeEngine("whisper", { text: "hello" }),
      },
    },
  })
  expect(r.text).toBe("hello")
  expect(r.engine).toBe("whisper")
  expect(r.fellBack).toBeUndefined()
  expect(r.prefersCleanup).toBe(true)
})

test("runStt falls back to whisper when a custom primary is unavailable", async () => {
  const r = await runStt("/tmp/a.webm", {
    engine: "future-cloud",
    select: {
      overrides: {
        "future-cloud": fakeEngine("future-cloud", { available: false }),
        whisper: fakeEngine("whisper", { text: "local" }),
      },
    },
  })
  expect(r.text).toBe("local")
  expect(r.engine).toBe("whisper")
  expect(r.fellBack).toBe(true)
})

test("runStt falls back to whisper when a custom primary throws", async () => {
  const r = await runStt("/tmp/a.webm", {
    engine: "future-cloud",
    select: {
      overrides: {
        "future-cloud": fakeEngine("future-cloud", { fail: true }),
        whisper: fakeEngine("whisper", { text: "local" }),
      },
    },
  })
  expect(r.text).toBe("local")
  expect(r.fellBack).toBe(true)
})

test("runStt rethrows when primary fails and fallback is disabled", async () => {
  await expect(runStt("/tmp/a.webm", {
    engine: "whisper",
    fallback: false,
    select: {
      overrides: {
        whisper: fakeEngine("whisper", { fail: true }),
      },
    },
  })).rejects.toThrow("whisper boom")
})

test("runStt rethrows when primary unavailable and fallback disabled", async () => {
  await expect(runStt("/tmp/a.webm", {
    engine: "whisper",
    fallback: false,
    select: {
      overrides: {
        whisper: fakeEngine("whisper", { available: false }),
      },
    },
  })).rejects.toThrow(/unavailable/)
})

test("whisper engine prefersCleanup is true", () => {
  const e = selectStt("whisper", { whisper: { isAvailable: () => true } })
  expect(e.prefersCleanup).toBe(true)
})

test("selectStt('claude-voice') returns that engine", () => {
  const e = selectStt("claude-voice", {
    claudeVoice: { isAvailable: () => true },
  })
  expect(e.name).toBe("claude-voice")
  expect(e.prefersCleanup).toBe(false)
})

test("runStt falls back to whisper when claude-voice is unavailable", async () => {
  const r = await runStt("/tmp/a.webm", {
    engine: "claude-voice",
    select: {
      overrides: {
        "claude-voice": fakeEngine("claude-voice", { available: false, prefersCleanup: false }),
        whisper: fakeEngine("whisper", { text: "local" }),
      },
    },
  })
  expect(r.text).toBe("local")
  expect(r.engine).toBe("whisper")
  expect(r.fellBack).toBe(true)
})

test("selectStt('cursor-stt') returns that engine", () => {
  const e = selectStt("cursor-stt", {
    cursorStt: { isAvailable: () => true },
  })
  expect(e.name).toBe("cursor-stt")
  expect(e.prefersCleanup).toBe(false)
})

test("runStt falls back to whisper when cursor-stt is unavailable", async () => {
  const r = await runStt("/tmp/a.webm", {
    engine: "cursor-stt",
    select: {
      overrides: {
        "cursor-stt": fakeEngine("cursor-stt", { available: false, prefersCleanup: false }),
        whisper: fakeEngine("whisper", { text: "local" }),
      },
    },
  })
  expect(r.text).toBe("local")
  expect(r.engine).toBe("whisper")
  expect(r.fellBack).toBe(true)
})
