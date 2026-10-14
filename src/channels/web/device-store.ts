import { createHash, randomBytes } from "crypto"
import { statSync } from "fs"
import { readJsonOr, writeJsonAtomic } from "../../shared/atomic-json"

export interface DeviceRecord {
  token_hash: string
  name: string
  created_at: string
  last_seen_at: string | null
}

function sha256(s: string): string {
  return createHash("sha256").update(s).digest("hex")
}

// touch() rewrites the whole file, so last_seen_at only needs minute precision.
const TOUCH_INTERVAL_MS = 60_000

export class DeviceStore {
  constructor(private readonly path: string) {}

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
      this.setCache(key, readJsonOr<DeviceRecord[]>(this.path, []))
    }
    return this.cache!.records
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

  list(): DeviceRecord[] {
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

  verify(token: string): DeviceRecord | undefined {
    if (!token) return undefined
    this.cached()
    const r = this.cache!.byHash.get(sha256(token))
    return r ? { ...r } : undefined
  }

  touch(token: string): void {
    const presentedHash = sha256(token)
    const now = Date.now()
    const i = this.cached().findIndex((r) => r.token_hash === presentedHash)
    if (i < 0) return
    const last = this.cached()[i]!.last_seen_at
    if (last && now - Date.parse(last) < TOUCH_INTERVAL_MS) return
    const records = this.read()
    records[i]!.last_seen_at = new Date(now).toISOString()
    this.write(records)
  }

  revoke(name: string): boolean {
    const records = this.read()
    const before = records.length
    const after = records.filter((r) => r.name !== name)
    if (after.length === before) return false
    this.write(after)
    for (const fn of this.revokeListeners) {
      try { fn(name) } catch { /* swallow */ }
    }
    return true
  }
}
