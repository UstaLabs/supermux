import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import {
  chmodSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  utimesSync,
  writeFileSync,
  existsSync,
} from "fs"
import { join } from "path"
import { tmpdir } from "os"
import {
  jwtExpiryMs,
  promoteCredential,
  promoteIfNewer,
  readCredentialJson,
  type FreshnessReader,
} from "./credential-file"

let dir: string
beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "cred-file-")) })
afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

/** A credential whose freshness is the numeric `exp` field. */
const cred = (exp: number, token: string) => JSON.stringify({ exp, token })
const expField: FreshnessReader = (p) => {
  const parsed = readCredentialJson(p)
  return typeof parsed?.exp === "number" ? parsed.exp : Number.NEGATIVE_INFINITY
}
/** No file ever carries a claim, so every comparison falls back to mtime. */
const noClaim: FreshnessReader = () => Number.NEGATIVE_INFINITY

function ageFile(path: string, secondsAgo: number) {
  const when = new Date(Date.now() - secondsAgo * 1000)
  utimesSync(path, when, when)
}

describe("promoteCredential", () => {
  test("writes through a sibling temp file and renames it into place", () => {
    const from = join(dir, "session.json")
    const canonical = join(dir, "canonical", "auth.json")
    writeFileSync(from, cred(2, "refreshed"))

    promoteCredential(from, canonical)

    expect(readFileSync(canonical, "utf8")).toBe(cred(2, "refreshed"))
    // The temp file is a sibling of the target (rename is atomic only inside
    // one filesystem) and no temp file survives the write.
    expect(readdirSync(join(dir, "canonical"))).toEqual(["auth.json"])
  })

  test("gives the canonical file owner-only permissions", () => {
    const from = join(dir, "session.json")
    const canonical = join(dir, "auth.json")
    writeFileSync(from, cred(2, "refreshed"), { mode: 0o644 })

    promoteCredential(from, canonical)

    expect(statSync(canonical).mode & 0o777).toBe(0o600)
  })

  test("leaves no temp file behind when the copy fails", () => {
    const canonical = join(dir, "auth.json")
    writeFileSync(canonical, cred(1, "old"))

    expect(() => promoteCredential(join(dir, "missing.json"), canonical)).toThrow()

    expect(readFileSync(canonical, "utf8")).toBe(cred(1, "old"))
    expect(readdirSync(dir)).toEqual(["auth.json"])
  })
})

describe("promoteIfNewer", () => {
  test("a newer session copy wins and replaces the canonical file", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, cred(100, "stale"))
    writeFileSync(sessionCopy, cred(200, "refreshed"))

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: expField })).toBe("promoted")
    expect(readFileSync(canonical, "utf8")).toBe(cred(200, "refreshed"))
  })

  test("an older session copy loses and never touches the canonical file", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, cred(200, "canonical"))
    writeFileSync(sessionCopy, cred(100, "stale"))

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: expField })).toBe("canonical_newer")
    expect(readFileSync(canonical, "utf8")).toBe(cred(200, "canonical"))
  })

  test("a corrupt session copy never clobbers the canonical file", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, cred(1, "canonical"))
    writeFileSync(sessionCopy, "{ this is not json")
    ageFile(canonical, 3600)

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: noClaim })).toBe("unusable_copy")
    expect(readFileSync(canonical, "utf8")).toBe(cred(1, "canonical"))
  })

  test("an empty session copy never clobbers the canonical file", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, cred(1, "canonical"))
    writeFileSync(sessionCopy, "")
    ageFile(canonical, 3600)

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: noClaim })).toBe("unusable_copy")
    expect(readFileSync(canonical, "utf8")).toBe(cred(1, "canonical"))
  })

  test("identical bytes are a no-operation, so a fresh copy does not churn", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, cred(1, "same"))
    writeFileSync(sessionCopy, cred(1, "same"))
    ageFile(canonical, 3600)
    const before = statSync(canonical).mtimeMs

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: noClaim })).toBe("identical")
    expect(statSync(canonical).mtimeMs).toBe(before)
  })

  test("no session copy means there is nothing to heal", () => {
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, cred(1, "canonical"))

    expect(promoteIfNewer({ sessionCopy: join(dir, "missing.json"), canonical, freshness: expField }))
      .toBe("no_session_copy")
  })

  test("an absent canonical file blocks the promotion by default", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(sessionCopy, cred(1, "orphan"))

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: expField })).toBe("no_canonical")
    expect(existsSync(canonical)).toBe(false)
  })

  test("allowMissingCanonical recovers the copy when the canonical file is gone", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(sessionCopy, cred(1, "only-copy"))

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: expField, allowMissingCanonical: true }))
      .toBe("promoted")
    expect(readFileSync(canonical, "utf8")).toBe(cred(1, "only-copy"))
  })

  test("without claims on both sides the comparison falls back to the modification time", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, JSON.stringify({ token: "stale" }))
    writeFileSync(sessionCopy, JSON.stringify({ token: "refreshed" }))
    ageFile(canonical, 3600)

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: noClaim })).toBe("promoted")
    expect(readFileSync(canonical, "utf8")).toBe(JSON.stringify({ token: "refreshed" }))
  })

  test("a corrupt canonical file is repaired by a readable session copy", () => {
    const sessionCopy = join(dir, "session.json")
    const canonical = join(dir, "canonical.json")
    writeFileSync(canonical, "{ truncated")
    writeFileSync(sessionCopy, cred(5, "good"))
    ageFile(canonical, 3600)

    expect(promoteIfNewer({ sessionCopy, canonical, freshness: expField })).toBe("promoted")
    expect(readFileSync(canonical, "utf8")).toBe(cred(5, "good"))
  })

  test("a promotion that cannot be written keeps both files and reports failure", () => {
    const sessionCopy = join(dir, "session.json")
    const lockedDir = join(dir, "locked")
    mkdirSync(lockedDir, { recursive: true })
    const canonical = join(lockedDir, "canonical.json")
    writeFileSync(sessionCopy, cred(200, "refreshed"))
    writeFileSync(canonical, cred(100, "stale"))
    // A read-only directory refuses the sibling temp file, so the rename never
    // runs and the canonical file keeps its old bytes.
    chmodSync(lockedDir, 0o500)

    try {
      expect(promoteIfNewer({ sessionCopy, canonical, freshness: expField })).toBe("failed")
      expect(readFileSync(canonical, "utf8")).toBe(cred(100, "stale"))
      expect(readFileSync(sessionCopy, "utf8")).toBe(cred(200, "refreshed"))
    } finally {
      chmodSync(lockedDir, 0o700)
    }
  })
})

describe("jwtExpiryMs", () => {
  const jwt = (claims: object) =>
    ["e30", Buffer.from(JSON.stringify(claims)).toString("base64url"), "sig"].join(".")

  test("reads the exp claim and converts seconds to milliseconds", () => {
    expect(jwtExpiryMs(jwt({ exp: 1790489028 }))).toBe(1790489028000)
  })

  test("reports no claim for a non-JWT, a missing exp, or a non-string", () => {
    expect(jwtExpiryMs("not-a-jwt")).toBe(Number.NEGATIVE_INFINITY)
    expect(jwtExpiryMs(jwt({ sub: "u" }))).toBe(Number.NEGATIVE_INFINITY)
    expect(jwtExpiryMs(undefined)).toBe(Number.NEGATIVE_INFINITY)
    expect(jwtExpiryMs("a.b.c")).toBe(Number.NEGATIVE_INFINITY)
  })
})
