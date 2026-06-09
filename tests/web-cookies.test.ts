import { test, expect } from "bun:test"
import { buildAuthCookie, buildClearCookie, cookieToken, sameOriginOk, AUTH_COOKIE } from "../src/channels/web/cookies"

test("buildAuthCookie: base attributes", () => {
  const c = buildAuthCookie("tok123", { publicUrl: "https://app.ustalabs.com" })
  expect(c).toContain("cmux_token=tok123")
  expect(c).toContain("HttpOnly")
  expect(c).toContain("Path=/")
  expect(c).toContain("SameSite=Lax")
  expect(c).toContain("Max-Age=31536000")
})

test("Secure: https yes, localhost yes, plain-http LAN no", () => {
  expect(buildAuthCookie("t", { publicUrl: "https://app.ustalabs.com" })).toContain("Secure")
  expect(buildAuthCookie("t", { publicUrl: "http://localhost:8787" })).toContain("Secure")
  expect(buildAuthCookie("t", { publicUrl: "http://192.168.1.9:8787" })).not.toContain("Secure")
})

test("Domain: set to .base when app host is within proxy base; else host-only", () => {
  expect(
    buildAuthCookie("t", { publicUrl: "https://app.ustalabs.com", proxyBaseDomain: "ustalabs.com" }),
  ).toContain("Domain=.ustalabs.com")
  // apex host equal to base
  expect(
    buildAuthCookie("t", { publicUrl: "https://ustalabs.com", proxyBaseDomain: "ustalabs.com" }),
  ).toContain("Domain=.ustalabs.com")
  // unrelated host → no Domain
  expect(
    buildAuthCookie("t", { publicUrl: "https://app.example.com", proxyBaseDomain: "ustalabs.com" }),
  ).not.toContain("Domain=")
  // no proxy base → no Domain
  expect(buildAuthCookie("t", { publicUrl: "https://app.ustalabs.com" })).not.toContain("Domain=")
})

test("buildClearCookie: Max-Age=0, same scope", () => {
  const c = buildClearCookie({ publicUrl: "https://app.ustalabs.com", proxyBaseDomain: "ustalabs.com" })
  expect(c).toContain("cmux_token=;")
  expect(c).toContain("Max-Age=0")
  expect(c).toContain("Domain=.ustalabs.com")
  expect(c).toContain("HttpOnly")
})

test("cookieToken: extracts cmux_token from Cookie header", () => {
  const req = new Request("https://x/", { headers: { cookie: `a=1; ${AUTH_COOKIE}=abc; b=2` } })
  expect(cookieToken(req)).toBe("abc")
  expect(cookieToken(new Request("https://x/"))).toBe("")
})

test("sameOriginOk: accepts same origin, rejects present-but-cross, allows missing", () => {
  const pub = "https://app.ustalabs.com"
  expect(sameOriginOk(new Request("https://app.ustalabs.com/x", { headers: { origin: "https://app.ustalabs.com" } }), pub)).toBe(true)
  expect(sameOriginOk(new Request("https://app.ustalabs.com/x", { headers: { origin: "https://evil.com" } }), pub)).toBe(false)
  // missing Origin → non-browser client (no ambient cookie); allowed (SameSite is the real guard)
  expect(sameOriginOk(new Request("https://app.ustalabs.com/x"), pub)).toBe(true)
})
