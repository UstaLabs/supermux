import { randomBytes, createCipheriv, createDecipheriv } from "node:crypto"

export interface RelayKeyset { currentKeyId: string; keys: Map<string, Buffer> }
export type OpenResult =
  | { ok: true; platform: "ios" | "android"; pushToken: string }
  | { ok: false; reason: "expired" | "invalid" }
export interface TokenCodec {
  seal(input: { platform: "ios" | "android"; pushToken: string; ttlSeconds: number }): string
  open(token: string): OpenResult
}

export function createTokenCodec(keyset: RelayKeyset, now: () => number = () => Math.floor(Date.now() / 1000)): TokenCodec {
  return {
    seal({ platform, pushToken, ttlSeconds }) {
      const keyId = keyset.currentKeyId
      const key = keyset.keys.get(keyId)
      if (!key) throw new Error(`relay token key '${keyId}' missing`)
      const iv = randomBytes(12)
      const aad = Buffer.from(`r1.${keyId}`, "utf8")
      const pt = Buffer.from(JSON.stringify({ p: platform === "ios" ? "i" : "a", t: pushToken, e: now() + ttlSeconds }), "utf8")
      const cipher = createCipheriv("aes-256-gcm", key, iv)
      cipher.setAAD(aad)
      const ct = Buffer.concat([cipher.update(pt), cipher.final()])
      const tag = cipher.getAuthTag()
      return `r1.${keyId}.${Buffer.concat([iv, ct, tag]).toString("base64url")}`
    },
    open(token) {
      try {
        const parts = token.split(".")
        if (parts.length !== 3 || parts[0] !== "r1") return { ok: false, reason: "invalid" }
        const keyId = parts[1]!
        const key = keyset.keys.get(keyId)
        if (!key) return { ok: false, reason: "invalid" }
        const raw = Buffer.from(parts[2]!, "base64url")
        if (raw.length < 12 + 16 + 1) return { ok: false, reason: "invalid" }
        const iv = raw.subarray(0, 12)
        const tag = raw.subarray(raw.length - 16)
        const ct = raw.subarray(12, raw.length - 16)
        const decipher = createDecipheriv("aes-256-gcm", key, iv)
        decipher.setAAD(Buffer.from(`r1.${keyId}`, "utf8"))
        decipher.setAuthTag(tag)
        const pt = Buffer.concat([decipher.update(ct), decipher.final()])
        const o = JSON.parse(pt.toString("utf8")) as { p: "i" | "a"; t: string; e: number }
        if (typeof o.e !== "number" || o.e <= now()) return { ok: false, reason: "expired" }
        return { ok: true, platform: o.p === "i" ? "ios" : "android", pushToken: o.t }
      } catch {
        return { ok: false, reason: "invalid" }
      }
    },
  }
}
