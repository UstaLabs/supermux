import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { RelayStore } from "./store"
import { createRelayCore } from "./core"
import { makeRelayHandler } from "./server"

function handler() {
  const a = { send: () => Promise.resolve({ ok: true as const }) }
  const core = createRelayCore({ store: new RelayStore(new Database(":memory:")), apns: a, fcm: a, ratePerMin: 100 })
  return makeRelayHandler(core)
}
const req = (path: string, body: any) => new Request("http://x" + path, { method: "POST", body: JSON.stringify(body) })

test("POST /register returns 202 pending (token not in the response)", async () => {
  const res = await handler()(req("/register", { platform: "ios", pushToken: "t" }))
  expect(res.status).toBe(202)
  expect(await res.json()).toEqual({ status: "pending" })
})

test("POST /push with an unknown token returns gone", async () => {
  const res = await handler()(req("/push", { routingToken: "nope", ciphertext: "x" }))
  expect(await res.json()).toMatchObject({ ok: false, gone: true })
})

test("rejects malformed bodies with 400", async () => {
  const res = await handler()(req("/register", { platform: "windows", pushToken: "t" }))
  expect(res.status).toBe(400)
})
