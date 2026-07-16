import { expect, test } from "bun:test"
import { ClaimStore } from "./pair-claim"

test("mint returns a 128-bit-plus base64url secret; consume works exactly once", () => {
  const now = 1000
  const store = new ClaimStore({ ttlMs: 5000, clock: () => now })
  const secret = store.mint()
  expect(secret.length).toBeGreaterThanOrEqual(22) // 16 bytes base64url
  expect(store.consume(secret)).toBe(true)
  expect(store.consume(secret)).toBe(false) // already consumed
})

test("expired secret is rejected and swept", () => {
  let now = 1000
  const store = new ClaimStore({ ttlMs: 5000, clock: () => now })
  const secret = store.mint()
  now = 6001
  expect(store.consume(secret)).toBe(false)
})

test("mintWithExpiry returns the exact claim deadline", () => {
  const store = new ClaimStore({ ttlMs: 5000, clock: () => 1000 })
  const claim = store.mintWithExpiry()
  expect(claim.expiresAt).toBe(6000)
  expect(store.consume(claim.secret)).toBe(true)
})

test("unknown secret is rejected", () => {
  const store = new ClaimStore({ ttlMs: 5000, clock: () => 0 })
  expect(store.consume("nope")).toBe(false)
})
