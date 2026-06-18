import { test, expect } from "bun:test"
import { extractFirstUrl, realRun, which } from "./run"

/** Run `fn` while capturing everything written to process.stdout (restored after). */
async function captureStdout(fn: () => Promise<void>): Promise<string> {
  const chunks: string[] = []
  const orig = process.stdout.write.bind(process.stdout)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(process.stdout as any).write = (chunk: any) => {
    chunks.push(typeof chunk === "string" ? chunk : new TextDecoder().decode(chunk))
    return true
  }
  try {
    await fn()
  } finally {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(process.stdout as any).write = orig
  }
  return chunks.join("")
}

test("realRun streams output live to process.stdout AND still captures it when stream:true", async () => {
  let res: Awaited<ReturnType<typeof realRun>> | undefined
  const live = await captureStdout(async () => {
    res = await realRun(["sh", "-c", "printf 'live-output-marker'"], { stream: true })
  })
  // Teed to the terminal as it happened — the user can see an auth URL mid-run.
  expect(live).toContain("live-output-marker")
  // …and still returned to the caller for the provider's own logic.
  expect(res?.stdout).toContain("live-output-marker")
  expect(res?.code).toBe(0)
})

test("realRun captures silently (no terminal echo) when stream is off", async () => {
  let res: Awaited<ReturnType<typeof realRun>> | undefined
  const live = await captureStdout(async () => {
    res = await realRun(["sh", "-c", "printf 'quiet-marker'"])
  })
  expect(live).not.toContain("quiet-marker")
  expect(res?.stdout).toContain("quiet-marker")
})

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
