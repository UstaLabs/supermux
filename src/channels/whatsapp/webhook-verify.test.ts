import { describe, expect, test } from "bun:test"
import { createHmac } from "crypto"
import { verifyGowaSignature } from "./webhook-verify"

const SECRET = "topsecret"
const BODY = JSON.stringify({ event: "message", payload: { id: "X" } })
const goodSig = "sha256=" + createHmac("sha256", SECRET).update(BODY, "utf8").digest("hex")

describe("verifyGowaSignature", () => {
  test("accepts a correctly-signed body", () => {
    expect(verifyGowaSignature(BODY, goodSig, SECRET)).toBe(true)
  })
  test("rejects a tampered body", () => {
    expect(verifyGowaSignature(BODY + " ", goodSig, SECRET)).toBe(false)
  })
  test("rejects wrong secret and missing header", () => {
    expect(verifyGowaSignature(BODY, goodSig, "other")).toBe(false)
    expect(verifyGowaSignature(BODY, null, SECRET)).toBe(false)
  })
})
