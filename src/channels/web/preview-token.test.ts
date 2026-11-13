// #105 Decision 3a: a Browser tab in the native apps opens the user's private port previews with a preview-only
// token, a cmux_preview cookie the app puts in the tab's own cookie store. It opens previews and nothing else: the
// API, the sockets and the proxied app itself never accept or see it.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
let upstream: ReturnType<typeof Bun.serve> | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  if (upstream) { upstream.stop(true); upstream = undefined }
})

async function boot(extra: Partial<WebChannelOpts> = {}) {
  upstream = Bun.serve({ port: 0, fetch: (req) => Response.json({ cookie: req.headers.get("cookie") }) })
  const port = upstream.port!
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-preview-token-")), "devices.json")
  const devices = new DeviceStore(devicesFile)
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "https://app.example.test",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    proxyLookup: (slug) => (slug === "px" ? { port, sessionName: "s", isPublic: false } : undefined),
    proxyAuth: (token) => !!devices.verify(token),
    ...extra,
  })
  await channel.start()
  const base = `http://127.0.0.1:${channel.boundPort}`
  const device = devices.mint("phone").token
  const mint = (headers: Record<string, string> = { authorization: `Bearer ${device}` }) =>
    fetch(`${base}/devices/preview-token`, { method: "POST", headers })
  return { base, devices, device, mint }
}

test("a native app gets a preview token as a cookie to set in the Browser tab's store, never in its own jar", async () => {
  const { mint } = await boot()
  const res = await mint()
  expect(res.status).toBe(200)
  expect(res.headers.get("set-cookie")).toBeNull()
  const body = await res.json() as { token: string; cookie: Record<string, unknown> }
  expect(body.token.length).toBeGreaterThan(30)
  expect(body.cookie).toEqual({
    name: "cmux_preview", value: body.token, url: "https://app.example.test/p/", path: "/p/",
    httpOnly: true, secure: true, sameSite: "Lax", maxAge: 31536000,
  })
})

test("in subdomain mode the cookie spans the preview hosts", async () => {
  const { mint } = await boot({ proxyBaseDomain: "example.test", proxyMainHost: "app.example.test" })
  const body = await (await mint()).json() as { cookie: Record<string, unknown> }
  expect(body.cookie).toMatchObject({ path: "/", domain: ".example.test", url: "https://app.example.test/" })
})

test("only a signed-in native app can mint one: no credential, or the web app's cookie, is refused", async () => {
  const { mint, device } = await boot()
  expect((await mint({})).status).toBe(401)
  expect((await mint({ cookie: `cmux_token=${device}` })).status).toBe(403)
})

test("the preview token opens a private preview, and the previewed app never sees it", async () => {
  const { base, mint } = await boot()
  const { token } = await (await mint()).json() as { token: string }
  const res = await fetch(`${base}/p/px/page`, { headers: { cookie: `cmux_preview=${token}; app=1` } })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ cookie: "app=1" })
  expect((await fetch(`${base}/p/px/page`, { headers: { cookie: "cmux_preview=not-a-token" } })).status).toBe(401)
})

test("the preview token opens nothing else: not the API, not the sockets", async () => {
  const { base, mint } = await boot()
  const { token } = await (await mint()).json() as { token: string }
  expect((await fetch(`${base}/me`, { headers: { cookie: `cmux_preview=${token}` } })).status).toBe(401)
  expect((await fetch(`${base}/me`, { headers: { authorization: `Bearer ${token}` } })).status).toBe(401)
  expect((await fetch(`${base}/ws`, { headers: { upgrade: "websocket", authorization: `Bearer ${token}` } })).status).toBe(401)
  expect((await fetch(`${base}/devices/preview-token`, { method: "POST", headers: { authorization: `Bearer ${token}` } })).status).toBe(401)
})

test("minting again replaces the token, and revoking the device ends it", async () => {
  const { base, devices, mint } = await boot()
  const first = (await (await mint()).json() as { token: string }).token
  const second = (await (await mint()).json() as { token: string }).token
  const open = (t: string) => fetch(`${base}/p/px/page`, { headers: { cookie: `cmux_preview=${t}` } }).then((r) => r.status)
  expect(await open(first)).toBe(401)
  expect(await open(second)).toBe(200)
  devices.revoke("phone")
  expect(await open(second)).toBe(401)
})

test("the cross-site guard covers the preview cookie too", async () => {
  const { base, mint } = await boot()
  const { token } = await (await mint()).json() as { token: string }
  expect((await fetch(`${base}/p/px/page`, { headers: { cookie: `cmux_preview=${token}`, origin: "null" } })).status).toBe(403)
  expect((await fetch(`${base}/p/px/page`, { headers: { cookie: `cmux_preview=${token}`, "sec-fetch-site": "cross-site", "sec-fetch-mode": "cors" } })).status).toBe(403)
})

// Ahmet's test, 2026-11-10: on a broker with no MUX_WEB_PUBLIC_URL the cookie had no host (a 500), so the Browser tab
// loaded /p/<port>/ without it and got "pair this device first". The cookie must be for the host the by-port links use.
test("the cookie is for the same host as the by-port links: the relay's when the broker has no public URL", async () => {
  const { mint, base, device } = await boot({ publicUrl: undefined, proxyLinksBaseUrl: () => "https://h-x.relay.test" })
  const res = await mint()
  expect(res.status).toBe(200)
  const { cookie } = await res.json() as { cookie: Record<string, unknown> }
  expect(cookie.url).toBe("https://h-x.relay.test/p/")
  const byPort = await (await fetch(`${base}/proxies/by-port`, { headers: { authorization: `Bearer ${device}` } })).json() as { url: string }
  expect(new URL(byPort.url.replace("{port}", "3077")).origin).toBe(new URL(cookie.url as string).origin)
})

test("with neither a public URL nor a relay, the cookie and the links are for the host the app reached", async () => {
  const { mint, base, device } = await boot({ publicUrl: undefined })
  const res = await mint()
  expect(res.status).toBe(200)
  const { cookie } = await res.json() as { cookie: Record<string, unknown> }
  expect(cookie.url).toBe(`${base}/p/`)
  const byPort = await (await fetch(`${base}/proxies/by-port`, { headers: { authorization: `Bearer ${device}` } })).json() as { url: string }
  expect(byPort.url).toBe(`${base}/p/{port}/`)
})
