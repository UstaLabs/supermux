// The port proxy must never hand supermux's own credentials to the proxied app: a dev server an agent runs would
// otherwise receive the user's device token (the cmux_token cookie, or a bearer token) on every request it serves.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { withoutCookies } from "./proxy"

let channel: WebChannel | undefined
let upstream: ReturnType<typeof Bun.serve> | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  if (upstream) { upstream.stop(true); upstream = undefined }
})

/** An upstream app that answers with the credentials it was handed. */
function echoUpstream() {
  upstream = Bun.serve({ port: 0, fetch: (req) => Response.json({ cookie: req.headers.get("cookie"), authorization: req.headers.get("authorization") }) })
  return upstream.port!
}

async function boot(extra: Partial<WebChannelOpts>) {
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-proxy-creds-")), "devices.json")
  const devices = new DeviceStore(devicesFile)
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    proxyAuth: (token) => !!devices.verify(token),
    ...extra,
  })
  await channel.start()
  return { base: `http://127.0.0.1:${channel.boundPort}`, token: devices.mint("phone").token }
}

test("withoutCookies drops the named cookies and keeps the app's own", () => {
  expect(withoutCookies("cmux_token=a; app_session=b; theme=dark", ["cmux_token"])).toBe("app_session=b; theme=dark")
  expect(withoutCookies("app=1;cmux_token=a;cmux_token=c", ["cmux_token"])).toBe("app=1")
  expect(withoutCookies("cmux_token=a", ["cmux_token"])).toBeNull()
  expect(withoutCookies("xcmux_token=a; cmux_token_b=c", ["cmux_token"])).toBe("xcmux_token=a; cmux_token_b=c")
})

for (const mode of ["path", "subdomain"] as const) {
  test(`${mode} mode: a private preview gets the app's cookies and its own bearer, never the device token`, async () => {
    const port = echoUpstream()
    const lookup = (slug: string) => (slug === "px" ? { port, sessionName: "s", isPublic: false } : undefined)
    const { base, token } = await boot(mode === "path"
      ? { proxyLookup: lookup }
      : { proxyLookup: lookup, proxyBaseDomain: "example.test", proxyMainHost: "app.example.test" })
    const url = mode === "path" ? `${base}/p/px/echo` : `${base}/echo`
    const host: Record<string, string> = mode === "path" ? {} : { host: "px.example.test" }
    const seen = async (headers: Record<string, string>) => {
      const res = await fetch(url, { headers: { ...host, ...headers } })
      expect(res.status).toBe(200)
      return res.json() as Promise<{ cookie: string | null; authorization: string | null }>
    }

    // The cookie authenticates the preview, and only the app's own cookies go on.
    expect(await seen({ cookie: `cmux_token=${token}; app_session=abc` })).toEqual({ cookie: "app_session=abc", authorization: null })
    expect(await seen({ cookie: `cmux_token=${token}` })).toEqual({ cookie: null, authorization: null })
    // A device token sent as a bearer is supermux's too.
    expect(await seen({ cookie: `cmux_token=${token}`, authorization: `Bearer ${token}` })).toEqual({ cookie: null, authorization: null })
    // The app's own Authorization header is the app's business.
    expect(await seen({ cookie: `cmux_token=${token}`, authorization: "Bearer the-apps-own-token" }))
      .toEqual({ cookie: null, authorization: "Bearer the-apps-own-token" })
  })
}
