import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import { SettingsStore } from "../src/core/settings/store"
import { openDb, runMigrations } from "../src/core/storage/db"
import { reverseProxySnippets } from "../src/core/settings/exposure"

const DEV_PATH = `/tmp/devices-exposure-${process.pid}.json`
const PORT = 18790
let ch: WebChannel
let token: string
let store: SettingsStore

function freshDb() {
  const dir = mkdtempSync(join(tmpdir(), "mux-exposure-rest-"))
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
      getExposure: () => ({
        exposureMode: "public",
        publicUrl: "https://mux.example.com",
        snippets: reverseProxySnippets({ publicUrl: "https://mux.example.com", port: "8787" }),
      }),
      validateExposure: async () => ({ reachable: true, status: 200 }),
    }),
  )
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}`, "content-type": "application/json" })

test("GET /settings/exposure returns 200 with exposureMode and snippets.caddy", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/exposure`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.exposureMode).toBe("public")
  expect(body.snippets.caddy).toContain("reverse_proxy")
})

test("POST /settings/exposure/validate same-origin returns reachable true", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/exposure/validate`, {
    method: "POST",
    headers: auth(),
    body: "{}",
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.reachable).toBe(true)
})

test("GET /settings/exposure requires auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/exposure`)
  expect(res.status).toBe(401)
})
