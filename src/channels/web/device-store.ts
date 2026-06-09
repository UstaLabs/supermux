import { createHash, randomBytes } from "crypto"
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

function tokenEqualsConstantTime(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let mismatch = 0
  for (let i = 0; i < a.length; i++) mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return mismatch === 0
}

export class DeviceStore {
  constructor(private readonly path: string) {}

  private revokeListeners: Array<(name: string) => void> = []

  addRevokeListener(fn: (name: string) => void): void {
    this.revokeListeners.push(fn)
  }

  private read(): DeviceRecord[] {
    return readJsonOr<DeviceRecord[]>(this.path, [])
  }

  private write(records: DeviceRecord[]): void {
    writeJsonAtomic(this.path, records)
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
    const presentedHash = sha256(token)
    const records = this.read()
    for (const r of records) {
      if (tokenEqualsConstantTime(presentedHash, r.token_hash)) return r
    }
    return undefined
  }

  touch(token: string): void {
    const presentedHash = sha256(token)
    const records = this.read()
    let changed = false
    for (const r of records) {
      if (tokenEqualsConstantTime(presentedHash, r.token_hash)) {
        r.last_seen_at = new Date().toISOString()
        changed = true
        break
      }
    }
    if (changed) this.write(records)
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
