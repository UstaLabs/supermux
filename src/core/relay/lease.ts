import { createHmac, timingSafeEqual } from "crypto"

export interface MintLeaseArgs { hostId: string; secret: string; ttlMs: number; now?: number }
export type VerifyFailureReason = "missing" | "malformed" | "invalid_expiry" | "invalid_signature" | "expired"
export type VerifyResult =
  | { ok: true; hostId: string; expiresAt: number }
  | { ok: false; reason: VerifyFailureReason; hostId?: string; expiresAt?: number }

function mac(payload: string, secret: string): string {
  return createHmac("sha256", secret).update(payload).digest("base64url")
}

/** Lease = "<hostId>.<expiresAt>.<hmac>". Opaque to frpc; verified by the plugin. */
export function mintLease({ hostId, secret, ttlMs, now = Date.now() }: MintLeaseArgs): string {
  const payload = `${hostId}.${now + ttlMs}`
  return `${payload}.${mac(payload, secret)}`
}

export function verifyLease(lease: string, { secret, now = Date.now() }: { secret: string; now?: number }): VerifyResult {
  if (!lease) return { ok: false, reason: "missing" }
  const parts = lease.split(".")
  if (parts.length !== 3) return { ok: false, reason: "malformed" }
  const [hostId, expStr, sig] = parts
  if (!hostId || !expStr || !sig) return { ok: false, reason: "malformed" }
  const expiresAt = Number(expStr)
  if (!Number.isSafeInteger(expiresAt) || expiresAt <= 0) return { ok: false, reason: "invalid_expiry" }
  const expected = mac(`${hostId}.${expStr}`, secret)
  const a = Buffer.from(sig), b = Buffer.from(expected)
  if (a.length !== b.length || !timingSafeEqual(a, b)) return { ok: false, reason: "invalid_signature" }
  if (now > expiresAt) return { ok: false, reason: "expired", hostId, expiresAt }
  return { ok: true, hostId, expiresAt }
}
