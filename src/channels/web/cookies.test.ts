import { expect, test } from "bun:test"
import { bearerToken, authToken, authedViaBearer, sameOriginOk } from "./cookies"

const reqWith = (headers: Record<string, string>) => new Request("https://x.test/ws", { headers })

test("bearerToken extracts the token from an Authorization: Bearer header", () => {
  expect(bearerToken(reqWith({ authorization: "Bearer abc123" }))).toBe("abc123")
})

test("bearerToken is empty when no/!bearer auth header", () => {
  expect(bearerToken(reqWith({}))).toBe("")
  expect(bearerToken(reqWith({ authorization: "Basic Zm9v" }))).toBe("")
})

test("authToken prefers cookie, falls back to bearer", () => {
  expect(authToken(reqWith({ cookie: "cmux_token=cook" }))).toBe("cook")
  expect(authToken(reqWith({ authorization: "Bearer bear" }))).toBe("bear")
  expect(authToken(reqWith({ cookie: "cmux_token=cook", authorization: "Bearer bear" }))).toBe("cook")
})

test("authedViaBearer is true only when bearer (not cookie) supplied the token", () => {
  expect(authedViaBearer(reqWith({ authorization: "Bearer bear" }))).toBe(true)
  expect(authedViaBearer(reqWith({ cookie: "cmux_token=cook" }))).toBe(false)
  expect(authedViaBearer(reqWith({}))).toBe(false)
})

// sameOriginOk is the CSRF guard, and mutation testing found it completely
// unasserted: flipping `if (!origin) return true` to `return false` left the
// whole suite green. Both directions matter and both are easy to "tidy" into a
// vulnerability or an outage —
//   - allow-on-missing-Origin is deliberate (curl/scripts carry no ambient
//     cookie to abuse); turning it into a rejection breaks every non-browser
//     client with no test to stop you.
//   - the cross-origin rejection IS the guard; loosening it kills CSRF
//     protection silently, because nothing user-visible changes.

test("sameOriginOk allows a request with no Origin header (non-browser client)", () => {
  expect(sameOriginOk(reqWith({}), "https://x.test")).toBe(true)
})

test("sameOriginOk accepts an Origin matching any of the app's public URLs", () => {
  expect(sameOriginOk(reqWith({ origin: "https://x.test" }), "https://x.test")).toBe(true)
  expect(sameOriginOk(reqWith({ origin: "https://alt.test" }), "https://x.test", "https://alt.test")).toBe(true)
})

test("sameOriginOk rejects a cross-origin request — this is the CSRF guard", () => {
  expect(sameOriginOk(reqWith({ origin: "https://evil.test" }), "https://x.test")).toBe(false)
  // Same host, different scheme/port is still a different origin.
  expect(sameOriginOk(reqWith({ origin: "http://x.test" }), "https://x.test")).toBe(false)
  expect(sameOriginOk(reqWith({ origin: "https://x.test:8443" }), "https://x.test")).toBe(false)
})

test("sameOriginOk rejects when no public URL is configured or either URL is unparseable", () => {
  expect(sameOriginOk(reqWith({ origin: "https://x.test" }))).toBe(false)
  expect(sameOriginOk(reqWith({ origin: "https://x.test" }), undefined)).toBe(false)
  expect(sameOriginOk(reqWith({ origin: "not-a-url" }), "https://x.test")).toBe(false)
  expect(sameOriginOk(reqWith({ origin: "https://x.test" }), "not-a-url")).toBe(false)
})
