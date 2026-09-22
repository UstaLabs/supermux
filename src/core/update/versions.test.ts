import { describe, expect, test } from "bun:test"
import { channelFor, compareVersions, isUpdateAvailable, parseVersionsJson } from "./versions"

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
    ["0.2.0-rc.1", "0.2.0-rc.2", -1], // two prereleases: identifier-wise
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

describe("prerelease identifiers compare per semver §11", () => {
  test("numeric identifiers compare numerically", () => {
    expect(compareVersions("0.12.0-alpha.2", "0.12.0-alpha.10")).toBe(-1)
    expect(compareVersions("0.12.0-alpha.10", "0.12.0-alpha.9")).toBe(1)
  })

  test("alpha < beta, numeric < alphanumeric, shorter < longer", () => {
    expect(compareVersions("0.12.0-alpha.9", "0.12.0-beta.1")).toBe(-1)
    expect(compareVersions("0.12.0-1", "0.12.0-alpha")).toBe(-1)
    expect(compareVersions("0.12.0-alpha", "0.12.0-alpha.1")).toBe(-1)
  })

  test("the alpha train sits between the two stable lines", () => {
    expect(isUpdateAvailable("0.11.36", "0.12.0-alpha.1")).toBe(true)
    expect(isUpdateAvailable("0.12.0-alpha.10", "0.12.0")).toBe(true)
    expect(isUpdateAvailable("0.12.0-alpha.10", "0.11.37")).toBe(false)
  })
})

describe("channelFor", () => {
  const withAlpha = () => {
    const obj = JSON.parse(GOOD_PAYLOAD)
    obj.channels.alpha = { ...obj.channels.stable, version: "0.12.0-alpha.3" }
    const res = parseVersionsJson(obj)
    if (!res.ok) throw new Error(res.error)
    return res.data
  }

  test("the schema keeps an optional channels.alpha", () => {
    expect(withAlpha().channels.alpha?.version).toBe("0.12.0-alpha.3")
  })

  test("a prerelease build follows alpha", () => {
    expect(channelFor(withAlpha(), "0.12.0-alpha.1").version).toBe("0.12.0-alpha.3")
  })

  test("a stable or dev build follows stable", () => {
    const m = withAlpha()
    expect(channelFor(m, "0.11.36")).toBe(m.channels.stable)
    expect(channelFor(m, "dev")).toBe(m.channels.stable)
  })

  test("a prerelease build falls back to stable when there is no alpha block", () => {
    const res = parseVersionsJson(JSON.parse(GOOD_PAYLOAD))
    if (!res.ok) throw new Error(res.error)
    expect(channelFor(res.data, "0.12.0-alpha.1")).toBe(res.data.channels.stable)
  })
})
