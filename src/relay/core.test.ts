import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { RelayStore } from "./store"
import { createRelayCore } from "./core"

function mk() {
  const sent: any[] = []
  const adapter = { send: (token: string, p: any, opts?: any) => { sent.push({ token, p, opts }); return Promise.resolve({ ok: true as const }) } }
  const core = createRelayCore({ store: new RelayStore(new Database(":memory:")), apns: adapter, fcm: adapter, ratePerMin: 5 })
  return { core, sent }
}

test("register mints a token and bootstrap-pushes it to the device", async () => {
  const { core, sent } = mk()
  const { routingToken, status } = await core.register("ios", "apns-tok")
  expect(status).toBe("pending")
  expect(sent).toHaveLength(1)
  expect(sent[0].token).toBe("apns-tok")
  expect(JSON.stringify(sent[0].p)).toContain(routingToken)
})

test("register sends bootstrap push silently (silent:true)", async () => {
  const { core, sent } = mk()
  await core.register("ios", "apns-tok")
  expect(sent[0].opts).toEqual({ silent: true })
})

test("push forwards the ciphertext to the mapped device", async () => {
  const { core, sent } = mk()
  const { routingToken } = await core.register("android", "fcm-tok")
  sent.length = 0
  const r = await core.push(routingToken, "CIPHER")
  expect(r).toEqual({ ok: true })
  expect(sent[0]).toMatchObject({ token: "fcm-tok", p: { ciphertext: "CIPHER" } })
})

test("push to an unknown routing token returns gone", async () => {
  const { core } = mk()
  expect(await core.push("nope", "x")).toEqual({ ok: false, gone: true })
})

test("rate limit blocks the N+1th push in a window", async () => {
  const { core } = mk()
  const { routingToken } = await core.register("ios", "t")
  for (let i = 0; i < 5; i++) expect((await core.push(routingToken, "x")).ok).toBe(true)
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: false })
})

test("global rate ceiling blocks across different routing tokens", async () => {
  const sent: any[] = []
  const adapter = { send: (token: string, p: any) => { sent.push({ token, p }); return Promise.resolve({ ok: true as const }) } }
  // ratePerMin=10 per-token, but globalRatePerMin=3 across all tokens
  const core = createRelayCore({
    store: new RelayStore(new Database(":memory:")),
    apns: adapter,
    fcm: adapter,
    ratePerMin: 10,
    globalRatePerMin: 3,
  })
  const { routingToken: rt1 } = await core.register("ios", "tok1")
  const { routingToken: rt2 } = await core.register("ios", "tok2")
  const { routingToken: rt3 } = await core.register("ios", "tok3")
  sent.length = 0

  // First 3 pushes (across different tokens) should succeed
  expect((await core.push(rt1, "a")).ok).toBe(true)
  expect((await core.push(rt2, "b")).ok).toBe(true)
  expect((await core.push(rt3, "c")).ok).toBe(true)
  // 4th push to a different token hits the global ceiling
  expect(await core.push(rt1, "d")).toEqual({ ok: false, gone: false })
})
