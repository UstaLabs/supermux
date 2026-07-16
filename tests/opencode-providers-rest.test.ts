import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let ch: WebChannel
let token: string
let staticDir: string
let tmpDir: string
let devicesFile: string
let port: number
let setKeyCalls: Array<{ providerId: string; key: string }>
let startOAuthCalls: Array<{ providerId: string; method: number }>

const base = (extra: object) => ({
  port: 0,
  devicesFile,
  publicUrl: "http://127.0.0.1",
  staticDir,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  ...extra,
})

beforeEach(async () => {
  __resetAuthFailures()
  setKeyCalls = []
  startOAuthCalls = []
  tmpDir = mkdtempSync(join(tmpdir(), "oc-providers-"))
  devicesFile = join(tmpDir, "devices.json")
  // A real staticDir with an index.html makes the SPA-fallback handler active —
  // that handler swallowed GET /opencode/providers back when "/opencode" was
  // missing from API_PREFIXES, returning the HTML shell instead of the JSON route.
  // The web client then threw "The string did not match the expected pattern"
  // (WebKit) trying to parse that HTML as JSON.
  staticDir = join(tmpDir, "static")
  rmSync(staticDir, { recursive: true, force: true })
  mkdirSync(staticDir, { recursive: true })
  writeFileSync(join(staticDir, "index.html"), "<!doctype html><title>app</title>")
  const deviceStore = new DeviceStore(devicesFile)
  token = deviceStore.mint("d").token
  ch = new WebChannel(
    base({
      listOpenCodeProviders: async () => [
        { id: "xai", configured: false, methods: [{ type: "oauth", label: "xAI Grok", index: 0 }] },
        { id: "openai", configured: true, methods: [{ type: "api", label: "API Key", index: 0 }] },
      ],
      setOpenCodeApiKey: async (providerId: string, key: string) => {
        setKeyCalls.push({ providerId, key })
      },
      startOpenCodeOAuth: async (providerId: string, method: number) => {
        startOAuthCalls.push({ providerId, method })
        return {
          url: "https://github.com/login/device",
          instructions: "Enter device code ABCD-EFGH before continuing.",
          method: "auto" as const,
        }
      },
    }),
  )
  await ch.start()
  port = ch.boundPort
})

afterEach(async () => {
  await ch.stop()
  if (tmpDir && existsSync(tmpDir)) rmSync(tmpDir, { recursive: true, force: true })
})

const url = (path: string) => `http://127.0.0.1:${port}${path}`
const auth = () => ({ Authorization: `Bearer ${token}`, "content-type": "application/json" })

// Regression for the "/opencode" API_PREFIXES omission: the static/SPA handler must
// NOT swallow the opencode API routes. Before the fix this returned the index.html
// shell (text/html, 200), and the web client choked parsing HTML as JSON on WebKit.
test("GET /opencode/providers returns the JSON provider list, not the SPA shell", async () => {
  const res = await fetch(url("/opencode/providers"), { headers: auth() })
  expect(res.status).toBe(200)
  expect(res.headers.get("content-type") ?? "").toContain("application/json")
  const body = (await res.json()) as Array<{ id: string }>
  expect(Array.isArray(body)).toBe(true)
  expect(body.map((p) => p.id)).toEqual(["xai", "openai"])
})

test("POST /opencode/auth/key saves OpenCode key for both Zen and Go", async () => {
  const res = await fetch(url("/opencode/auth/key"), {
    method: "POST",
    headers: auth(),
    body: JSON.stringify({ providerId: "opencode", key: "sk-test" }),
  })
  expect(res.status).toBe(200)
  expect(setKeyCalls).toEqual([
    { providerId: "opencode", key: "sk-test" },
    { providerId: "opencode-go", key: "sk-test" },
  ])
})

test("POST /opencode/auth/key saves OpenCode Go key for both Go and Zen", async () => {
  const res = await fetch(url("/opencode/auth/key"), {
    method: "POST",
    headers: auth(),
    body: JSON.stringify({ providerId: "opencode-go", key: "sk-test" }),
  })
  expect(res.status).toBe(200)
  expect(setKeyCalls).toEqual([
    { providerId: "opencode-go", key: "sk-test" },
    { providerId: "opencode", key: "sk-test" },
  ])
})

test("POST /opencode/auth/oauth/start preserves instructions and callback method", async () => {
  const res = await fetch(url("/opencode/auth/oauth/start"), {
    method: "POST",
    headers: auth(),
    body: JSON.stringify({ providerId: "github-copilot", method: 0 }),
  })

  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({
    url: "https://github.com/login/device",
    instructions: "Enter device code ABCD-EFGH before continuing.",
    method: "auto",
  })
  expect(startOAuthCalls).toEqual([{ providerId: "github-copilot", method: 0 }])
})
