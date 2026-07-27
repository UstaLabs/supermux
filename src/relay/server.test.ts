import { expect, test } from "bun:test"
import { randomBytes } from "node:crypto"
import { createTokenCodec } from "./token-codec"
import { createInMemoryRateLimiter } from "./rate-limiter"
import { createRelayCore } from "./core"
import { makeRelayHandler } from "./server"

function handler() {
  const a = { send: () => Promise.resolve({ ok: true as const }) }
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) })
  const core = createRelayCore({ codec, apns: a, fcm: a, limiter: createInMemoryRateLimiter(), ttlSeconds: 3600, ratePerMin: 100, globalRatePerMin: 1000 })
  return makeRelayHandler(core)
}
const req = (path: string, body: any) => new Request("http://x" + path, { method: "POST", body: JSON.stringify(body) })

test("POST /register returns 202 pending with routingToken", async () => {
  const res = await handler()(req("/register", { platform: "ios", pushToken: "t" }))
  expect(res.status).toBe(202)
  const body = await res.json() as { status: string; routingToken?: string }
  expect(body.status).toBe("pending")
  expect(typeof body.routingToken).toBe("string")
  expect(body.routingToken!.length).toBeGreaterThan(0)
})

test("POST /push with an unknown token returns gone", async () => {
  const res = await handler()(req("/push", { routingToken: "nope", ciphertext: "x" }))
  expect(await res.json()).toMatchObject({ ok: false, gone: true })
})

test("rejects malformed bodies with 400", async () => {
  const res = await handler()(req("/register", { platform: "windows", pushToken: "t" }))
  expect(res.status).toBe(400)
})
