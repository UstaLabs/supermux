import { describe, expect, test } from "bun:test"
import { UpdateChecker } from "./checker"

const PRIMARY_URL = "https://supermux.dev/versions.json"
const FALLBACK_URL = "https://api.github.com/repos/UstaLabs/supermux/releases/latest"

function versionsBody(version: string): string {
  return JSON.stringify({
    schemaVersion: 1,
    channels: {
      stable: {
        version,
        publishedAt: "2026-06-13T00:00:00.000Z",
        notesUrl: `https://github.com/UstaLabs/supermux/releases/tag/v${version}`,
        assets: {
          "linux-x64": {
            url: `https://github.com/UstaLabs/supermux/releases/download/v${version}/supermux-linux-x64`,
            sha256: "x64sha",
          },
          "linux-arm64": {
            url: `https://github.com/UstaLabs/supermux/releases/download/v${version}/supermux-linux-arm64`,
            sha256: "arm64sha",
          },
        },
      },
    },
  })
}

// Records every request a checker makes so tests can assert headers/URLs.
interface Call {
  url: string
  ifNoneMatch: string | null
}

describe("UpdateChecker.checkNow — 200 happy path", () => {
  test("populates status fields and retains the manifest", async () => {
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc1234",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async () =>
        new Response(versionsBody("0.2.0"), {
          status: 200,
          headers: { etag: '"v2-etag"' },
        }),
    })

    expect(checker.status().state).toBe("idle")
    expect(checker.status().latest).toBe(null)

    await checker.checkNow()

    const s = checker.status()
    expect(s.current).toBe("0.1.0")
    expect(s.commit).toBe("abc1234")
    expect(s.latest).toBe("0.2.0")
    expect(s.updateAvailable).toBe(true)
    expect(s.notesUrl).toContain("v0.2.0")
    expect(s.mode).toBe("binary")
    expect(s.state).toBe("idle")
    expect(s.lastChecked).not.toBe(null)
    expect(s.lastError).toBe(null)

    // manifest retained with asset details for the apply engine
    const m = checker.latestManifest()
    expect(m).not.toBe(null)
    expect(m?.channels.stable.version).toBe("0.2.0")
    expect(m?.channels.stable.assets["linux-x64"]?.sha256).toBe("x64sha")
  })

  test("equal version → updateAvailable false", async () => {
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.2.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async () => new Response(versionsBody("0.2.0"), { status: 200 }),
    })
    await checker.checkNow()
    expect(checker.status().updateAvailable).toBe(false)
    expect(checker.status().latest).toBe("0.2.0")
  })
})

describe("UpdateChecker.checkNow — 304 ETag reuse", () => {
  test("sends if-none-match on the second call and retains last good data", async () => {
    const calls: Call[] = []
    let nth = 0
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async (input, init) => {
        calls.push({
          url: String(input),
          ifNoneMatch: new Headers(init?.headers).get("if-none-match"),
        })
        nth++
        if (nth === 1) {
          return new Response(versionsBody("0.2.0"), {
            status: 200,
            headers: { etag: '"v2-etag"' },
          })
        }
        // second call: server says not-modified
        return new Response(null, { status: 304 })
      },
    })

    await checker.checkNow()
    await checker.checkNow()

    expect(calls.length).toBe(2)
    expect(calls[0]?.ifNoneMatch).toBe(null) // first call: no etag known yet
    expect(calls[1]?.ifNoneMatch).toBe('"v2-etag"') // second: replays stored etag

    // last good data retained across the 304
    const s = checker.status()
    expect(s.latest).toBe("0.2.0")
    expect(s.updateAvailable).toBe(true)
    expect(s.state).toBe("idle")
    expect(s.lastError).toBe(null)
    expect(checker.latestManifest()?.channels.stable.version).toBe("0.2.0")
  })
})

describe("UpdateChecker.checkNow — network failure", () => {
  test("network throw → lastError set, state idle, previous good data retained", async () => {
    let nth = 0
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      // no fallbackUrl override -> default fallback also throws below
      fetchImpl: async () => {
        nth++
        if (nth === 1) {
          return new Response(versionsBody("0.2.0"), { status: 200, headers: { etag: '"e"' } })
        }
        throw new Error("boom: network down")
      },
    })

    await checker.checkNow() // good
    expect(checker.status().latest).toBe("0.2.0")

    await checker.checkNow() // both primary and fallback throw

    const s = checker.status()
    expect(s.state).toBe("idle") // never stuck in checking
    expect(s.lastError).not.toBe(null)
    expect(typeof s.lastError).toBe("string")
    // previous good data retained
    expect(s.latest).toBe("0.2.0")
    expect(s.updateAvailable).toBe(true)
    expect(checker.latestManifest()?.channels.stable.version).toBe("0.2.0")
    expect(s.lastChecked).not.toBe(null)
  })

  test("never throws out of checkNow even when everything fails", async () => {
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async () => {
        throw new Error("total failure")
      },
    })
    // must resolve, not reject
    await expect(checker.checkNow()).resolves.toBeUndefined()
    expect(checker.status().state).toBe("idle")
    expect(checker.status().lastError).not.toBe(null)
  })
})

describe("UpdateChecker.checkNow — GitHub fallback", () => {
  test("primary fails → fallback fills latest from tag_name but NOT the manifest", async () => {
    const calls: string[] = []
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async (input) => {
        const url = String(input)
        calls.push(url)
        if (url === PRIMARY_URL) {
          // primary returns junk → parse fails → triggers fallback
          return new Response("not json at all", { status: 200 })
        }
        if (url === FALLBACK_URL) {
          return new Response(
            JSON.stringify({
              tag_name: "v0.3.0",
              html_url: "https://github.com/UstaLabs/supermux/releases/tag/v0.3.0",
            }),
            { status: 200 },
          )
        }
        throw new Error(`unexpected url ${url}`)
      },
    })

    await checker.checkNow()

    expect(calls).toContain(PRIMARY_URL)
    expect(calls).toContain(FALLBACK_URL)

    const s = checker.status()
    expect(s.latest).toBe("0.3.0") // "v" stripped from tag_name
    expect(s.updateAvailable).toBe(true)
    expect(s.notesUrl).toContain("v0.3.0")
    expect(s.state).toBe("idle")
    // manifest stays null: fallback gives no assets/sha, apply must refuse
    expect(checker.latestManifest()).toBe(null)
  })

  test("fallback is tried only once per check", async () => {
    let fallbackHits = 0
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async (input) => {
        const url = String(input)
        if (url === PRIMARY_URL) throw new Error("primary down")
        if (url === FALLBACK_URL) {
          fallbackHits++
          throw new Error("fallback also down")
        }
        throw new Error("unexpected")
      },
    })
    await checker.checkNow()
    expect(fallbackHits).toBe(1) // exactly once, even though it failed
    expect(checker.status().state).toBe("idle")
    expect(checker.status().lastError).not.toBe(null)
  })

  test("custom fallbackUrl is honored", async () => {
    const customFallback = "https://example.test/fallback"
    const seen: string[] = []
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fallbackUrl: customFallback,
      fetchImpl: async (input) => {
        const url = String(input)
        seen.push(url)
        if (url === PRIMARY_URL) throw new Error("down")
        if (url === customFallback) {
          return new Response(JSON.stringify({ tag_name: "v0.4.0" }), { status: 200 })
        }
        throw new Error("unexpected")
      },
    })
    await checker.checkNow()
    expect(seen).toContain(customFallback)
    expect(checker.status().latest).toBe("0.4.0")
  })
})

describe("UpdateChecker — dev current version", () => {
  test("dev current → updateAvailable false even when latest is higher", async () => {
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "dev",
      commit: "unknown",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async () => new Response(versionsBody("9.9.9"), { status: 200 }),
    })
    await checker.checkNow()
    const s = checker.status()
    expect(s.latest).toBe("9.9.9")
    expect(s.updateAvailable).toBe(false) // dev never claims behind
  })
})

describe("UpdateChecker.setState", () => {
  test("setState surfaces apply progress through the status object", () => {
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async () => new Response(versionsBody("0.2.0"), { status: 200 }),
    })
    checker.setState("downloading")
    expect(checker.status().state).toBe("downloading")
    checker.setState("failed", "sha mismatch")
    expect(checker.status().state).toBe("failed")
    expect(checker.status().lastError).toBe("sha mismatch")
    checker.setState("restart-required")
    expect(checker.status().state).toBe("restart-required")
  })
})

describe("UpdateChecker timers", () => {
  test("no timers fire from the constructor (no fetch before start/checkNow)", async () => {
    let fetched = false
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      intervalMs: 5,
      fetchImpl: async () => {
        fetched = true
        return new Response(versionsBody("0.2.0"), { status: 200 })
      },
    })
    // wait longer than intervalMs without starting
    await new Promise((r) => setTimeout(r, 30))
    expect(fetched).toBe(false)
    checker.stop() // safe to call even when never started
  })

  test("start schedules a boot check; stop clears timers (no leak)", async () => {
    let fetches = 0
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0, // immediate boot check
      intervalMs: 10_000, // long interval so only the boot check fires in this window
      fetchImpl: async () => {
        fetches++
        return new Response(versionsBody("0.2.0"), { status: 200 })
      },
    })
    checker.start()
    // allow the boot-jitter(0) timer + the async checkNow to run
    await new Promise((r) => setTimeout(r, 40))
    expect(fetches).toBe(1)
    checker.stop()
    const after = fetches
    // nothing more should fire after stop
    await new Promise((r) => setTimeout(r, 40))
    expect(fetches).toBe(after)
  })

  test("stop is idempotent and safe before start", () => {
    const checker = new UpdateChecker({
      url: PRIMARY_URL,
      currentVersion: "0.1.0",
      commit: "abc",
      mode: "binary",
      bootJitterMs: 0,
      fetchImpl: async () => new Response(versionsBody("0.2.0"), { status: 200 }),
    })
    expect(() => {
      checker.stop()
      checker.stop()
    }).not.toThrow()
  })
})
