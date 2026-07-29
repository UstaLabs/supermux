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
})
