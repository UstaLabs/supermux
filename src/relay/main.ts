import { readFileSync } from "node:fs"
import { createRelayCore } from "./core"
import { makeRelayHandler } from "./server"
import { createApnsAdapter } from "./apns"
import { createFcmAdapter, createServiceAccountTokenGetter } from "./fcm"
import { createTokenCodec, type RelayKeyset } from "./token-codec"
import { createInMemoryRateLimiter, createRedisRateLimiter, type RedisLike } from "./rate-limiter"
import { makeLogger } from "../shared/log"

// Parse an env var as a positive integer; fall back to `d` for missing/empty/NaN/0.
const intEnv = (v: string | undefined, d: number): number => {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : d
}

function loadKeyset(env: NodeJS.ProcessEnv): RelayKeyset {
  const keys = new Map<string, Buffer>(); let currentKeyId = ""
  if (env.MUX_RELAY_TOKEN_KEYS) {
    env.MUX_RELAY_TOKEN_KEYS.split(",").forEach((pair, i) => {
      const [id, b64] = pair.split(":"); keys.set(id!, Buffer.from(b64!, "base64")); if (i === 0) currentKeyId = id!
    })
  } else if (env.MUX_RELAY_TOKEN_KEY) { currentKeyId = "k1"; keys.set("k1", Buffer.from(env.MUX_RELAY_TOKEN_KEY, "base64")) }
  if (!currentKeyId || (keys.get(currentKeyId)?.length ?? 0) !== 32) throw new Error("set MUX_RELAY_TOKEN_KEY(S) to 32-byte base64 key(s)")
  return { currentKeyId, keys }
}

// Minimal RedisLike over Bun's built-in Redis client. Connects lazily (the client
// dials on first command), so an unreachable Redis never crashes startup — the
// limiter wraps any error and falls back to in-memory.
function makeRedisClient(url: string): RedisLike {
  const client = new Bun.RedisClient(url)
  return {
    async incrWithExpiry(key, windowSeconds) {
      const n = await client.incr(key)
      if (n === 1) await client.expire(key, windowSeconds)
      return n
    },
  }
}

const env = process.env
const log = makeLogger("relay/main")
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
const codec = createTokenCodec(loadKeyset(env))
const inMem = createInMemoryRateLimiter()
const limiter = env.MUX_RELAY_REDIS_URL ? createRedisRateLimiter(makeRedisClient(env.MUX_RELAY_REDIS_URL), inMem) : inMem
const core = createRelayCore({
  codec,
  apns,
  fcm,
  limiter,
  ttlSeconds: intEnv(env.MUX_RELAY_TOKEN_TTL_DAYS, 90) * 86400,
  ratePerMin: intEnv(env.MUX_RELAY_RATE_PER_MIN, 30),
  globalRatePerMin: intEnv(env.MUX_RELAY_GLOBAL_PER_MIN, 6000),
})
const handler = makeRelayHandler(core)

// per-IP /register limiter (defense against mass registration)
const registerPerMin = intEnv(env.MUX_RELAY_REGISTER_PER_MIN, 10)
const port = intEnv(env.MUX_RELAY_PORT, 8788)

Bun.serve({
  port,
  async fetch(req, server) {
    if (req.method === "POST" && new URL(req.url).pathname === "/register") {
      const ip = server.requestIP(req)?.address ?? "?"
      if (!(await limiter.allow(`reg:${ip}`, registerPerMin))) return new Response(JSON.stringify({ error: "rate" }), { status: 429 })
    }
    return handler(req)
  },
})
log.info("relay_ready", { port, fcm: !!sa, apnsSandbox: env.MUX_APNS_SANDBOX === "1", redis: !!env.MUX_RELAY_REDIS_URL })
