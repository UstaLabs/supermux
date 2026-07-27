import type { TokenCodec } from "./token-codec"
import type { RateLimiter } from "./rate-limiter"
import type { PlatformPushAdapter } from "../core/push/native-sender"

export interface RelayCore {
  register(platform: "ios" | "android", pushToken: string): Promise<{ routingToken: string; status: "pending" }>
  push(routingToken: string, ciphertext: string): Promise<{ ok: true } | { ok: false; gone: boolean }>
  unregister(routingToken: string): void
}
export function createRelayCore(o: {
  codec: TokenCodec; apns: PlatformPushAdapter; fcm: PlatformPushAdapter; limiter: RateLimiter
  ttlSeconds: number; ratePerMin: number; globalRatePerMin: number
}): RelayCore {
  const adapterFor = (p: "ios" | "android") => (p === "ios" ? o.apns : o.fcm)
  return {
    async register(platform, pushToken) {
      const routingToken = o.codec.seal({ platform, pushToken, ttlSeconds: o.ttlSeconds })
      // Best-effort bootstrap push. Result is intentionally ignored for the HTTP
      // response — clients now also receive routingToken over HTTP (see server.ts).
      try {
        const send = await adapterFor(platform).send(
          pushToken,
          { ciphertext: JSON.stringify({ kind: "bootstrap", routingToken }) } as any,
          { silent: true },
        )
        if (!send.ok) {
          // Soft-fail: HTTP still returns routingToken so registration can complete.
          // gone=true usually means a dead/malformed device token (common in tests).
        }
      } catch {
        // Same — network/FCM auth blips must not block client-side /push/device.
      }
      return { routingToken, status: "pending" }
    },
    async push(routingToken, ciphertext) {
      const r = o.codec.open(routingToken)
      if (!r.ok) return { ok: false, gone: true }
      if (!(await o.limiter.allow(routingToken, o.ratePerMin))) return { ok: false, gone: false }
      if (!(await o.limiter.allow("__global__", o.globalRatePerMin))) return { ok: false, gone: false }
      return adapterFor(r.platform).send(r.pushToken, { ciphertext } as any)
    },
    unregister() { /* stateless: nothing to delete; tokens expire via TTL */ },
  }
}
