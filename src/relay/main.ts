import { Database } from "bun:sqlite"
import { readFileSync } from "node:fs"
import { RelayStore } from "./store"
import { createRelayCore } from "./core"
import { makeRelayHandler } from "./server"
import { createApnsAdapter } from "./apns"
import { createFcmAdapter, createServiceAccountTokenGetter } from "./fcm"
import { makeLogger } from "../shared/log"

// Parse an env var as a positive integer; fall back to `d` for missing/empty/NaN/0.
const intEnv = (v: string | undefined, d: number): number => {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : d
}

const env = process.env
const log = makeLogger("relay/main")
const store = new RelayStore(new Database(env.MUX_RELAY_DB ?? "relay.db"))
const apns = createApnsAdapter({
  keyP8: env.MUX_APNS_KEY_P8 ?? "",
  keyId: env.MUX_APNS_KEY_ID ?? "",
  teamId: env.MUX_APNS_TEAM_ID ?? "",
  bundleId: env.MUX_APNS_BUNDLE_ID ?? "dev.supermux.ios",
  sandbox: env.MUX_APNS_SANDBOX === "1",
})
const sa = env.MUX_FCM_SA_JSON ? JSON.parse(readFileSync(env.MUX_FCM_SA_JSON, "utf8")) : null
const fcm = createFcmAdapter({
  projectId: env.MUX_FCM_PROJECT_ID ?? sa?.project_id ?? "",
  getAccessToken: sa
    ? createServiceAccountTokenGetter(sa)
    : async () => { throw new Error("FCM not configured") },
})
const core = createRelayCore({
  store,
  apns,
  fcm,
  ratePerMin: intEnv(env.MUX_RELAY_RATE_PER_MIN, 30),
  globalRatePerMin: intEnv(env.MUX_RELAY_GLOBAL_PER_MIN, 6000),
})
const handler = makeRelayHandler(core)

// per-IP /register limiter (defense against mass registration)
const ipHits = new Map<string, number[]>()
const registerPerMin = intEnv(env.MUX_RELAY_REGISTER_PER_MIN, 10)
const port = intEnv(env.MUX_RELAY_PORT, 8788)

Bun.serve({
  port,
  fetch(req, server) {
    if (req.method === "POST" && new URL(req.url).pathname === "/register") {
      const ip = server.requestIP(req)?.address ?? "?"
      const now = Date.now()
      const win = (ipHits.get(ip) ?? []).filter((t) => now - t < 60_000)
      if (win.length >= registerPerMin) return new Response(JSON.stringify({ error: "rate" }), { status: 429 })
      win.push(now)
      ipHits.set(ip, win)
    }
    return handler(req)
  },
})
log.info("relay_ready", { port, fcm: !!sa, apnsSandbox: env.MUX_APNS_SANDBOX === "1" })
