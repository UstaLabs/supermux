import { describe, expect, it } from "vitest"
import { plainTextForSpeech } from "./speech-text"

describe("plainTextForSpeech", () => {
  it("strips fenced code and keeps surrounding prose", () => {
    const out = plainTextForSpeech("Hello\n\n```ts\nconst x = 1\n```\n\nworld")
    expect(out).toContain("Hello")
    expect(out).toContain("world")
    expect(out).not.toContain("const x")
  })

  it("unwraps links and inline code", () => {
    expect(plainTextForSpeech("See [docs](https://x.test) and `foo`.")).toBe("See docs and foo.")
  })

  it("returns empty for blank input", () => {
    expect(plainTextForSpeech("")).toBe("")
    expect(plainTextForSpeech("   ")).toBe("")
  })
})
