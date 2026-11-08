import { expect, test } from "bun:test"
import { bearerToken, authToken, authedViaBearer, crossSiteRefusal, sameOriginOk } from "./cookies"

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

// crossSiteRefusal is the one guard every request passes before it is routed (#104). A request that
// carries the app's cookie is the browser acting for the user, so it must come from a page the app
// trusts. WebKit sends the SameSite=Lax cookie even from a sandboxed srcdoc frame (measured
// 2026-11-08), so SameSite alone can't be the line.
const app = { appOrigins: ["https://app.test", "https://h-x.relay.test"], proxied: false }
const cookie = { cookie: "cmux_token=cook" }
const ws = { upgrade: "websocket" }
const nav = { "sec-fetch-site": "cross-site", "sec-fetch-mode": "navigate", "sec-fetch-dest": "document" }

test("crossSiteRefusal leaves requests without the app's cookie alone", () => {
  expect(crossSiteRefusal(reqWith({ origin: "null" }), app)).toBeUndefined()
  expect(crossSiteRefusal(reqWith({ "sec-fetch-site": "cross-site" }), app)).toBeUndefined()
  expect(crossSiteRefusal(reqWith({ ...ws, origin: "https://px.app.test" }), app)).toBeUndefined()
  // Bearer clients (the native apps) carry no ambient credential to ride.
  expect(crossSiteRefusal(reqWith({ authorization: "Bearer b", origin: "null", ...ws }), app)).toBeUndefined()
})

test("crossSiteRefusal refuses an opaque origin carrying the cookie", () => {
  expect(crossSiteRefusal(reqWith({ ...cookie, origin: "null" }), app)).toBeDefined()
  expect(crossSiteRefusal(reqWith({ ...cookie, origin: "null", ...ws }), app)).toBeDefined()
  expect(crossSiteRefusal(reqWith({ ...cookie, origin: "null" }), { ...app, proxied: true })).toBeDefined()
})

test("crossSiteRefusal refuses a cross-site request except a top-level GET navigation", () => {
  const xs = (h: Record<string, string>, method = "GET") =>
    crossSiteRefusal(new Request("https://app.test/files/f1", { method, headers: { ...cookie, ...h } }), app)
  expect(xs({ "sec-fetch-site": "cross-site", "sec-fetch-mode": "cors", "sec-fetch-dest": "empty" })).toBeDefined()
  expect(xs({ "sec-fetch-site": "cross-site", "sec-fetch-mode": "no-cors", "sec-fetch-dest": "image" })).toBeDefined()
  expect(xs({ ...nav, "sec-fetch-dest": "iframe" })).toBeDefined()
  expect(xs(nav, "POST")).toBeDefined()
  // A link from somewhere else (mail, chat, another site) still opens the app.
  expect(xs(nav)).toBeUndefined()
  expect(xs(nav, "HEAD")).toBeUndefined()
  expect(xs({ "sec-fetch-site": "same-site", "sec-fetch-mode": "cors" })).toBeUndefined()
  expect(xs({ "sec-fetch-site": "same-origin", "sec-fetch-mode": "cors" })).toBeUndefined()
  expect(xs({ "sec-fetch-site": "none", "sec-fetch-mode": "navigate" })).toBeUndefined()
  expect(xs({})).toBeUndefined()
})

test("crossSiteRefusal lets the app's own WebSockets open only from the app's own origins", () => {
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "https://app.test" }), app)).toBeUndefined()
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "https://h-x.relay.test" }), app)).toBeUndefined()
  // A non-browser client sends no Origin and has no ambient cookie of its own to abuse.
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws }), app)).toBeUndefined()
  // A proxied page on <slug>.<base> gets the Domain=.<base> cookie: it must not open the app's sockets.
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "https://px.app.test" }), app)).toBeDefined()
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "https://evil.test" }), app)).toBeDefined()
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "not-a-url" }), app)).toBeDefined()
})

test("crossSiteRefusal lets a proxied page open its own WebSockets", () => {
  // A proxied app's socket comes from the proxied page's own origin, which is not the app's.
  const proxied = { appOrigins: ["https://app.test"], proxied: true }
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "https://px.app.test" }), proxied)).toBeUndefined()
  expect(crossSiteRefusal(reqWith({ ...cookie, ...ws, origin: "https://px.app.test", "sec-fetch-site": "cross-site" }), proxied)).toBeDefined()
})
