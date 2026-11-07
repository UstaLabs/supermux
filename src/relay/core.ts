import type { TokenCodec } from "./token-codec"
import type { RateLimiter } from "./rate-limiter"
import type { PlatformPushAdapter } from "../core/push/native-sender"
import { makeLogger, type Logger } from "../shared/log"

export type PushOutcome = { ok: true } | { ok: false; gone: boolean; reason?: string }

export interface RelayCore {
  register(platform: "ios" | "android", pushToken: string): Promise<{ routingToken: string; status: "pending" }>
  push(routingToken: string, ciphertext: string): Promise<PushOutcome>
  unregister(routingToken: string): void
}
export function createRelayCore(o: {
  codec: TokenCodec; apns: PlatformPushAdapter; fcm: PlatformPushAdapter; limiter: RateLimiter
  ttlSeconds: number; ratePerMin: number; globalRatePerMin: number
  log?: Logger
}): RelayCore {
  const adapterFor = (p: "ios" | "android") => (p === "ios" ? o.apns : o.fcm)
  // One line per push and per registration (card #86), never with a token in it:
  // the push token addresses a real phone and the routing token is a credential.
  const log = o.log ?? makeLogger("relay/core")
  const send = async (platform: "ios" | "android", token: string, payload: any, opts?: { silent?: boolean }): Promise<PushOutcome> => {
    try {
      return await adapterFor(platform).send(token, payload, opts)
    } catch (err: any) {
      return { ok: false, gone: false, reason: `send_failed: ${err?.message ?? String(err)}` }
    }
  }
  return {
    async register(platform, pushToken) {
      const routingToken = o.codec.seal({ platform, pushToken, ttlSeconds: o.ttlSeconds })
      // Best-effort bootstrap push. Result is intentionally ignored for the HTTP
      // response — clients now also receive routingToken over HTTP (see server.ts).
      // gone=true usually means a dead/malformed device token (common in tests).
      const bootstrap = await send(platform, pushToken, { ciphertext: JSON.stringify({ kind: "bootstrap", routingToken }) }, { silent: true })
      log.info("relay_register", { platform, bootstrap: bootstrap.ok ? "ok" : (bootstrap.reason ?? "failed") })
      return { routingToken, status: "pending" }
    },
    async push(routingToken, ciphertext) {
      const r = o.codec.open(routingToken)
      if (!r.ok) {
        const out = { ok: false as const, gone: true, reason: `routing_token_${r.reason}` }
        log.warn("relay_push", { ok: false, gone: true, reason: out.reason })
        return out
      }
      let out: PushOutcome
      if (!(await o.limiter.allow(routingToken, o.ratePerMin))) out = { ok: false, gone: false, reason: "rate_limited" }
      else if (!(await o.limiter.allow("__global__", o.globalRatePerMin))) out = { ok: false, gone: false, reason: "rate_limited_global" }
      else out = await send(r.platform, r.pushToken, { ciphertext })
      if (out.ok) log.info("relay_push", { platform: r.platform, ok: true })
      else log.warn("relay_push", { platform: r.platform, ok: false, gone: out.gone, reason: out.reason })
      return out
    },
    unregister() { /* stateless: nothing to delete; tokens expire via TTL */ },
  }
}
