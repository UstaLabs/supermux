/** Credential-file mechanics shared by the per-agent auth resolvers.
 *
 * This module holds MECHANICS, not a contract: an atomic write, a JWT expiry
 * reader, and one compare-then-promote driver. The dialect — which field of
 * which file carries the freshness signal — stays inside each agent's own
 * `auth.ts`, because the file format is the agent's own.
 *
 * ## Why promotion exists
 *
 * Codex and cursor give each session a COPY of the user's canonical credential.
 * When the CLI refreshes its token, it writes the copy. The canonical file never
 * learns, so a long session drifts away from the user's credential and a rotated
 * refresh token can leave the canonical file useless.
 *
 * Grok solved the same problem with a canonical-path environment variable
 * (`GROK_AUTH_PATH`) after a copy and then a symlink both failed. A symlink is
 * wrong because an atomic rename replaces the symlink itself. No such variable
 * is known for codex or cursor, and the real refresh flows cannot be exercised
 * from the broker, so those two keep the copy transport and heal the drift on
 * every spawn and every resume instead.
 */
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
} from "fs"
import { randomUUID } from "crypto"
import { dirname } from "path"

/** How fresh a credential file is, in milliseconds since the epoch.
 * Return `Number.NEGATIVE_INFINITY` when the file carries no readable claim. */
export type FreshnessReader = (path: string) => number

/** Replace `canonical` with the bytes of `from` in ONE atomic step: write a
 * temp file beside the target, set mode 0600, then rename. A crash therefore
 * leaves either the whole old file or the whole new file, never a partial one.
 * The rename is atomic only inside one filesystem, so the temp file is a
 * sibling of the target and never lives in /tmp. */
export function promoteCredential(from: string, canonical: string): void {
  mkdirSync(dirname(canonical), { recursive: true, mode: 0o700 })
  const temp = `${canonical}.mux-${process.pid}-${randomUUID()}.tmp`
  try {
    copyFileSync(from, temp)
    chmodSync(temp, 0o600)
    renameSync(temp, canonical)
  } finally {
    rmSync(temp, { force: true })
  }
}

/** What `promoteIfNewer` did. Every value except `promoted` leaves both files
 * exactly as they were. */
export type PromotionResult =
  | "promoted"
  | "canonical_newer"
  | "identical"
  | "no_session_copy"
  | "no_canonical"
  | "unusable_copy"
  | "failed"

/** Heal credential drift in the session -> canonical direction.
 *
 * The promotion happens ONLY when every one of these holds:
 *  - the session copy exists, is not empty, and parses as JSON,
 *  - the canonical file exists (see `allowMissingCanonical`),
 *  - the two files differ byte for byte,
 *  - the session copy is strictly fresher than the canonical file.
 *
 * Freshness compares the two expiry claims when BOTH files give one. Otherwise
 * it compares the modification times. `copyFileSync` stamps the copy with the
 * time of the copy, so a fresh copy always looks newer than its source; the
 * byte-equality test above makes that case a no-operation.
 *
 * The function never throws. A failure returns `"failed"` and keeps both files,
 * because the session copy may hold the only usable token. */
export function promoteIfNewer(opts: {
  sessionCopy: string
  canonical: string
  freshness: FreshnessReader
  /** Allow a promotion when the canonical file is absent. Default false: for
   * codex and cursor an absent canonical file means the user logged out on the
   * host, and a promotion would undo that logout. */
  allowMissingCanonical?: boolean
}): PromotionResult {
  const { sessionCopy, canonical, freshness } = opts
  if (!existsSync(sessionCopy)) return "no_session_copy"

  let copyBytes: Buffer
  try {
    copyBytes = readFileSync(sessionCopy)
  } catch {
    return "unusable_copy"
  }
  // A truncated, empty, or non-JSON file is not a credential. It must never
  // reach the canonical path.
  if (copyBytes.length === 0) return "unusable_copy"
  try {
    JSON.parse(copyBytes.toString("utf8"))
  } catch {
    return "unusable_copy"
  }

  const canonicalExists = existsSync(canonical)
  if (!canonicalExists && !opts.allowMissingCanonical) return "no_canonical"

  if (canonicalExists) {
    let canonicalBytes: Buffer
    try {
      canonicalBytes = readFileSync(canonical)
    } catch {
      return "failed"
    }
    if (copyBytes.equals(canonicalBytes)) return "identical"
    if (!isFresher(sessionCopy, canonical, freshness)) return "canonical_newer"
  }

  try {
    promoteCredential(sessionCopy, canonical)
    return "promoted"
  } catch {
    return "failed"
  }
}

function isFresher(sessionCopy: string, canonical: string, freshness: FreshnessReader): boolean {
  const copyClaim = freshness(sessionCopy)
  const canonicalClaim = freshness(canonical)
  if (Number.isFinite(copyClaim) && Number.isFinite(canonicalClaim)) return copyClaim > canonicalClaim
  return mtimeMs(sessionCopy) > mtimeMs(canonical)
}

function mtimeMs(path: string): number {
  try {
    return statSync(path).mtimeMs
  } catch {
    return Number.NEGATIVE_INFINITY
  }
}

/** Milliseconds since the epoch of a JWT `exp` claim, or NEGATIVE_INFINITY when
 * the string is not a JWT or carries no numeric `exp`. Codex and cursor both
 * store their access token as a JWT, so both read freshness this way. */
export function jwtExpiryMs(token: unknown): number {
  if (typeof token !== "string") return Number.NEGATIVE_INFINITY
  const parts = token.split(".")
  if (parts.length !== 3) return Number.NEGATIVE_INFINITY
  try {
    const payload = Buffer.from(parts[1]!, "base64url").toString("utf8")
    const claims = JSON.parse(payload)
    const exp = claims?.exp
    if (typeof exp !== "number" || !Number.isFinite(exp)) return Number.NEGATIVE_INFINITY
    return exp * 1000
  } catch {
    return Number.NEGATIVE_INFINITY
  }
}

/** Read a credential file as JSON, or undefined when it is missing or corrupt. */
export function readCredentialJson(path: string): any | undefined {
  try {
    return JSON.parse(readFileSync(path, "utf8"))
  } catch {
    return undefined
  }
}
