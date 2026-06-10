import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import { SettingsStore } from "../src/core/settings/store"
import { openDb, runMigrations } from "../src/core/storage/db"

const DEV_PATH = `/tmp/devices-app-config-${process.pid}.json`
const PORT = 18897
let ch: WebChannel
let token: string
let store: SettingsStore

function freshDb() {
  const dir = mkdtempSync(join(tmpdir(), "mux-appcfg-rest-"))
  const db = openDb(join(dir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return db
}

const appConfigEnv = { MUX_PA_NAME: "testpa", MUX_PA_WORKDIR: "", MUX_TELEGRAM_BOT_TOKEN: "", MUX_WEB_PUBLIC_URL: "", MUX_WEB_PORT: "" }

const base = (extra: object) => ({
  port: PORT,
  devicesFile: DEV_PATH,
  publicUrl: "http://127.0.0.1:" + PORT,
  staticDir: undefined,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  ...extra,
})

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const deviceStore = new DeviceStore(DEV_PATH)
  token = deviceStore.mint("d").token
  store = new SettingsStore(freshDb())
  ch = new WebChannel(
    base({
      getAppConfig: () => store.getAppConfig(appConfigEnv),
      setAppConfig: (patch: Partial<import("../src/core/settings/app-config").AppConfig>) => {
        store.setAppConfig(patch)
        return store.getAppConfig(appConfigEnv)
      },
    }),
  )
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}`, "content-type": "application/json" })

test("GET /settings/config returns 200 with paName, telegramConfigured boolean, no telegramBotToken", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.paName).toBe("testpa")
  expect(typeof body.telegramConfigured).toBe("boolean")
  expect(body.telegramConfigured).toBe(false)
  expect("telegramBotToken" in body).toBe(false)
})

test("GET /settings/config requires auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`)
  expect(res.status).toBe(401)
})

test("PUT /settings/config persists and response echoes redacted config", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`, {
    method: "PUT",
    headers: auth(),
    body: JSON.stringify({ paName: "ana", onboarded: true }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.paName).toBe("ana")
  expect(body.onboarded).toBe(true)
  expect("telegramBotToken" in body).toBe(false)
  expect(body.telegramConfigured).toBe(false)

  // Also verify via the store directly that the change persisted
  const cfg = store.getAppConfig(appConfigEnv)
  expect(cfg.paName).toBe("ana")
  expect(cfg.onboarded).toBe(true)
})

test("PUT /settings/config cross-origin → 403", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`, {
    method: "PUT",
    headers: { Cookie: `cmux_token=${token}`, Origin: "https://evil.com", "content-type": "application/json" },
    body: "{}",
  })
  expect(res.status).toBe(403)
})

test("PUT /settings/config with telegramBotToken → telegramConfigured true, raw token never returned", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`, {
    method: "PUT",
    headers: auth(),
    body: JSON.stringify({ telegramBotToken: "secret" }),
  })
  expect(res.ok).toBe(true)
  const body = await res.json() as any
  expect(body.telegramConfigured).toBe(true)
  expect(body.telegramBotToken).toBeUndefined()
})

test("GET /settings/config redacts ALL secrets, exposes *Configured booleans", async () => {
  // First PUT { codexApiKey: "secret-key" } so codexConfigured is true
  const putRes = await fetch(`http://127.0.0.1:${PORT}/settings/config`, {
    method: "PUT",
    headers: auth(),
    body: JSON.stringify({ codexApiKey: "secret-key" }),
  })
  expect(putRes.ok).toBe(true)

  // Then authed GET
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.json() as any

  // Assert NONE of the raw secrets are present
  expect("telegramBotToken" in body).toBe(false)
  expect("claudeOauthToken" in body).toBe(false)
  expect("anthropicApiKey" in body).toBe(false)
  expect("codexApiKey" in body).toBe(false)
  expect("cursorApiKey" in body).toBe(false)

  // Assert all *Configured booleans are present
  expect(typeof body.telegramConfigured).toBe("boolean")
  expect(typeof body.claudeConfigured).toBe("boolean")
  expect(typeof body.anthropicConfigured).toBe("boolean")
  expect(typeof body.codexConfigured).toBe("boolean")
  expect(typeof body.cursorConfigured).toBe("boolean")

  // codexApiKey was set, so codexConfigured must be true
  expect(body.codexConfigured).toBe(true)
})

test("PUT /settings/config persists a pasted credential and never echoes it raw", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/config`, {
    method: "PUT",
    headers: auth(),
    body: JSON.stringify({ codexApiKey: "secret-key" }),
  })
  expect(res.ok).toBe(true)
  const body = await res.json() as any

  // Response must have codexConfigured === true and no raw key
  expect(body.codexConfigured).toBe(true)
  expect(body.codexApiKey).toBeUndefined()

  // Verify via the store directly that the value was actually persisted
  const cfg = store.getAppConfig(appConfigEnv)
  expect(cfg.codexApiKey).toBe("secret-key")
})
