import { describe, expect, test } from "bun:test"
import { compareVersions, isUpdateAvailable, parseVersionsJson } from "./versions"

// Real shape emitted by scripts/generate-versions-json.ts 9.9.9 aaa bbb
// (publishedAt pinned to a fixed instant — the generator uses new Date().toISOString()
// which varies per run; the structure is what the parser cares about).
const GOOD_PAYLOAD = `{
  "schemaVersion": 1,
  "channels": {
    "stable": {
      "version": "9.9.9",
      "publishedAt": "2026-06-13T01:06:16.109Z",
      "notesUrl": "https://github.com/UstaLabs/supermux/releases/tag/v9.9.9",
      "assets": {
        "linux-x64": {
          "url": "https://github.com/UstaLabs/supermux/releases/download/v9.9.9/supermux-linux-x64",
          "sha256": "aaa"
        },
        "linux-arm64": {
          "url": "https://github.com/UstaLabs/supermux/releases/download/v9.9.9/supermux-linux-arm64",
          "sha256": "bbb"
        }
      }
    }
  }
}`

describe("compareVersions", () => {
  // a, b, expected
  const cases: Array<[string, string, -1 | 0 | 1]> = [
    ["0.10.0", "0.9.9", 1], // numeric, not lexicographic
    ["0.9.9", "0.10.0", -1],
    ["1.0", "0.9.9", 1], // major beats everything below
    ["0.9.9", "1.0", -1],
    ["1.2.3", "1.2.3", 0], // equal
    ["1.0", "1.0.0", 0], // missing trailing segments == 0
    ["1.0.0", "1.0", 0],
    ["0.2.0-rc.1", "0.2.0", -1], // prerelease ranks below its release
    ["0.2.0", "0.2.0-rc.1", 1],
    ["0.2.0-rc.1", "0.2.0-rc.2", -1], // two prereleases: lexicographic tiebreak
    ["0.2.0-rc.2", "0.2.0-rc.1", 1],
    ["0.2.0-rc.1", "0.2.0-rc.1", 0], // identical prereleases
    ["dev", "0.0.1", -1], // dev is lowest of all
    ["0.0.1", "dev", 1],
    ["dev", "dev", 0], // two unparseables equal
    ["garbage!!", "0.0.1", -1], // garbage is lowest of all
    ["0.0.1", "garbage!!", 1],
    ["garbage!!", "also-garbage", 0], // two unparseables equal
    ["2.0.0", "10.0.0", -1], // multi-digit major numeric
  ]
  for (const [a, b, expected] of cases) {
    test(`compareVersions(${a}, ${b}) === ${expected}`, () => {
      expect(compareVersions(a, b)).toBe(expected)
    })
  }

  test("comparison is anti-symmetric across the table", () => {
    for (const [a, b, expected] of cases) {
      // negate without producing -0 (toBe distinguishes -0 from 0)
      const flipped = (expected === 0 ? 0 : -expected) as -1 | 0 | 1
      expect(compareVersions(b, a)).toBe(flipped)
    }
  })
})

describe("isUpdateAvailable", () => {
  test("higher latest → true", () => {
    expect(isUpdateAvailable("0.1.0", "0.2.0")).toBe(true)
  })
  test("equal → false", () => {
    expect(isUpdateAvailable("0.2.0", "0.2.0")).toBe(false)
  })
  test("lower latest → false", () => {
    expect(isUpdateAvailable("0.3.0", "0.2.0")).toBe(false)
  })
  test("dev current → false even when latest higher", () => {
    expect(isUpdateAvailable("dev", "9.9.9")).toBe(false)
  })
  test("unparseable current → false even when latest higher", () => {
    expect(isUpdateAvailable("garbage!!", "9.9.9")).toBe(false)
  })
  test("prerelease current, release latest → true", () => {
    expect(isUpdateAvailable("0.2.0-rc.1", "0.2.0")).toBe(true)
  })
})

describe("parseVersionsJson", () => {
  test("parses the real generator payload", () => {
    const parsed = JSON.parse(GOOD_PAYLOAD)
    const res = parseVersionsJson(parsed)
    expect(res.ok).toBe(true)
    if (res.ok) {
      expect(res.data.schemaVersion).toBe(1)
      expect(res.data.channels.stable.version).toBe("9.9.9")
      expect(res.data.channels.stable.notesUrl).toContain("v9.9.9")
      expect(res.data.channels.stable.assets["linux-x64"]?.url).toContain("supermux-linux-x64")
      expect(res.data.channels.stable.assets["linux-x64"]?.sha256).toBe("aaa")
      expect(res.data.channels.stable.assets["linux-arm64"]?.sha256).toBe("bbb")
    }
  })

  test("accepts arbitrary asset keys (forward-compat)", () => {
    const obj = JSON.parse(GOOD_PAYLOAD)
    obj.channels.stable.assets["darwin-arm64"] = { url: "https://x/y", sha256: "ccc" }
    const res = parseVersionsJson(obj)
    expect(res.ok).toBe(true)
    if (res.ok) expect(res.data.channels.stable.assets["darwin-arm64"]?.sha256).toBe("ccc")
  })

  test("rejects a non-object with a useful error", () => {
    const res = parseVersionsJson("totally junk")
    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.error.length).toBeGreaterThan(0)
      expect(res.error.toLowerCase()).toContain("expected object")
    }
  })

  test("rejects missing channels.stable with a path in the error", () => {
    const res = parseVersionsJson({ schemaVersion: 1, channels: {} })
    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.error.length).toBeGreaterThan(0)
      // error mentions where it failed
      expect(res.error).toContain("stable")
    }
  })

  test("rejects wrong-typed schemaVersion", () => {
    const obj = JSON.parse(GOOD_PAYLOAD)
    obj.schemaVersion = "1"
    const res = parseVersionsJson(obj)
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.error).toContain("schemaVersion")
  })

  test("rejects asset missing sha256", () => {
    const obj = JSON.parse(GOOD_PAYLOAD)
    delete obj.channels.stable.assets["linux-x64"].sha256
    const res = parseVersionsJson(obj)
    expect(res.ok).toBe(false)
  })
})
