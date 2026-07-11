import { expect, test } from "bun:test"
import { handleAuthOp } from "./auth-plugin"
import { mintLease } from "./lease"

const SECRET = "s"
const ctx = { secret: SECRET, now: () => 1000 }

test("Login with a valid lease is accepted", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "Login", content: { metas: { lease } } }, ctx)
  expect(r).toEqual({ reject: false, unchange: true })
})

test("Login with no lease is rejected", () => {
  const r = handleAuthOp({ op: "Login", content: { metas: {} } }, ctx)
  expect(r.reject).toBe(true)
})

test("NewProxy claiming the leased subdomain is accepted", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, proxy_config: { subdomain: "h-habc", proxy_type: "http" } } }, ctx)
  expect(r.reject).toBe(false)
})

test("NewProxy claiming a DIFFERENT host's subdomain is rejected (GATE 1)", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, proxy_config: { subdomain: "h-hbbb", proxy_type: "http" } } }, ctx)
  expect(r.reject).toBe(true)
})

test("NewProxy for a non-http proxy type is rejected", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, proxy_config: { subdomain: "h-habc", proxy_type: "tcp" } } }, ctx)
  expect(r.reject).toBe(true)
})
