// #105 by-port previews: /p/<digits>/ (path mode) and <digits>.<base> (subdomain mode) open 127.0.0.1:<port> on the host with no
// registration and no stored proxy. It is the private-preview door: the same login as a private named proxy, never public, through
// the same cross-site guard and the same credential stripping. GET /proxies/by-port hands the apps the URL template.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
let upstream: ReturnType<typeof Bun.serve> | undefined
let decoy: ReturnType<typeof Bun.serve> | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  if (upstream) { upstream.stop(true); upstream = undefined }
  if (decoy) { decoy.stop(true); decoy = undefined }
})

type Seen = { path: string; host: string | null; cookie: string | null; authorization: string | null }

/** An upstream app that says what it was asked and with which credentials, and echoes WebSocket messages. */
function startUpstream(label = "app") {
  return Bun.serve<{ path: string }>({
    port: 0,
    fetch(req, server) {
      const u = new URL(req.url)
      if (req.headers.get("upgrade")?.toLowerCase() === "websocket") {
        return server.upgrade(req, { data: { path: u.pathname + u.search } }) ? undefined : new Response("no", { status: 400 })
      }
      return Response.json({ label, path: u.pathname + u.search, host: req.headers.get("host"), cookie: req.headers.get("cookie"), authorization: req.headers.get("authorization") })
    },
    websocket: { open(ws) { ws.send(`open:${ws.data.path}`) }, message(ws, m) { ws.send(`echo:${m}`) } },
  })
}

async function boot(extra: Partial<WebChannelOpts> = {}) {
  upstream = startUpstream()
  const port = upstream.port!
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-by-port-")), "devices.json")
  const devices = new DeviceStore(devicesFile)
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "https://app.example.test",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    // Nothing is registered: by-port must not need a stored proxy.
    proxyLookup: () => undefined,
    proxyAuth: (token) => !!devices.verify(token),
    ...extra,
  })
  await channel.start()
  const brokerPort = channel.boundPort
  const base = `http://127.0.0.1:${brokerPort}`
  const device = devices.mint("phone").token
  const bearer = { authorization: `Bearer ${device}` }
  const preview = () => devices.mintPreview(device)!
  return { base, brokerPort, port, devices, device, bearer, preview }
}

const asSeen = async (res: Response) => (await res.json()) as Seen

// ---- path mode ----

test("path mode: /p/<port>/ opens 127.0.0.1:<port> with the /p/<port> prefix stripped, no registration needed", async () => {
  const { base, port, bearer } = await boot()
  const res = await fetch(`${base}/p/${port}/hello/world?q=1`, { headers: bearer })
  expect(res.status).toBe(200)
  const seen = await asSeen(res)
  expect(seen.path).toBe("/hello/world?q=1")
  expect(seen.host).toBe(`127.0.0.1:${port}`)
})

test("path mode: bare /p/<port> redirects to /p/<port>/ like a named preview", async () => {
  const { base, port, bearer } = await boot()
  const res = await fetch(`${base}/p/${port}?x=1`, { headers: bearer, redirect: "manual" })
  expect(res.status).toBe(301)
  expect(res.headers.get("location")).toBe(`/p/${port}/?x=1`)
})

test("it is never public: no credential, or a wrong one, is a 401, as for a private named proxy", async () => {
  const { base, port } = await boot()
  expect((await fetch(`${base}/p/${port}/`)).status).toBe(401)
  expect((await fetch(`${base}/p/${port}/`, { headers: { cookie: "cmux_token=nope" } })).status).toBe(401)
  expect((await fetch(`${base}/p/${port}/`, { headers: { authorization: "Bearer nope" } })).status).toBe(401)
  expect((await fetch(`${base}/p/${port}/`, { headers: { cookie: "cmux_preview=nope" } })).status).toBe(401)
})

test("the login cookie and the preview cookie both open it, and the app sees neither", async () => {
  const { base, port, device, preview } = await boot()
  const viaCookie = await fetch(`${base}/p/${port}/x`, { headers: { cookie: `cmux_token=${device}; app=1` } })
  expect(viaCookie.status).toBe(200)
  expect(await asSeen(viaCookie)).toMatchObject({ cookie: "app=1", authorization: null })
  const viaPreview = await fetch(`${base}/p/${port}/x`, { headers: { cookie: `cmux_preview=${preview()}; app=1` } })
  expect(viaPreview.status).toBe(200)
  expect(await asSeen(viaPreview)).toMatchObject({ cookie: "app=1", authorization: null })
})

test("the device token never reaches the upstream: the cookie and the bearer are both stripped, the app's own Authorization is not", async () => {
  const { base, port, device } = await boot()
  const seen = async (headers: Record<string, string>) => {
    const res = await fetch(`${base}/p/${port}/echo`, { headers })
    expect(res.status).toBe(200)
    return asSeen(res)
  }
  expect(await seen({ cookie: `cmux_token=${device}` })).toMatchObject({ cookie: null, authorization: null })
  expect(await seen({ authorization: `Bearer ${device}` })).toMatchObject({ cookie: null, authorization: null })
  expect(await seen({ cookie: `cmux_token=${device}`, authorization: `Bearer ${device}` })).toMatchObject({ cookie: null, authorization: null })
  expect(await seen({ cookie: `cmux_token=${device}`, authorization: "Bearer the-apps-own-token" }))
    .toMatchObject({ cookie: null, authorization: "Bearer the-apps-own-token" })
})

test("the cross-site guard covers it: an opaque origin or a cross-site fetch carrying the cookie is refused", async () => {
  const { base, port, device } = await boot()
  const cookie = `cmux_token=${device}`
  expect((await fetch(`${base}/p/${port}/`, { headers: { cookie, origin: "null" } })).status).toBe(403)
  expect((await fetch(`${base}/p/${port}/`, { headers: { cookie, "sec-fetch-site": "cross-site", "sec-fetch-mode": "cors" } })).status).toBe(403)
  // A link followed from another site is a top-level navigation and still passes, as for a named preview.
  expect((await fetch(`${base}/p/${port}/`, { headers: { cookie, "sec-fetch-site": "cross-site", "sec-fetch-mode": "navigate", "sec-fetch-dest": "document" } })).status).toBe(200)
})

test("a digits slug that is not a usable port is a 404, even signed in", async () => {
  const { base, bearer } = await boot()
  for (const slug of ["0", "65536", "99999999", "05173", "000"]) {
    expect((await fetch(`${base}/p/${slug}/`, { headers: bearer })).status).toBe(404)
  }
  // Bare, too: no redirect to a 404.
  expect((await fetch(`${base}/p/65536`, { headers: bearer, redirect: "manual" })).status).toBe(404)
})

test("a digits-only named proxy stored before the rule loses to the port route", async () => {
  decoy = startUpstream("decoy")
  const { base, port, bearer } = await boot({
    proxyLookup: (slug) => (slug === String(port0()) ? { port: decoy!.port!, sessionName: "old", isPublic: false } : undefined),
  })
  expect(port).toBe(port0())
  const res = await fetch(`${base}/p/${port}/`, { headers: bearer })
  expect(((await res.json()) as { label: string }).label).toBe("app")
})
// proxyLookup is built before the upstream's port is known, so it asks the running upstream.
function port0(): number { return upstream!.port! }

test("path mode: a WebSocket opens through /p/<port>/ and carries messages both ways; without a login it is refused", async () => {
  const { brokerPort, port, bearer } = await boot()
  const ws = new WebSocket(`ws://127.0.0.1:${brokerPort}/p/${port}/sock?a=1`, { headers: bearer } as any)
  const got: string[] = []
  await new Promise<void>((resolve, reject) => {
    ws.onmessage = (e) => {
      got.push(String(e.data))
      if (got.length === 1) ws.send("hi")
      else resolve()
    }
    ws.onerror = () => reject(new Error("socket error"))
    setTimeout(() => reject(new Error(`timed out with ${JSON.stringify(got)}`)), 5000)
  })
  ws.close()
  expect(got).toEqual(["open:/sock?a=1", "echo:hi"])

  const refused = new WebSocket(`ws://127.0.0.1:${brokerPort}/p/${port}/sock`)
  const outcome = await new Promise<string>((resolve) => {
    refused.onopen = () => resolve("open")
    refused.onerror = () => resolve("refused")
    refused.onclose = () => resolve("refused")
  })
  expect(outcome).toBe("refused")
})

// ---- the broker's own port ----

test("the broker's own port is not reachable by port: its direct-loopback facts stay on this computer", async () => {
  // A request the proxy forwards arrives from loopback with Host 127.0.0.1:<port> and no forwarding header, which the broker
  // reads as "the desktop app on this computer" and answers GET /host with the local-only facts (state dir, install mode).
  const hostInfo = { hostId: "h1", name: "box", platform: "linux", version: "1", protocolVersion: 1, stateDir: "/secret/state-dir", mode: "source" as const }
  const { base, brokerPort, bearer, preview } = await boot({ getHostInfo: () => hostInfo })
  expect(await (await fetch(`${base}/host`)).text()).toContain("/secret/state-dir")

  for (const headers of [bearer, { cookie: `cmux_preview=${preview()}` }]) {
    const res = await fetch(`${base}/p/${brokerPort}/host`, { headers })
    expect(res.status).toBe(404)
    expect(await res.text()).not.toContain("/secret/state-dir")
  }
})

// ---- subdomain mode ----

const sub = { proxyBaseDomain: "example.test", proxyMainHost: "app.example.test" }

test("subdomain mode: <port>.<base> opens 127.0.0.1:<port>, private, with the device token stripped", async () => {
  const { base, port, device, preview } = await boot(sub)
  const host = { host: `${port}.example.test` }
  expect((await fetch(`${base}/echo`, { headers: host })).status).toBe(401)
  const res = await fetch(`${base}/echo?q=1`, { headers: { ...host, cookie: `cmux_token=${device}; app=1`, authorization: `Bearer ${device}` } })
  expect(res.status).toBe(200)
  expect(await asSeen(res)).toMatchObject({ path: "/echo?q=1", host: `127.0.0.1:${port}`, cookie: "app=1", authorization: null })
  expect((await fetch(`${base}/echo`, { headers: { ...host, cookie: `cmux_preview=${preview()}` } })).status).toBe(200)
})

test("subdomain mode: the cross-site guard applies, and an unusable port is a 404", async () => {
  const { base, port, device } = await boot(sub)
  const headers = { host: `${port}.example.test`, cookie: `cmux_token=${device}` }
  expect((await fetch(`${base}/`, { headers: { ...headers, origin: "null" } })).status).toBe(403)
  for (const slug of ["0", "65536", "07"]) {
    expect((await fetch(`${base}/`, { headers: { ...headers, host: `${slug}.example.test` } })).status).toBe(404)
  }
})

test("subdomain mode: a WebSocket opens through <port>.<base>", async () => {
  const { brokerPort, port, bearer } = await boot(sub)
  const ws = new WebSocket(`ws://127.0.0.1:${brokerPort}/sock`, { headers: { ...bearer, host: `${port}.example.test` } } as any)
  const first = await new Promise<string>((resolve, reject) => {
    ws.onmessage = (e) => resolve(String(e.data))
    ws.onerror = () => reject(new Error("socket error"))
    setTimeout(() => reject(new Error("timed out")), 5000)
  })
  ws.close()
  expect(first).toBe("open:/sock")
})

// ---- GET /proxies/by-port ----

test("GET /proxies/by-port answers the URL template with {port}, signed in only", async () => {
  const { base, bearer } = await boot()
  expect((await fetch(`${base}/proxies/by-port`)).status).toBe(401)
  const res = await fetch(`${base}/proxies/by-port`, { headers: bearer })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ url: "https://app.example.test/p/{port}/" })
})

test("GET /proxies/by-port: subdomain mode gives the subdomain form", async () => {
  const { base, bearer } = await boot(sub)
  expect(await (await fetch(`${base}/proxies/by-port`, { headers: bearer })).json()).toEqual({ url: "https://{port}.example.test" })
})

test("GET /proxies/by-port builds on the link base the broker hands out for named proxies (e.g. the relay's)", async () => {
  const { base, bearer } = await boot({ proxyLinksBaseUrl: () => "https://relay.example.test/h/abc/" })
  expect(await (await fetch(`${base}/proxies/by-port`, { headers: bearer })).json()).toEqual({ url: "https://relay.example.test/h/abc/p/{port}/" })
})
