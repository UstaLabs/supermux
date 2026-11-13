// #116: the one-time code SimplySign's login takes as its password (RFC 6238), computed in-process from the account's
// otpauth URI (the SIMPLYSIGN_OTP_URI secret). Certum's authenticator uses SHA-256, 6 digits, 30 s. Nothing here logs
// or keeps the seed: errors never quote the URI.
import { createHmac } from "node:crypto"

export type OtpAlgorithm = "sha1" | "sha256" | "sha512"

export interface OtpParams {
  readonly secret: Buffer
  readonly algorithm: OtpAlgorithm
  readonly digits: number
  readonly period: number
}

const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

function base32Decode(text: string): Buffer {
  const clean = text.toUpperCase().replace(/=+$/, "").replace(/\s+/g, "")
  let bits = 0
  let value = 0
  const out: number[] = []
  for (const ch of clean) {
    const v = BASE32.indexOf(ch)
    if (v < 0) throw new Error("the otpauth secret is not base32")
    value = (value << 5) | v
    bits += 5
    if (bits >= 8) {
      bits -= 8
      out.push((value >> bits) & 0xff)
    }
  }
  return Buffer.from(out)
}

/** The seed and its parameters from an `otpauth://totp/…?secret=…` URI. */
export function parseOtpUri(uri: string): OtpParams {
  let url: URL
  try {
    url = new URL(uri.trim())
  } catch {
    throw new Error("the one-time-code secret is not an otpauth URI")
  }
  if (url.protocol !== "otpauth:" || url.host.toLowerCase() !== "totp") throw new Error("the one-time-code secret is not an otpauth://totp URI")
  const secret = url.searchParams.get("secret")
  if (!secret) throw new Error("the otpauth URI has no secret")
  const algorithm = (url.searchParams.get("algorithm") ?? "SHA256").toLowerCase()
  if (algorithm !== "sha1" && algorithm !== "sha256" && algorithm !== "sha512") throw new Error("the otpauth URI names an algorithm TOTP does not use")
  const digits = Number(url.searchParams.get("digits") ?? 6)
  const period = Number(url.searchParams.get("period") ?? 30)
  if (!Number.isInteger(digits) || digits < 6 || digits > 8) throw new Error("the otpauth URI's digits are out of range")
  if (!Number.isInteger(period) || period < 1) throw new Error("the otpauth URI's period is invalid")
  const key = base32Decode(secret)
  if (key.length === 0) throw new Error("the otpauth URI's secret is empty")
  return { secret: key, algorithm, digits, period }
}

/** The code for the window [unixSeconds] falls in. */
export function codeAt(p: OtpParams, unixSeconds: number): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(unixSeconds / p.period)))
  const mac = createHmac(p.algorithm, p.secret).update(counter).digest()
  const offset = mac[mac.length - 1]! & 0x0f
  const binary = mac.readUInt32BE(offset) & 0x7fffffff
  return String(binary % 10 ** p.digits).padStart(p.digits, "0")
}

/** The window [unixSeconds] falls in, and the whole seconds left in it. */
export function windowOf(p: OtpParams, unixSeconds: number): { index: number; secondsLeft: number } {
  const index = Math.floor(unixSeconds / p.period)
  return { index, secondsLeft: (index + 1) * p.period - Math.floor(unixSeconds) }
}
