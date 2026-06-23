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
  expect(await core.push("not-a-real-token", "x")).toEqual({ ok: false, gone: true })
})

test("rate limit blocks the N+1th push for a token", async () => {
  const { core } = mk(3)
  const { routingToken } = await core.register("ios", "t")
  for (let i = 0; i < 3; i++) expect((await core.push(routingToken, "x")).ok).toBe(true)
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: false })
})
