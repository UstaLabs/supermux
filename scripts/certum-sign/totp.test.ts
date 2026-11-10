// #116: the one-time code SimplySign's login takes as its password, computed in-process from the otpauth URI.
import { expect, test } from "bun:test"
import { codeAt, parseOtpUri, windowOf } from "./totp"

// RFC 6238 appendix B, SHA-256 seed, the last 6 digits (Certum's codes are 6 digits, 30 s).
const SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
const uri = `otpauth://totp/Certum:me@example.com?secret=${SEED}&algorithm=SHA256&digits=6&period=30&issuer=Certum`

test("RFC 6238's SHA-256 vectors, at 6 digits", () => {
  const p = parseOtpUri(uri)
  expect(codeAt(p, 59)).toBe("119246")
  expect(codeAt(p, 1111111109)).toBe("084774")
  expect(codeAt(p, 1111111111)).toBe("062674")
  expect(codeAt(p, 1234567890)).toBe("819424")
  expect(codeAt(p, 2000000000)).toBe("698825")
})

test("the URI's algorithm, digits and period are read; Certum's defaults otherwise", () => {
  const p = parseOtpUri(`otpauth://totp/x?secret=${SEED}`)
  expect([p.algorithm, p.digits, p.period]).toEqual(["sha256", 6, 30])
  const q = parseOtpUri(`otpauth://totp/x?secret=${SEED.toLowerCase()}&algorithm=SHA1&digits=8&period=60`)
  expect([q.algorithm, q.digits, q.period]).toEqual(["sha1", 8, 60])
})

test("a malformed URI is refused without echoing it", () => {
  for (const bad of ["", "https://x?secret=AAAA", "otpauth://hotp/x?secret=AAAA", "otpauth://totp/x", `otpauth://totp/x?secret=${SEED}&algorithm=MD5`]) {
    let message = ""
    try { parseOtpUri(bad) } catch (e) { message = String(e) }
    expect(message).toMatch(/otpauth/)
    if (bad.includes("secret=")) expect(message).not.toContain(bad.split("secret=")[1]!.slice(0, 4))
  }
})

test("the window a time falls in, and the seconds left in it", () => {
  const p = parseOtpUri(uri)
  expect(windowOf(p, 59)).toEqual({ index: 1, secondsLeft: 1 })
  expect(windowOf(p, 60)).toEqual({ index: 2, secondsLeft: 30 })
})
