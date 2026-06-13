// versions.json schema (distribution spec §A) + version comparison helpers.
//
// The update checker polls versions.json; the apply engine reads asset
// URLs/sha256 out of the parsed manifest. Schema is permissive on asset KEYS
// (any string) so adding e.g. darwin-arm64 later doesn't break old brokers,
// but strict on the fields the apply engine needs.
import { z } from "zod"

const AssetSchema = z.object({
  url: z.string(),
  sha256: z.string(),
})

const ChannelSchema = z.object({
  version: z.string(),
  publishedAt: z.string(),
  notesUrl: z.string(),
  // record over arbitrary string keys (linux-x64 / linux-arm64 today, more later)
  assets: z.record(z.string(), AssetSchema),
})

export const VersionsJsonSchema = z.object({
  schemaVersion: z.number(),
  channels: z.object({
    stable: ChannelSchema,
  }),
})

export type VersionAsset = z.infer<typeof AssetSchema>
export type VersionChannel = z.infer<typeof ChannelSchema>
export type VersionsJson = z.infer<typeof VersionsJsonSchema>

export type ParseResult =
  | { ok: true; data: VersionsJson }
  | { ok: false; error: string }

/**
 * Parse an unknown value (typically the decoded JSON body of versions.json)
 * into a validated VersionsJson, or a short human-readable error string.
 */
export function parseVersionsJson(input: unknown): ParseResult {
  const result = VersionsJsonSchema.safeParse(input)
  if (result.success) return { ok: true, data: result.data }
  // Flatten zod issues to a compact "path: message; path: message" string.
  const error = result.error.issues
    .map((issue) => {
      const path = issue.path.join(".")
      return path ? `${path}: ${issue.message}` : issue.message
    })
    .join("; ")
  return { ok: false, error: error || "invalid versions.json" }
}

// ── version comparison (semver-lite, no dependency) ──────────────────────────

interface ParsedVersion {
  core: number[]
  prerelease: string | null
}

/**
 * Parse "1.2.3" / "1.2.3-rc.1" / "1.0" into numeric core segments + optional
 * prerelease suffix. Returns null for anything whose core isn't purely numeric
 * dotted digits (e.g. "dev", "garbage!!", "1.x") — those rank lowest of all.
 */
function parseVersion(v: string): ParsedVersion | null {
  const trimmed = v.trim()
  if (trimmed.length === 0) return null
  const dash = trimmed.indexOf("-")
  const coreStr = dash === -1 ? trimmed : trimmed.slice(0, dash)
  const prerelease = dash === -1 ? null : trimmed.slice(dash + 1)
  const segments = coreStr.split(".")
  const core: number[] = []
  for (const seg of segments) {
    if (seg.length === 0 || !/^[0-9]+$/.test(seg)) return null
    core.push(Number(seg))
  }
  if (core.length === 0) return null
  return { core, prerelease }
}

function compareNumericCore(a: number[], b: number[]): -1 | 0 | 1 {
  const len = Math.max(a.length, b.length)
  for (let i = 0; i < len; i++) {
    const av = a[i] ?? 0 // missing trailing segment == 0
    const bv = b[i] ?? 0
    if (av < bv) return -1
    if (av > bv) return 1
  }
  return 0
}

/**
 * Compare two version strings.
 *  - Numeric dotted cores compared segment-wise (missing trailing segs = 0).
 *  - A `-prerelease` suffix ranks BELOW the same release (0.2.0-rc.1 < 0.2.0).
 *  - Two prereleases of the same core compare lexicographically as a tiebreak.
 *  - Unparseable / "dev" ranks lowest of all (and two unparseables are equal).
 */
export function compareVersions(a: string, b: string): -1 | 0 | 1 {
  const pa = parseVersion(a)
  const pb = parseVersion(b)

  // Unparseable handling: lowest of all.
  if (pa === null && pb === null) return 0
  if (pa === null) return -1
  if (pb === null) return 1

  const coreCmp = compareNumericCore(pa.core, pb.core)
  if (coreCmp !== 0) return coreCmp

  // Same numeric core → prerelease awareness.
  if (pa.prerelease === null && pb.prerelease === null) return 0
  if (pa.prerelease === null) return 1 // a is the release, b is prerelease → a > b
  if (pb.prerelease === null) return -1 // a is prerelease, b is release → a < b
  // Both prereleases: lexicographic tiebreak.
  if (pa.prerelease < pb.prerelease) return -1
  if (pa.prerelease > pb.prerelease) return 1
  return 0
}

/**
 * Is `latest` strictly newer than `current`?
 * Always false when `current` is "dev"/unparseable (a dev build never claims an
 * update is available — its version is unknowable, not "behind").
 */
export function isUpdateAvailable(current: string, latest: string): boolean {
  if (parseVersion(current) === null) return false
  return compareVersions(latest, current) > 0
}
