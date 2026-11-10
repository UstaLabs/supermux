import { createHash, randomBytes } from "crypto"
import { statSync } from "fs"
import { readJsonOr, writeJsonAtomic } from "../../shared/atomic-json"

export interface DeviceRecord {
  token_hash: string
  name: string
  created_at: string
  last_seen_at: string | null
  /** The device's preview-only token (#105): it opens port previews and nothing else. Gone with the device. */
  preview_hash?: string
  /**
   * Set while the device's pairing link is unclaimed (#119): the link pairs once, by [expires_at]. [by] is the
   * device that made the code: its next code revokes this one. Gone once claimed; an expired one is revoked.
   */
  pairing?: { expires_at: string; by?: string }
}

/** How long a pairing link pairs (#119), on the broker, whatever the app does. */
export const PAIRING_TTL_MS = 10 * 60_000

export type PairingClaim = { ok: true; name: string } | { ok: false; reason: "expired" | "used" | "unknown" }

function sha256(s: string): string {
  return createHash("sha256").update(s).digest("hex")
}

// touch() rewrites the whole file, so last_seen_at only needs minute precision.
const TOUCH_INTERVAL_MS = 60_000

export class DeviceStore {
  private readonly clock: () => number

  constructor(private readonly path: string, opts: { clock?: () => number } = {}) {
    this.clock = opts.clock ?? Date.now
  }

  private revokeListeners: Array<(name: string) => void> = []

  addRevokeListener(fn: (name: string) => void): void {
    this.revokeListeners.push(fn)
  }

  // verify() runs on every authenticated request, including every asset a
  // private proxy serves, so re-parsing the file each time blocked the event
  // loop for ~1ms per request. Re-read only when the file's stat changes, which
  // still picks up edits made by another process (CLI revoke, another broker).
  // byHash is keyed by sha256(token): a lookup leaks nothing usable, since an
  // attacker can't steer the hash of a guessed token toward a stored one.
  private cache: { key: string; records: DeviceRecord[]; byHash: Map<string, DeviceRecord> } | undefined

  private setCache(key: string, records: DeviceRecord[]): void {
    this.cache = { key, records, byHash: new Map(records.map((r) => [r.token_hash, r])) }
  }

  private statKey(): string {
    try {
      const st = statSync(this.path)
      return `${st.ino}:${st.size}:${st.mtimeMs}`
    } catch {
      return "missing"
    }
  }

  /** The cached records themselves — read-only callers only. */
  private cached(): readonly DeviceRecord[] {
    const key = this.statKey()
    if (this.cache?.key !== key) {
      const previous = this.cache?.records
      const loaded = readJsonOr<unknown>(this.path, undefined)
      const records = Array.isArray(loaded) ? (loaded as DeviceRecord[]) : undefined
      this.setCache(key, records ?? [])
      // Another process (the CLI, a hand edit) removed devices: they are revoked
      // all the same, and whatever outlives a device must hear it. A file that
      // can't be read is not a revoke.
      if (previous && records) {
        const kept = new Set(records.map((r) => r.name))
        for (const r of previous) if (!kept.has(r.name)) this.notifyRevoked(r.name)
      }
    }
    return this.cache!.records
  }

  private notifyRevoked(name: string): void {
    for (const fn of this.revokeListeners) {
      try { fn(name) } catch { /* swallow */ }
    }
  }

  // Callers mutate the array before write(); hand out copies so a failed
  // write can't leave the cache ahead of the file.
  private read(): DeviceRecord[] {
    return this.cached().map((r) => ({ ...r }))
  }

  private write(records: DeviceRecord[]): void {
    writeJsonAtomic(this.path, records)
    this.setCache(this.statKey(), records.map((r) => ({ ...r })))
  }

  /** The devices; an unclaimed pairing link past its deadline is revoked on the way (#119). */
  list(): DeviceRecord[] {
    for (const r of this.cached()) if (this.pairingExpired(r)) this.revoke(r.name)
    return this.read()
  }

  mint(name: string): { token: string; name: string } {
    const records = this.read()
    if (records.some((r) => r.name === name)) {
      // ensure unique by suffixing
      let i = 2
      while (records.some((r) => r.name === `${name}-${i}`)) i++
      name = `${name}-${i}`
    }
    const token = randomBytes(32).toString("base64url")
    records.push({
      token_hash: sha256(token),
      name,
      created_at: new Date().toISOString(),
      last_seen_at: null,
    })
    this.write(records)
    return { token, name }
  }

  /**
   * A preview-only token for the device holding `deviceToken`, or undefined when that token isn't a device's.
   * Minting again replaces the last one. It never verifies as a device token: verify() matches token_hash only.
   */
  mintPreview(deviceToken: string): string | undefined {
    const records = this.read()
    const record = records.find((r) => r.token_hash === sha256(deviceToken))
    if (!record) return undefined
    const token = randomBytes(32).toString("base64url")
    record.preview_hash = sha256(token)
    this.write(records)
    return token
  }

  verifyPreview(token: string): DeviceRecord | undefined {
    if (!token) return undefined
    const hash = sha256(token)
    const r = this.cached().find((x) => x.preview_hash === hash)
    return r ? { ...r } : undefined
  }

  /**
   * A device for a pairing link (#119): pending until the link's first /pair or /pair.json claims it, revoked when
   * nobody has within [PAIRING_TTL_MS]. A code made [by] a device revokes that device's earlier unclaimed codes.
   */
  mintPairing(name: string, opts: { by?: string } = {}): { token: string; name: string } {
    if (opts.by) {
      for (const r of this.cached()) if (r.pairing && r.pairing.by === opts.by) this.revoke(r.name)
    }
    const minted = this.mint(name)
    const records = this.read()
    const record = records.find((r) => r.name === minted.name)!
    record.pairing = { expires_at: new Date(this.clock() + PAIRING_TTL_MS).toISOString(), ...(opts.by ? { by: opts.by } : {}) }
    this.write(records)
    return minted
  }

  /** The pairing link's one use (#119): claims a pending device, or says why the link no longer pairs. */
  claimPairing(token: string): PairingClaim {
    const hash = token ? sha256(token) : ""
    const records = this.read()
    const record = records.find((r) => r.token_hash === hash)
    if (!record) return { ok: false, reason: "unknown" }
    if (!record.pairing) return { ok: false, reason: "used" }
    if (this.pairingExpired(record)) {
      this.revoke(record.name)
      return { ok: false, reason: "expired" }
    }
    delete record.pairing
    this.write(records)
    return { ok: true, name: record.name }
  }

  private pairingExpired(r: DeviceRecord): boolean {
    return !!r.pairing && this.clock() > Date.parse(r.pairing.expires_at)
  }

  verify(token: string): DeviceRecord | undefined {
    if (!token) return undefined
    this.cached()
    const r = this.cache!.byHash.get(sha256(token))
    if (!r) return undefined
    // An unclaimed pairing link past its deadline is no device (#119); the list and its claim remove it.
    if (this.pairingExpired(r)) return undefined
    return { ...r }
  }

  touch(token: string): void {
    const presentedHash = sha256(token)
    const now = Date.now()
    const i = this.cached().findIndex((r) => r.token_hash === presentedHash)
    if (i < 0) return
    const current = this.cached()[i]!
    const last = current.last_seen_at
    // A pending device that signs in has had its link's one use (#119), unless the link expired first.
    const claims = !!current.pairing && !this.pairingExpired(current)
    if (!claims && last && now - Date.parse(last) < TOUCH_INTERVAL_MS) return
    const records = this.read()
    records[i]!.last_seen_at = new Date(now).toISOString()
    if (claims) delete records[i]!.pairing
    this.write(records)
  }

  revoke(name: string): boolean {
    const records = this.read()
    const before = records.length
    const after = records.filter((r) => r.name !== name)
    if (after.length === before) return false
    this.write(after)
    this.notifyRevoked(name)
    return true
  }
}
