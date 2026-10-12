import { describe, expect, test } from "bun:test"
import { plainTextForSpeech, splitForTts } from "./plain-text"

describe("plainTextForSpeech", () => {
  test("strips fences", () => {
    const out = plainTextForSpeech("Hello\n\n```ts\nconst x = 1\n```\n\nworld")
    expect(out).toContain("Hello")
    expect(out).toContain("world")
    expect(out).not.toContain("const x")
  })
})

describe("splitForTts", () => {
  test("keeps short text whole", () => {
    expect(splitForTts("hi there", 100)).toEqual(["hi there"])
  })

  test("splits long text near sentences", () => {
    const a = "A".repeat(50) + ". "
    const b = "B".repeat(50) + ". "
    const c = "C".repeat(50) + "."
    const text = a + b + c
    const parts = splitForTts(text, 80)
    expect(parts.length).toBeGreaterThan(1)
    expect(parts.join(" ").replace(/\s+/g, " ").length).toBeGreaterThan(100)
    for (const p of parts) expect(p.length).toBeLessThanOrEqual(80)
  })

  test("ramp keeps the first piece to about a sentence", () => {
    const sentences = Array.from({ length: 40 }, (_, i) => `Sentence number ${i} says something useful here.`)
    const text = sentences.join(" ")
    const parts = splitForTts(text, 1200, [160, 400])
    expect(parts[0]!.length).toBeLessThanOrEqual(160)
    expect(parts[0]!.endsWith(".")).toBe(true)
    expect(parts[1]!.length).toBeLessThanOrEqual(400)
    expect(parts[1]!.length).toBeGreaterThan(parts[0]!.length)
    expect(parts.join(" ")).toBe(text)
  })

  test("ramp still splits a message that fits in one codex call", () => {
    const text = "Done. " + "The build passed and every test is green now. ".repeat(8).trim()
    const parts = splitForTts(text, 1200, [160, 400])
    expect(parts.length).toBeGreaterThan(1)
    expect(parts[0]!.length).toBeLessThanOrEqual(160)
    expect(parts.join(" ")).toBe(text)
  })

  test("a long first sentence finishes rather than cutting mid-word", () => {
    const first = "This opening sentence runs on for quite a while, well past the first budget, before it ends."
    const text = first + " Second one. Third one."
    const parts = splitForTts(text, 1200, [60])
    expect(parts[0]).toBe(first)
  })

  test("splits on ! and ? too", () => {
    const parts = splitForTts("Really? Yes! It works fine now. And more text follows after.", 1200, [40])
    expect(parts[0]).toBe("Really? Yes! It works fine now.")
  })
})

import { runTtsStream } from "./tts"
import type { TtsEngine } from "./tts-types"

describe("runTtsStream pipeline", () => {
  test("starts next synth before first yield returns (one-ahead)", async () => {
    let releaseSecond!: () => void
    const secondGate = new Promise<void>((r) => { releaseSecond = r })
    let secondStarted = false
    let calls = 0

    const mock: TtsEngine = {
      name: "codex",
      isAvailable: () => true,
      async speak(text) {
        calls++
        if (calls === 2) {
          secondStarted = true
          await secondGate
        }
        return { audio: new TextEncoder().encode(text.slice(0, 8)), mime: "audio/mpeg", engine: "codex" }
      },
    }

    // Two pieces via the 1200-char codex split.
    const text = ("Alpha sentence. ").repeat(90) + ("Beta sentence here. ").repeat(90)
    const it = runTtsStream(text, {
      engine: "codex",
      flattenMarkdown: false,
      select: { overrides: { codex: mock } },
    })

    const first = await it.next()
    expect(first.done).toBe(false)
    // One-ahead: second synth is already running when first chunk is yielded.
    expect(secondStarted).toBe(true)
    releaseSecond()

    const chunks = [first.value!]
    while (true) {
      const n = await it.next()
      if (n.done) break
      chunks.push(n.value)
    }
    expect(chunks.length).toBeGreaterThanOrEqual(2)
    expect(calls).toBe(chunks.length)
  })
})
