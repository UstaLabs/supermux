import { createHash, randomBytes } from "crypto"

function sha256(s: string): string { return createHash("sha256").update(s).digest("hex") }

export interface ClaimStoreOpts {
  ttlMs?: number
  clock?: () => number
}

/** In-memory one-time pairing secrets: hashed at rest, single-use, expiring. */
export class ClaimStore {
  private readonly ttlMs: number
  private readonly clock: () => number
  private readonly entries = new Map<string, number>() // hash → expiresAt

  constructor(opts: ClaimStoreOpts = {}) {
    this.ttlMs = opts.ttlMs ?? 10 * 60 * 1000
    this.clock = opts.clock ?? (() => Date.now())
  }

  mint(): string {
    const secret = randomBytes(16).toString("base64url")
    this.entries.set(sha256(secret), this.clock() + this.ttlMs)
    return secret
  }

  /** Verify + delete atomically. Returns true only for a live, unused secret. */
  consume(secret: string): boolean {
    const key = sha256(secret)
    const expiresAt = this.entries.get(key)
    if (expiresAt === undefined) return false
    this.entries.delete(key)
    return this.clock() <= expiresAt
  }

  sweep(): void {
    const now = this.clock()
    for (const [k, exp] of this.entries) if (now > exp) this.entries.delete(k)
  }
}
