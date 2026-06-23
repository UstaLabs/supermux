import { expect, test } from "bun:test"
import { randomBytes } from "node:crypto"
import { createTokenCodec, type RelayKeyset } from "./token-codec"

function keyset(ids: string[]): RelayKeyset {
  const keys = new Map(ids.map((id) => [id, randomBytes(32)]))
  return { currentKeyId: ids[0]!, keys }
}
const seed = { platform: "ios" as const, pushToken: "apns-tok-xyz", ttlSeconds: 3600 }

test("seal then open round-trips platform + pushToken", () => {
  const c = createTokenCodec(keyset(["k1"]))
  const tok = c.seal(seed)
  expect(tok.startsWith("r1.k1.")).toBe(true)
  expect(c.open(tok)).toEqual({ ok: true, platform: "ios", pushToken: "apns-tok-xyz" })
})

test("an expired token opens as expired", () => {
  let t = 1000
  const c = createTokenCodec(keyset(["k1"]), () => t)
  const tok = c.seal({ ...seed, ttlSeconds: 10 })
  t = 2000
  expect(c.open(tok)).toEqual({ ok: false, reason: "expired" })
})

test("a tampered token is invalid", () => {
  const c = createTokenCodec(keyset(["k1"]))
  const tok = c.seal(seed)
  const bad = tok.slice(0, -2) + (tok.endsWith("A") ? "B" : "A")
  expect(c.open(bad)).toMatchObject({ ok: false, reason: "invalid" })
})

test("an unknown keyId is invalid; garbage is invalid", () => {
  const c = createTokenCodec(keyset(["k1"]))
  expect(c.open("r1.kZ.abc")).toMatchObject({ ok: false, reason: "invalid" })
  expect(c.open("not-a-token")).toMatchObject({ ok: false, reason: "invalid" })
})

test("rotation: a token sealed under an old key still opens while that key is in the set", () => {
  const ks = keyset(["k2", "k1"])
  const oldOnly = { currentKeyId: "k1", keys: new Map([["k1", ks.keys.get("k1")!]]) }
  const tokFromOld = createTokenCodec(oldOnly).seal(seed)
  expect(createTokenCodec(ks).open(tokFromOld)).toMatchObject({ ok: true, pushToken: "apns-tok-xyz" })
})

test("editing the keyId in the string fails (AAD-bound)", () => {
  const ks = keyset(["k1", "k9"])
  const tok = createTokenCodec(ks).seal(seed)
  const swapped = tok.replace("r1.k1.", "r1.k9.")
  expect(createTokenCodec(ks).open(swapped)).toMatchObject({ ok: false, reason: "invalid" })
})
