import { test, expect } from "bun:test"
import { extractFirstUrl, which } from "./run"

test("extractFirstUrl finds a trycloudflare URL amid noise", () => {
  const out =
    "2024 INF Request custom tunnel\n2024 INF |  https://random-words-here.trycloudflare.com  |\n2024 INF +--+"
  expect(extractFirstUrl(out, /trycloudflare\.com/)).toBe("https://random-words-here.trycloudflare.com")
})

test("extractFirstUrl returns the first url when no host filter", () => {
  expect(extractFirstUrl("see http://a.com and https://b.com")).toBe("http://a.com")
})

test("extractFirstUrl honors the host filter, skipping non-matching urls", () => {
  expect(extractFirstUrl("http://a.com https://x.ts.net", /ts\.net/)).toBe("https://x.ts.net")
})

test("extractFirstUrl trims trailing punctuation", () => {
  expect(extractFirstUrl("url: https://x.ts.net.")).toBe("https://x.ts.net")
})

test("extractFirstUrl returns undefined when there is no url", () => {
  expect(extractFirstUrl("no urls here")).toBeUndefined()
})

test("which returns false for a nonsense binary", () => {
  expect(which("definitely-not-a-real-bin-xyz-9000")).toBe(false)
})
