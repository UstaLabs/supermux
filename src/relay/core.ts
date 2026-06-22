import type { RelayStore } from "./store"
import type { PlatformPushAdapter } from "../core/push/native-sender"

export interface RelayCore {
  register(platform: "ios" | "android", pushToken: string): Promise<{ routingToken: string; status: "pending" }>
  push(routingToken: string, ciphertext: string): Promise<{ ok: true } | { ok: false; gone: boolean }>
  unregister(routingToken: string): void
}

export function createRelayCore(o: { store: RelayStore; apns: PlatformPushAdapter; fcm: PlatformPushAdapter; ratePerMin: number }): RelayCore {
  const hits = new Map<string, number[]>()
  const adapterFor = (p: "ios" | "android") => (p === "ios" ? o.apns : o.fcm)

  function allowed(rt: string): boolean {
    const now = Date.now(), win = hits.get(rt)?.filter((t) => now - t < 60_000) ?? []
    if (win.length >= o.ratePerMin) { hits.set(rt, win); return false }
    win.push(now); hits.set(rt, win); return true
  }

  return {
    async register(platform, pushToken) {
      const routingToken = o.store.register(platform, pushToken)
      await adapterFor(platform).send(pushToken, { ciphertext: JSON.stringify({ kind: "bootstrap", routingToken }) } as any)
      return { routingToken, status: "pending" }
    },
    async push(routingToken, ciphertext) {
      const route = o.store.lookup(routingToken)
      if (!route) return { ok: false, gone: true }
      if (!allowed(routingToken)) return { ok: false, gone: false }
      const res = await adapterFor(route.platform).send(route.pushToken, { ciphertext } as any)
      if (res.ok === false && res.gone) o.store.unregister(routingToken)
      return res
    },
    unregister(routingToken) { o.store.unregister(routingToken) },
  }
}
