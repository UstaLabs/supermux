import { test, expect, describe, afterAll } from "bun:test"
import {
  matchProxyPath,
  buildProxyPublicUrl,
  rewriteLocation,
  rewriteSetCookiePath,
  handleProxyRequest,
} from "../src/channels/web/proxy"

describe("matchProxyPath", () => {
  test("bare /p/<slug> → rest null (caller adds trailing slash)", () => {
    expect(matchProxyPath("/p/app")).toEqual({ slug: "app", rest: null })
  })
  test("/p/<slug>/ → rest '/'", () => {
    expect(matchProxyPath("/p/app/")).toEqual({ slug: "app", rest: "/" })
  })
  test("/p/<slug>/deep/path → rest is remainder", () => {
    expect(matchProxyPath("/p/app/assets/x.js")).toEqual({ slug: "app", rest: "/assets/x.js" })
  })
  test("does not match the broker's own /pair or /proxies", () => {
    expect(matchProxyPath("/pair")).toBeNull()
    expect(matchProxyPath("/proxies")).toBeNull()
  })
  test("empty slug does not match", () => {
    expect(matchProxyPath("/p/")).toBeNull()
    expect(matchProxyPath("/p")).toBeNull()
  })
  test("does not swallow a query/fragment into the slug", () => {
    expect(matchProxyPath("/p/ap?p")).toBeNull()
    expect(matchProxyPath("/p/ap#x")).toBeNull()
  })
})

describe("buildProxyPublicUrl", () => {
  test("subdomain mode when base domain set", () => {
    expect(buildProxyPublicUrl("app", { baseDomain: "dok.dev", publicUrl: "https://mux.dok.dev" }))
      .toBe("https://app.dok.dev")
  })
  test("path mode under public URL when no base domain", () => {
    expect(buildProxyPublicUrl("app", { publicUrl: "https://mux.example.com" }))
      .toBe("https://mux.example.com/p/app/")
  })
  test("path mode strips a trailing slash on the public URL", () => {
    expect(buildProxyPublicUrl("app", { publicUrl: "https://mux.example.com/" }))
      .toBe("https://mux.example.com/p/app/")
  })
})

describe("rewriteLocation", () => {
  const prefix = "/p/app"
  const host = "127.0.0.1:3000"
  test("absolute-path Location gets the prefix", () => {
    expect(rewriteLocation("/login", prefix, host)).toBe("/p/app/login")
  })
  test("root Location gets the prefix", () => {
    expect(rewriteLocation("/", prefix, host)).toBe("/p/app/")
  })
  test("loopback absolute URL is reduced to its path and prefixed", () => {
    expect(rewriteLocation("http://127.0.0.1:3000/next", prefix, host)).toBe("/p/app/next")
  })
  test("protocol-relative URL is left alone", () => {
    expect(rewriteLocation("//evil.com/x", prefix, host)).toBe("//evil.com/x")
  })
  test("off-host absolute URL is left alone", () => {
    expect(rewriteLocation("https://other.com/x", prefix, host)).toBe("https://other.com/x")
  })
  test("relative Location is left alone", () => {
    expect(rewriteLocation("next", prefix, host)).toBe("next")
  })
  test("already-prefixed Location is left unchanged (idempotent)", () => {
    expect(rewriteLocation("/p/app/login", prefix, host)).toBe("/p/app/login")
  })
})

describe("rewriteSetCookiePath", () => {
  const prefix = "/p/app"
  test("Path=/ becomes Path=<prefix>/", () => {
    expect(rewriteSetCookiePath("sid=1; Path=/; HttpOnly", prefix)).toBe("sid=1; Path=/p/app/; HttpOnly")
  })
  test("Path=/admin becomes Path=<prefix>/admin (other attrs preserved)", () => {
    expect(rewriteSetCookiePath("sid=1; Path=/admin; HttpOnly", prefix)).toBe("sid=1; Path=/p/app/admin; HttpOnly")
  })
  test("no Path attribute → scoped to <prefix>/", () => {
    expect(rewriteSetCookiePath("sid=1; HttpOnly", prefix)).toBe("sid=1; HttpOnly; Path=/p/app/")
  })
  test("already-scoped cookie Path is left unchanged (idempotent)", () => {
    expect(rewriteSetCookiePath("sid=1; Path=/p/app/x", prefix)).toBe("sid=1; Path=/p/app/x")
  })
})

describe("handleProxyRequest path mode", () => {
  let srv: ReturnType<typeof Bun.serve> | undefined
  afterAll(() => srv?.stop())

  test("strips the /p/<slug> prefix before forwarding upstream", async () => {
    srv = Bun.serve({
      port: 0,
      fetch(req) {
        return new Response(JSON.stringify({ path: new URL(req.url).pathname }), {
          headers: { "content-type": "application/json" },
        })
      },
    })
    const port = (srv as any).port as number
    const req = new Request(`http://broker.example.com/p/app/assets/x.js`)
    const res = await handleProxyRequest(req, { port, sessionName: "app" }, {
      prefix: "/p/app",
      upstreamPath: "/assets/x.js",
    })
    expect(res.status).toBe(200)
    expect((await res.json() as { path: string }).path).toBe("/assets/x.js")
  })

  test("rewrites an absolute-path Location with the prefix", async () => {
    const s = Bun.serve({
      port: 0,
      fetch() {
        return new Response(null, { status: 302, headers: { location: "/login" } })
      },
    })
    const port = (s as any).port as number
    const req = new Request("http://broker.example.com/p/app/")
    const res = await handleProxyRequest(req, { port, sessionName: "app" }, {
      prefix: "/p/app",
      upstreamPath: "/",
    })
    s.stop()
    expect(res.status).toBe(302)
    expect(res.headers.get("location")).toBe("/p/app/login")
  })

  test("rewrites Set-Cookie Path to the prefix", async () => {
    const s = Bun.serve({
      port: 0,
      fetch() {
        return new Response("ok", { headers: { "set-cookie": "sid=1; Path=/" } })
      },
    })
    const port = (s as any).port as number
    const req = new Request("http://broker.example.com/p/app/")
    const res = await handleProxyRequest(req, { port, sessionName: "app" }, {
      prefix: "/p/app",
      upstreamPath: "/",
    })
    s.stop()
    expect(res.headers.getSetCookie().some((c) => c.includes("Path=/p/app/"))).toBe(true)
  })
})
