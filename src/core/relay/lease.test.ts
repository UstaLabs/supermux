import { expect, test } from "bun:test"
import { mintLease, verifyLease } from "./lease"

const SECRET = "relay-hmac-secret"

test("a freshly minted lease verifies for its hostId", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = verifyLease(lease, { secret: SECRET, now: 2000 })
  expect(r.ok).toBe(true)
  if (r.ok) expect(r.hostId).toBe("habc")
})

test("an expired lease fails", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  expect(verifyLease(lease, { secret: SECRET, now: 7000 }).ok).toBe(false)
})

test("a tampered hostId fails the signature", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const forged = lease.replace("habc", "hxyz")
  expect(verifyLease(forged, { secret: SECRET, now: 2000 }).ok).toBe(false)
})

test("wrong secret fails", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  expect(verifyLease(lease, { secret: "other", now: 2000 }).ok).toBe(false)
})
