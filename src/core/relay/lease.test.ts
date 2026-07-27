import { expect, test } from "bun:test"
import { mintLease, verifyLease } from "./lease"

const SECRET = "relay-hmac-secret"

test("missing and malformed leases report distinct failures", () => {
  expect(verifyLease("", { secret: SECRET, now: 2_000 })).toEqual({ ok: false, reason: "missing" })
  expect(verifyLease("not-a-lease", { secret: SECRET, now: 2_000 })).toEqual({ ok: false, reason: "malformed" })
  for (const lease of [".2000.sig", "habc..sig", "habc.2000."]) {
    expect(verifyLease(lease, { secret: SECRET, now: 2_000 })).toEqual({ ok: false, reason: "malformed" })
  }
})

test("a freshly minted lease includes its verified host and expiry", () => {
  const valid = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })
  expect(verifyLease(valid, { secret: SECRET, now: 2_000 })).toEqual({
    ok: true,
    hostId: "habc",
    expiresAt: 6_000,
  })
})

test("correctly signed positive safe-integer expiry boundaries are valid through their expiry", () => {
  for (const expiresAt of [1, Number.MAX_SAFE_INTEGER]) {
    const valid = mintLease({ hostId: "habc", secret: SECRET, ttlMs: expiresAt, now: 0 })
    expect(verifyLease(valid, { secret: SECRET, now: expiresAt })).toEqual({
      ok: true,
      hostId: "habc",
      expiresAt,
    })
  }
})

test("a correctly signed expired lease includes verified metadata", () => {
  const valid = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })
  expect(verifyLease(valid, { secret: SECRET, now: 7_000 })).toEqual({
    ok: false,
    reason: "expired",
    hostId: "habc",
    expiresAt: 6_000,
  })
})

test("a tampered hostId fails the signature", () => {
  const valid = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })
  const forged = valid.replace("habc", "hxyz")
  expect(verifyLease(forged, { secret: SECRET, now: 2_000 })).toEqual({
    ok: false,
    reason: "invalid_signature",
  })
})

test("invalid expiry boundaries do not expose unverified metadata", () => {
  for (const expiry of ["0", "-1", "1e-1", String(Number.MAX_SAFE_INTEGER + 1)]) {
    expect(verifyLease(`habc.${expiry}.unsigned`, { secret: SECRET, now: 2_000 })).toEqual({
      ok: false,
      reason: "invalid_expiry",
    })
  }
})

test("wrong secret fails", () => {
  const valid = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })
  expect(verifyLease(valid, { secret: "other", now: 2_000 })).toEqual({
    ok: false,
    reason: "invalid_signature",
  })
})
