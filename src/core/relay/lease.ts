import { createHmac, timingSafeEqual } from "crypto"

export interface MintLeaseArgs { hostId: string; secret: string; ttlMs: number; now?: number }
export type VerifyResult = { ok: true; hostId: string } | { ok: false }

function mac(payload: string, secret: string): string {
  return createHmac("sha256", secret).update(payload).digest("base64url")
}

/** Lease = "<hostId>.<expiresAt>.<hmac>". Opaque to frpc; verified by the plugin. */
export function mintLease({ hostId, secret, ttlMs, now = Date.now() }: MintLeaseArgs): string {
  const payload = `${hostId}.${now + ttlMs}`
  return `${payload}.${mac(payload, secret)}`
}

export function verifyLease(lease: string, { secret, now = Date.now() }: { secret: string; now?: number }): VerifyResult {
  const parts = lease.split(".")
  if (parts.length !== 3) return { ok: false }
  const [hostId, expStr, sig] = parts
  const expected = mac(`${hostId}.${expStr}`, secret)
  const a = Buffer.from(sig), b = Buffer.from(expected)
  if (a.length !== b.length || !timingSafeEqual(a, b)) return { ok: false }
  if (now > Number(expStr)) return { ok: false }
  return { ok: true, hostId }
}
