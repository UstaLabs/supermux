import { expect, test } from "bun:test"
import { randomBytes } from "node:crypto"
import { createTokenCodec } from "./token-codec"
import { createInMemoryRateLimiter } from "./rate-limiter"
import { createRelayCore } from "./core"

function mk(ratePerMin = 100) {
  const sent: any[] = []
  const adapter = { send: (token: string, p: any, opts?: any) => { sent.push({ token, p, opts }); return Promise.resolve({ ok: true as const }) } }
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) })
  const core = createRelayCore({ codec, apns: adapter, fcm: adapter, limiter: createInMemoryRateLimiter(), ttlSeconds: 3600, ratePerMin, globalRatePerMin: 1000 })
  return { core, sent, codec }
}

test("register seals a token and silently bootstrap-pushes it to the device", async () => {
  const { core, sent, codec } = mk()
  const { routingToken } = await core.register("ios", "apns-tok")
  expect(sent).toHaveLength(1)
  expect(sent[0].token).toBe("apns-tok")
  expect(sent[0].opts).toEqual({ silent: true })
  expect(codec.open(routingToken)).toMatchObject({ ok: true, pushToken: "apns-tok" })
})

test("push opens the token and forwards the ciphertext", async () => {
  const { core, sent } = mk()
  const { routingToken } = await core.register("android", "fcm-tok")
  sent.length = 0
  expect(await core.push(routingToken, "CIPHER")).toEqual({ ok: true })
  expect(sent[0]).toMatchObject({ token: "fcm-tok", p: { ciphertext: "CIPHER" } })
})

test("push with an invalid/garbage token returns gone", async () => {
  const { core } = mk()
  expect(await core.push("not-a-real-token", "x")).toEqual({ ok: false, gone: true, reason: "routing_token_invalid" })
})

test("rate limit blocks the N+1th push for a token", async () => {
  const { core } = mk(3)
  const { routingToken } = await core.register("ios", "t")
  for (let i = 0; i < 3; i++) expect((await core.push(routingToken, "x")).ok).toBe(true)
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: false, reason: "rate_limited" })
})

function recorder() {
  const lines: { level: string; msg: string; extra?: Record<string, unknown> }[] = []
  const at = (level: string) => (msg: string, extra?: Record<string, unknown>) => { lines.push({ level, msg, extra }) }
  return { log: { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") }, lines }
}

// Card #86: the relay printed a stack trace for a DNS failure and nothing at all
// for a delivered or refused push. Each push is now one line, without tokens.
test("a push that cannot reach Apple or Google answers with the reason instead of throwing", async () => {
  const { log, lines } = recorder()
  const failing = { send: async () => { throw new Error("getaddrinfo EAI_AGAIN api.push.apple.com") } }
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) })
  const core = createRelayCore({ codec, apns: failing, fcm: failing, limiter: createInMemoryRateLimiter(), ttlSeconds: 3600, ratePerMin: 100, globalRatePerMin: 1000, log })
  const { routingToken } = await core.register("ios", "apns-secret-token")
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: false, reason: "send_failed: getaddrinfo EAI_AGAIN api.push.apple.com" })
  expect(lines.at(-1)).toEqual({ level: "warn", msg: "relay_push", extra: { platform: "ios", ok: false, gone: false, reason: "send_failed: getaddrinfo EAI_AGAIN api.push.apple.com" } })
  expect(JSON.stringify(lines)).not.toContain("apns-secret-token")
  expect(JSON.stringify(lines)).not.toContain(routingToken)
})

test("logs each push and registration without its tokens", async () => {
  const { log, lines } = recorder()
  const ok = { send: async () => ({ ok: true as const }) }
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) })
  const core = createRelayCore({ codec, apns: ok, fcm: ok, limiter: createInMemoryRateLimiter(), ttlSeconds: 3600, ratePerMin: 100, globalRatePerMin: 1000, log })
  const { routingToken } = await core.register("android", "fcm-secret-token")
  await core.push(routingToken, "x")
  expect(lines).toEqual([
    { level: "info", msg: "relay_register", extra: { platform: "android", bootstrap: "ok" } },
    { level: "info", msg: "relay_push", extra: { platform: "android", ok: true } },
  ])
  expect(JSON.stringify(lines)).not.toContain("fcm-secret-token")
})

test("an expired routing token is gone, by name", async () => {
  let now = 1_000
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) }, () => now)
  const ok = { send: async () => ({ ok: true as const }) }
  const core = createRelayCore({ codec, apns: ok, fcm: ok, limiter: createInMemoryRateLimiter(), ttlSeconds: 60, ratePerMin: 100, globalRatePerMin: 1000 })
  const { routingToken } = await core.register("ios", "t")
  now += 61
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: true, reason: "routing_token_expired" })
})
