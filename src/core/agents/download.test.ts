import { describe, expect, test } from "bun:test"
import { BINARY_TIMEOUT_MS, METADATA_TIMEOUT_MS, assertOpenCodeAssetUrl, httpsBytes, httpsGet } from "./download"

function responding(finalUrl: string, body = "ok", status = 200) {
  const seen: Array<{ url: string; init?: RequestInit }> = []
  const fn = (async (url: string, init?: RequestInit) => {
    seen.push({ url, init })
    const r = new Response(body, { status })
    Object.defineProperty(r, "url", { value: finalUrl })
    return r
  }) as unknown as typeof fetch
  return { fn, seen }
}

describe("httpsGet", () => {
  test("follows redirects, with a timeout signal, and returns an HTTPS answer", async () => {
    const f = responding("https://cdn.example.com/x")
    const res = await httpsGet(f.fn, "https://example.com/x", { timeoutMs: METADATA_TIMEOUT_MS })
    expect(await res.text()).toBe("ok")
    expect(f.seen[0]!.init!.redirect).toBe("follow")
    expect(f.seen[0]!.init!.signal).toBeInstanceOf(AbortSignal)
  })

  test("a redirect that lands on http:// is refused", async () => {
    const f = responding("http://evil.example.com/x")
    const err = await httpsGet(f.fn, "https://example.com/x", { timeoutMs: 1000 }).catch((e) => e)
    expect(String(err)).toContain("non-HTTPS")
  })

  test("an http:// URL is refused before any request", async () => {
    const f = responding("http://example.com/x")
    const err = await httpsGet(f.fn, "http://example.com/x", { timeoutMs: 1000 }).catch((e) => e)
    expect(String(err)).toContain("not an https:// URL")
    expect(f.seen).toEqual([])
  })

  test("a non-2xx answer fails with its status", async () => {
    const f = responding("https://example.com/x", "no", 503)
    expect(String(await httpsGet(f.fn, "https://example.com/x", { timeoutMs: 1000 }).catch((e) => e))).toContain("HTTP 503")
  })

  test("a request that outlives its budget fails as a timeout", async () => {
    const hang = (async (_url: string, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init!.signal!.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "TimeoutError" })))
      })) as unknown as typeof fetch
    const err = await httpsGet(hang, "https://example.com/slow", { timeoutMs: 20 }).catch((e) => e)
    expect(String(err)).toContain("timed out after 0.02 s")
  })

  test("binary downloads get the 10 minute budget", () => {
    expect(BINARY_TIMEOUT_MS).toBe(600_000)
    expect(METADATA_TIMEOUT_MS).toBe(60_000)
  })

  test("httpsBytes returns the body", async () => {
    const f = responding("https://example.com/b", "abc")
    expect(Array.from(await httpsBytes(f.fn, "https://example.com/b"))).toEqual([97, 98, 99])
  })
})

test("OpenCode assets must be its own GitHub release downloads", () => {
  expect(() => assertOpenCodeAssetUrl("https://github.com/anomalyco/opencode/releases/download/v1/opencode-linux-x64.tar.gz")).not.toThrow()
  expect(() => assertOpenCodeAssetUrl("https://evil.example.com/opencode.tar.gz")).toThrow("not under")
  expect(() => assertOpenCodeAssetUrl("https://github.com/someone/opencode/releases/download/v1/x.zip")).toThrow("not under")
})
