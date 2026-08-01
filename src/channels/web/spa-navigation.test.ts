// Regression: vue-router pages share top-level paths with REST APIs
// (/settings, /devices, /usage, /proxies, /displays). Browser document
// navigations (refresh / PWA relaunch) must get the SPA shell, while fetch()
// to the same path must keep returning JSON.
import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, isDocumentNavigation, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => {
  if (channel) {
    await channel.stop()
    channel = undefined
  }
})

function base(): string {
  return `http://127.0.0.1:${channel!.boundPort}`
}

function mintToken(devicesFile: string): string {
  return new DeviceStore(devicesFile).mint("test-device").token
}

function makeChannel(opts: Partial<WebChannelOpts> = {}): { channel: WebChannel; devicesFile: string; staticDir: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-spa-nav-"))
  const devicesFile = join(dir, "devices.json")
  const staticDir = join(dir, "static")
  mkdirSync(staticDir, { recursive: true })
  writeFileSync(join(staticDir, "index.html"), "<!doctype html><html><body>SPA-SHELL</body></html>")
  const full: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    staticDir,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...opts,
  }
  return { channel: new WebChannel(full), devicesFile, staticDir }
}

test("isDocumentNavigation: Sec-Fetch-Dest document wins; empty/fetch is not", () => {
  expect(isDocumentNavigation(new Request("http://x/", {
    headers: { "sec-fetch-dest": "document", accept: "*/*" },
  }))).toBe(true)
  expect(isDocumentNavigation(new Request("http://x/", {
    headers: { "sec-fetch-dest": "empty", accept: "text/html" },
  }))).toBe(false)
  expect(isDocumentNavigation(new Request("http://x/", {
    headers: { accept: "text/html,application/xhtml+xml" },
  }))).toBe(true)
  expect(isDocumentNavigation(new Request("http://x/", {
    headers: { accept: "*/*" },
  }))).toBe(false)
  expect(isDocumentNavigation(new Request("http://x/"))).toBe(false)
})

test("browser refresh of /settings and /settings/* gets SPA shell, not 404", async () => {
  const made = makeChannel()
  channel = made.channel
  await channel.start()

  for (const path of ["/settings", "/settings/assistant", "/settings/voice", "/settings/editor"]) {
    const res = await fetch(`${base()}${path}`, {
      headers: {
        "sec-fetch-dest": "document",
        "sec-fetch-mode": "navigate",
        accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      },
    })
    expect(res.status).toBe(200)
    expect(res.headers.get("content-type") ?? "").toContain("text/html")
    expect(await res.text()).toContain("SPA-SHELL")
  }
})

test("browser refresh of shared API/Vue paths (/devices, /usage, /proxies, /displays) gets SPA shell", async () => {
  const made = makeChannel()
  channel = made.channel
  await channel.start()

  for (const path of ["/devices", "/usage", "/proxies", "/displays"]) {
    const res = await fetch(`${base()}${path}`, {
      headers: {
        "sec-fetch-dest": "document",
        accept: "text/html,application/xhtml+xml",
      },
    })
    expect(res.status).toBe(200)
    expect(res.headers.get("content-type") ?? "").toContain("text/html")
    expect(await res.text()).toContain("SPA-SHELL")
  }
})

test("API fetch to shared paths still returns JSON (not SPA shell)", async () => {
  const made = makeChannel()
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  // Same path as the DevicesView page — client fetch must keep getting JSON.
  const devices = await fetch(`${base()}/devices`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(devices.status).toBe(200)
  expect(devices.headers.get("content-type") ?? "").toContain("application/json")
  const list = await devices.json() as unknown[]
  expect(Array.isArray(list)).toBe(true)

  // Nested settings API under the same prefix as the Settings SPA tree.
  const editor = await fetch(`${base()}/settings/editor`, {
    headers: { authorization: `Bearer ${token}` },
  })
  // Without an editor settings provider this is 503 JSON, not HTML.
  expect(editor.status).toBe(503)
  expect(editor.headers.get("content-type") ?? "").toContain("application/json")
})

test("Accept: text/html alone (no Sec-Fetch) still serves SPA on /settings", async () => {
  const made = makeChannel()
  channel = made.channel
  await channel.start()

  const res = await fetch(`${base()}/settings`, {
    headers: { accept: "text/html" },
  })
  expect(res.status).toBe(200)
  expect(await res.text()).toContain("SPA-SHELL")
})

// Regression: the SPA-shell fallback above is checked BEFORE the route handlers,
// so it also swallowed `/pair?t=…` — the link a QR scan opens. That handler's
// entire job is to verify the token, set the HttpOnly auth cookie and 302 to "/",
// none of which the SPA can do, so every pairing link silently degraded to the
// manual paste screen and the device never got a cookie. Caught by the browser
// journey, not by any unit test, because the shell response is a valid 200.
test("document navigation to /pair?t= pairs server-side instead of getting the shell", async () => {
  const made = makeChannel()
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const res = await fetch(`${base()}/pair?t=${encodeURIComponent(token)}`, {
    redirect: "manual",
    headers: {
      "sec-fetch-dest": "document",
      "sec-fetch-mode": "navigate",
      accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    },
  })

  expect(res.status).toBe(302)
  expect(res.headers.get("location")).toBe("/")
  expect(res.headers.get("set-cookie") ?? "").toContain("HttpOnly")
})

test("bare /pair (no token) is still a real SPA route", async () => {
  const made = makeChannel()
  channel = made.channel
  await channel.start()

  const res = await fetch(`${base()}/pair`, {
    headers: {
      "sec-fetch-dest": "document",
      "sec-fetch-mode": "navigate",
      accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    },
  })
  expect(res.status).toBe(200)
  expect(await res.text()).toContain("SPA-SHELL")
})
