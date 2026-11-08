// The cross-site guard every request passes before it is routed (#104): a request carrying the app's
// cookie must come from a page the app trusts. These are the routes that had no Origin check of their
// own (/ws/display, /ws/scrcpy, /transcribe/stream), a proxied page's origin, and an opaque one.
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

const APP = "http://localhost:8787"
const PROXIED = "https://px.example.test"

async function boot(extra: Partial<WebChannelOpts> = {}) {
  const dir = mkdtempSync(join(tmpdir(), "mux-cross-site-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: APP,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...extra,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("t").token
  return { base: `http://127.0.0.1:${channel.boundPort}`, token }
}

const SOCKETS = [
  ["/ws/display?id=d-1", 404],
  ["/ws/scrcpy?id=d-1", 404],
  ["/transcribe/stream?dictationId=d1", 503],
] as const

for (const [path, passed] of SOCKETS) {
  test(`${path.split("?")[0]} opens only from the app's own origin when the cookie authenticates it`, async () => {
    const { base, token } = await boot()
    const open = (headers: Record<string, string>) => fetch(`${base}${path}`, { headers: { upgrade: "websocket", ...headers } })
    const cookie = `cmux_token=${token}`
    expect((await open({ cookie, origin: PROXIED })).status).toBe(403)
    expect((await open({ cookie, origin: "null" })).status).toBe(403)
    expect((await open({ cookie, origin: "https://evil.example" })).status).toBe(403)
    // Past the guard, the route answers as before.
    expect((await open({ cookie, origin: APP })).status).toBe(passed)
    expect((await open({ cookie })).status).toBe(passed)
    // The native apps authenticate with a bearer token and carry no ambient cookie.
    expect((await open({ authorization: `Bearer ${token}`, origin: PROXIED })).status).toBe(passed)
  })
}

test("the main socket still opens from the app, and not from a proxied page", async () => {
  const { base, token } = await boot()
  const open = (origin: string) => fetch(`${base}/ws`, { headers: { upgrade: "websocket", cookie: `cmux_token=${token}`, origin } })
  expect((await open(PROXIED)).status).toBe(403)
  expect((await open(APP)).status).not.toBe(403)
})

test("a cookie request from an opaque origin or another site is refused, a link from elsewhere is not", async () => {
  const { base, token } = await boot()
  const get = (headers: Record<string, string>) => fetch(`${base}/me`, { headers: { cookie: `cmux_token=${token}`, ...headers } })
  expect((await get({})).status).toBe(200)
  expect((await get({ origin: "null", "sec-fetch-site": "cross-site", "sec-fetch-mode": "cors" })).status).toBe(403)
  expect((await get({ "sec-fetch-site": "cross-site", "sec-fetch-mode": "no-cors", "sec-fetch-dest": "image" })).status).toBe(403)
  expect((await get({ "sec-fetch-site": "cross-site", "sec-fetch-mode": "navigate", "sec-fetch-dest": "iframe" })).status).toBe(403)
  expect((await get({ "sec-fetch-site": "cross-site", "sec-fetch-mode": "navigate", "sec-fetch-dest": "document" })).status).toBe(200)
  expect((await get({ "sec-fetch-site": "same-site", "sec-fetch-mode": "cors", origin: PROXIED })).status).toBe(200)
})

test("a proxied page keeps its own sockets; an opaque origin can't use the cookie there either", async () => {
  upstream = Bun.serve({ port: 0, fetch: () => new Response("upstream") })
  const up = upstream.port!
  const { base, token } = await boot({
    proxyBaseDomain: "example.test", proxyMainHost: "app.example.test",
    proxyLookup: (slug) => (slug === "px" ? { port: up, sessionName: "s", isPublic: true } : undefined),
    proxyAuth: () => false,
  })
  const open = (origin: string) => fetch(`${base}/socket`, {
    headers: { host: "px.example.test", upgrade: "websocket", cookie: `cmux_token=${token}`, origin },
  })
  expect((await open(PROXIED)).status).not.toBe(403)
  expect((await open("null")).status).toBe(403)
  const page = await fetch(`${base}/`, { headers: { host: "px.example.test", cookie: `cmux_token=${token}` } })
  expect(await page.text()).toBe("upstream")
})
