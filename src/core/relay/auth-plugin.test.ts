import { expect, test } from "bun:test"
import { handleAuthOp, type AuthRejectionEvent } from "./auth-plugin"
import { mintLease } from "./lease"

const SECRET = "s"
const ctx = { secret: SECRET, now: () => 1000 }

test("valid operations are accepted without rejection events", () => {
  const events: AuthRejectionEvent[] = []
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const observedCtx = { ...ctx, onReject: (event: AuthRejectionEvent) => events.push(event) }

  const login = handleAuthOp({ op: "Login", content: { metas: { lease } } }, observedCtx)
  const proxy = handleAuthOp({
    op: "NewProxy",
    content: { user: { metas: { lease } }, subdomain: "h-habc", proxy_type: "http" },
  }, observedCtx)

  expect(login).toEqual({ reject: false, unchange: true })
  expect(proxy).toEqual({ reject: false, unchange: true })
  expect(events).toEqual([])
})

test("expired signed Login leases emit verified metadata without leaking the lease", () => {
  const events: AuthRejectionEvent[] = []
  const observedCtx = {
    secret: SECRET,
    now: () => 7_000,
    onReject: (event: AuthRejectionEvent) => events.push(event),
  }
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })

  const response = handleAuthOp({
    op: "Login",
    content: { metas: { lease }, client_address: "203.0.113.9:1234" },
  }, observedCtx)

  expect(response).toEqual({ reject: true, reject_reason: "invalid or missing lease" })
  expect(events).toEqual([{
    operation: "Login",
    reason: "expired_lease",
    hostId: "habc",
    leaseExpiresAt: 6_000,
    expiredByMs: 1_000,
    clientAddress: "203.0.113.9:1234",
  }])
  expect(JSON.stringify(events)).not.toContain(lease)
})

test("Login with no lease emits missing_lease and keeps the external reason generic", () => {
  const events: AuthRejectionEvent[] = []

  const response = handleAuthOp(
    { op: "Login", content: { metas: {}, client_address: "203.0.113.10:4321" } },
    { ...ctx, onReject: (event: AuthRejectionEvent) => events.push(event) },
  )

  expect(response).toEqual({ reject: true, reject_reason: "invalid or missing lease" })
  expect(events).toEqual([{
    operation: "Login",
    reason: "missing_lease",
    clientAddress: "203.0.113.10:4321",
  }])
})

test("Login with a forged lease emits invalid_lease_signature without unverified metadata", () => {
  const events: AuthRejectionEvent[] = []
  const lease = mintLease({ hostId: "habc", secret: "wrong-secret", ttlMs: 5_000, now: 1_000 })

  const response = handleAuthOp(
    { op: "Login", content: { metas: { lease } } },
    { ...ctx, onReject: (event: AuthRejectionEvent) => events.push(event) },
  )

  expect(response).toEqual({ reject: true, reject_reason: "invalid or missing lease" })
  expect(events).toEqual([{ operation: "Login", reason: "invalid_lease_signature" }])
  expect(JSON.stringify(events)).not.toContain("habc")
})

// NewProxy content shape matches frp 0.61 exactly (flat subdomain/proxy_type,
// lease under user.metas) — captured live in the spike.
test("NewProxy claiming the leased subdomain is accepted", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, subdomain: "h-habc", proxy_type: "http" } }, ctx)
  expect(r.reject).toBe(false)
})

test("NewProxy claiming a DIFFERENT host's subdomain is rejected (GATE 1)", () => {
  const events: AuthRejectionEvent[] = []
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const response = handleAuthOp(
    { op: "NewProxy", content: { user: { metas: { lease } }, subdomain: "h-hbbb", proxy_type: "http" } },
    { ...ctx, onReject: (event: AuthRejectionEvent) => events.push(event) },
  )

  expect(response).toEqual({ reject: true, reject_reason: "subdomain does not match leased hostId" })
  expect(events).toEqual([{
    operation: "NewProxy",
    reason: "subdomain_mismatch",
    hostId: "habc",
    subdomain: "h-hbbb",
  }])
  expect(JSON.stringify(events)).not.toContain(lease)
})

test("NewProxy for a non-http proxy type is rejected", () => {
  const events: AuthRejectionEvent[] = []
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const response = handleAuthOp(
    { op: "NewProxy", content: { user: { metas: { lease } }, subdomain: "h-habc", proxy_type: "tcp" } },
    { ...ctx, onReject: (event: AuthRejectionEvent) => events.push(event) },
  )

  expect(response).toEqual({ reject: true, reject_reason: "only http proxies permitted" })
  expect(events).toEqual([{
    operation: "NewProxy",
    reason: "unsupported_proxy_type",
    hostId: "habc",
    proxyType: "tcp",
  }])
  expect(JSON.stringify(events)).not.toContain(lease)
})
