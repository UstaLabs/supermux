import { test, expect, describe, afterAll } from "bun:test"
import {
  extractSubdomain,
  buildUpstreamUrl,
  buildUpstreamWsUrl,
  parseCookie,
  handleProxyRequest,
} from "../src/channels/web/proxy"

// --- extractSubdomain ---

describe("extractSubdomain", () => {
  test("returns subdomain for matching host", () => {
    expect(extractSubdomain("myapp.dok.dev", "dok.dev")).toBe("myapp")
  })

  test("returns null when host IS the base domain", () => {
    expect(extractSubdomain("dok.dev", "dok.dev")).toBeNull()
  })

  test("returns null when host IS the main host", () => {
    expect(extractSubdomain("agentmux.dok.dev", "dok.dev", "agentmux.dok.dev")).toBeNull()
  })

  test("returns null when host does not match base domain", () => {
    expect(extractSubdomain("myapp.other.com", "dok.dev")).toBeNull()
  })

  test("strips port before matching — subdomain case", () => {
    expect(extractSubdomain("myapp.dok.dev:8080", "dok.dev")).toBe("myapp")
  })

  test("strips port before matching — base domain case returns null", () => {
    expect(extractSubdomain("dok.dev:443", "dok.dev")).toBeNull()
  })

  test("strips port before matching — main host case returns null", () => {
    expect(extractSubdomain("agentmux.dok.dev:443", "dok.dev", "agentmux.dok.dev")).toBeNull()
  })

  test("returns null for multi-level sub.sub.base", () => {
    expect(extractSubdomain("deep.myapp.dok.dev", "dok.dev")).toBeNull()
  })

  test("returns subdomain with different base domain", () => {
    expect(extractSubdomain("hello.example.com", "example.com")).toBe("hello")
  })

  test("returns null when there is no subdomain portion", () => {
    expect(extractSubdomain("dok.dev", "dok.dev")).toBeNull()
  })
})

// --- buildUpstreamUrl ---

describe("buildUpstreamUrl", () => {
  test("builds correct URL with path", () => {
    expect(buildUpstreamUrl(3000, "/api/hello")).toBe("http://127.0.0.1:3000/api/hello")
  })

  test("builds correct URL with path and query", () => {
    expect(buildUpstreamUrl(8080, "/search?q=foo&page=2")).toBe("http://127.0.0.1:8080/search?q=foo&page=2")
  })

  test("builds correct URL with root path", () => {
    expect(buildUpstreamUrl(4321, "/")).toBe("http://127.0.0.1:4321/")
  })
})

// --- buildUpstreamWsUrl ---

describe("buildUpstreamWsUrl", () => {
  test("builds correct WS URL", () => {
    expect(buildUpstreamWsUrl(3000, "/ws")).toBe("ws://127.0.0.1:3000/ws")
  })

  test("builds WS URL with query params", () => {
    expect(buildUpstreamWsUrl(4000, "/ws?token=abc")).toBe("ws://127.0.0.1:4000/ws?token=abc")
  })
})

// --- parseCookie ---

describe("parseCookie", () => {
  test("parses a single cookie", () => {
    expect(parseCookie("session=abc123", "session")).toBe("abc123")
  })

  test("parses the correct cookie from multiple", () => {
    expect(parseCookie("a=1; session=mytoken; b=2", "session")).toBe("mytoken")
  })

  test("returns null for missing cookie", () => {
    expect(parseCookie("a=1; b=2", "session")).toBeNull()
  })

  test("returns null for null header", () => {
    expect(parseCookie(null, "session")).toBeNull()
  })

  test("handles cookie value with = sign", () => {
    expect(parseCookie("token=abc=def==", "token")).toBe("abc=def==")
  })

  test("handles empty cookie header", () => {
    expect(parseCookie("", "session")).toBeNull()
  })

  test("handles whitespace around cookie names", () => {
    expect(parseCookie("a=1;  session=xyz", "session")).toBe("xyz")
  })
})

// --- handleProxyRequest integration ---

describe("handleProxyRequest", () => {
  let upstreamServer: ReturnType<typeof Bun.serve> | undefined

  afterAll(async () => {
    upstreamServer?.stop()
  })

  test("forwards request to upstream and returns response", async () => {
    upstreamServer = Bun.serve({
      port: 0,
      fetch(req) {
        return new Response(JSON.stringify({ path: new URL(req.url).pathname }), {
          headers: { "content-type": "application/json" },
        })
      },
    })
    const port = (upstreamServer as any).port as number

    const req = new Request(`http://myapp.dok.dev/api/test?foo=bar`)
    const res = await handleProxyRequest(req, { port, sessionName: "myapp" })

    expect(res.status).toBe(200)
    const body = await res.json() as { path: string }
    expect(body.path).toBe("/api/test")
  })

  test("rewrites Host header to upstream", async () => {
    let capturedHost = ""
    const srv = Bun.serve({
      port: 0,
      fetch(req) {
        capturedHost = req.headers.get("host") ?? ""
        return new Response("ok")
      },
    })
    const port = (srv as any).port as number

    const req = new Request("http://myapp.dok.dev/")
    await handleProxyRequest(req, { port, sessionName: "myapp" })
    srv.stop()

    expect(capturedHost).toBe(`127.0.0.1:${port}`)
  })

  test("strips cf-* headers before forwarding", async () => {
    const capturedHeaders: Record<string, string | null> = {}
    const srv = Bun.serve({
      port: 0,
      fetch(req) {
        capturedHeaders["cf-connecting-ip"] = req.headers.get("cf-connecting-ip")
        capturedHeaders["cf-ray"] = req.headers.get("cf-ray")
        capturedHeaders["cf-visitor"] = req.headers.get("cf-visitor")
        return new Response("ok")
      },
    })
    const port = (srv as any).port as number

    const req = new Request("http://myapp.dok.dev/", {
      headers: {
        "cf-connecting-ip": "1.2.3.4",
        "cf-ray": "abc123",
        "cf-visitor": '{"scheme":"https"}',
      },
    })
    await handleProxyRequest(req, { port, sessionName: "myapp" })
    srv.stop()

    expect(capturedHeaders["cf-connecting-ip"]).toBeNull()
    expect(capturedHeaders["cf-ray"]).toBeNull()
    expect(capturedHeaders["cf-visitor"]).toBeNull()
  })

  test("strips transfer-encoding from response headers", async () => {
    const srv = Bun.serve({
      port: 0,
      fetch(_req) {
        return new Response("chunked body", {
          headers: { "transfer-encoding": "chunked", "x-custom": "keep" },
        })
      },
    })
    const port = (srv as any).port as number

    const req = new Request("http://myapp.dok.dev/")
    const res = await handleProxyRequest(req, { port, sessionName: "myapp" })
    srv.stop()

    expect(res.headers.get("transfer-encoding")).toBeNull()
    expect(res.headers.get("x-custom")).toBe("keep")
  })

  test("returns 502 when upstream is not running", async () => {
    // Use a port that is very unlikely to have anything listening
    const req = new Request("http://myapp.dok.dev/")
    const res = await handleProxyRequest(req, { port: 19999, sessionName: "dead-app" })

    expect(res.status).toBe(502)
    const text = await res.text()
    expect(text).toContain("19999")
    expect(text).toContain("not responding")
  })

  test("forwards request body for POST", async () => {
    let capturedBody = ""
    const srv = Bun.serve({
      port: 0,
      async fetch(req) {
        capturedBody = await req.text()
        return new Response("got it")
      },
    })
    const port = (srv as any).port as number

    const req = new Request("http://myapp.dok.dev/echo", {
      method: "POST",
      body: "hello from proxy",
      headers: { "content-type": "text/plain" },
    })
    const res = await handleProxyRequest(req, { port, sessionName: "myapp" })
    srv.stop()

    expect(res.status).toBe(200)
    expect(capturedBody).toBe("hello from proxy")
  })

  test("proxies query string correctly", async () => {
    let capturedSearch = ""
    const srv = Bun.serve({
      port: 0,
      fetch(req) {
        capturedSearch = new URL(req.url).search
        return new Response("ok")
      },
    })
    const port = (srv as any).port as number

    const req = new Request("http://myapp.dok.dev/path?key=value&other=123")
    await handleProxyRequest(req, { port, sessionName: "myapp" })
    srv.stop()

    expect(capturedSearch).toBe("?key=value&other=123")
  })
})
