import { describe, expect, test } from "bun:test"
import { assembleManifest } from "./versions-manifest"

const block = (version: string) => ({
  version,
  publishedAt: "2026-09-21T00:00:00.000Z",
  notesUrl: `https://github.com/UstaLabs/supermux/releases/tag/v${version}`,
  assets: { "linux-x64": { url: `https://dl.test/${version}`, sha256: "sha" } },
})

describe("assembleManifest — alpha release", () => {
  test("writes channels.alpha and carries channels.stable over untouched", () => {
    const stable = block("0.11.36")
    const out = assembleManifest(block("0.12.0-alpha.1"), { schemaVersion: 1, channels: { stable } })
    expect(out.channels.stable).toEqual(stable)
    expect(out.channels.alpha?.version).toBe("0.12.0-alpha.1")
  })

  test("replaces an older alpha", () => {
    const previous = { schemaVersion: 1, channels: { stable: block("0.11.36"), alpha: block("0.12.0-alpha.1") } }
    const out = assembleManifest(block("0.12.0-alpha.2"), previous)
    expect(out.channels.alpha?.version).toBe("0.12.0-alpha.2")
    expect(out.channels.stable.version).toBe("0.11.36")
  })

  test("refuses to publish without a stable block to carry over", () => {
    expect(() => assembleManifest(block("0.12.0-alpha.1"), null)).toThrow(/stable/)
    expect(() => assembleManifest(block("0.12.0-alpha.1"), { schemaVersion: 1, channels: {} })).toThrow(/stable/)
  })
})

describe("assembleManifest — stable release", () => {
  test("rolls alpha users onto a stable that is newer than their alpha", () => {
    const previous = { schemaVersion: 1, channels: { stable: block("0.11.36"), alpha: block("0.12.0-alpha.9") } }
    const out = assembleManifest(block("0.12.0"), previous)
    expect(out.channels.stable.version).toBe("0.12.0")
    expect(out.channels.alpha?.version).toBe("0.12.0")
  })

  test("an old-line hotfix does not drag alpha users backwards", () => {
    const previous = { schemaVersion: 1, channels: { stable: block("0.11.36"), alpha: block("0.12.0-alpha.2") } }
    const out = assembleManifest(block("0.11.37"), previous)
    expect(out.channels.stable.version).toBe("0.11.37")
    expect(out.channels.alpha?.version).toBe("0.12.0-alpha.2")
  })

  test("with no previous manifest both channels point at the release", () => {
    const out = assembleManifest(block("0.12.0"), null)
    expect(out.channels.stable.version).toBe("0.12.0")
    expect(out.channels.alpha?.version).toBe("0.12.0")
  })
})
