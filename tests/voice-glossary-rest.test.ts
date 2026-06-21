import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync, mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import { SettingsStore } from "../src/core/settings/store"
import { openDb, runMigrations } from "../src/core/storage/db"
import { DEFAULT_VOICE_CLEANUP_GLOSSARY } from "../src/core/settings/app-config"

const DEV_PATH = `/tmp/devices-voice-glossary-${process.pid}.json`
const PORT = 18896
let ch: WebChannel
let token: string
let store: SettingsStore

function freshDb() {
  const dir = mkdtempSync(join(tmpdir(), "mux-glossary-rest-"))
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

test("GET /config/voice-glossary returns the default-seeded glossary", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/config/voice-glossary`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(Array.isArray(body.glossary)).toBe(true)
  expect(body.glossary).toEqual(DEFAULT_VOICE_CLEANUP_GLOSSARY)
})

test("GET /config/voice-glossary requires auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/config/voice-glossary`)
  expect(res.status).toBe(401)
})

test("PUT /config/voice-glossary persists the list and GET reflects it", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/config/voice-glossary`, {
    method: "PUT",
    headers: auth(),
    body: JSON.stringify({ glossary: ["Supermux", "Foobar"] }),
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.glossary).toEqual(["Supermux", "Foobar"])

  // Persisted in the store
  const cfg = store.getAppConfig(appConfigEnv)
  expect(cfg.voiceCleanupGlossary).toEqual(["Supermux", "Foobar"])

  // GET reflects the stored list (no longer the default seed)
  const getRes = await fetch(`http://127.0.0.1:${PORT}/config/voice-glossary`, { headers: auth() })
  const getBody = await getRes.json() as any
  expect(getBody.glossary).toEqual(["Supermux", "Foobar"])
})

test("PUT /config/voice-glossary cross-origin → 403", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/config/voice-glossary`, {
    method: "PUT",
    headers: { Cookie: `cmux_token=${token}`, Origin: "https://evil.com", "content-type": "application/json" },
    body: JSON.stringify({ glossary: ["X"] }),
  })
  expect(res.status).toBe(403)
})

test("PUT /config/voice-glossary requires auth", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/config/voice-glossary`, {
    method: "PUT",
    headers: { Origin: `http://127.0.0.1:${PORT}`, "content-type": "application/json" },
    body: JSON.stringify({ glossary: ["X"] }),
  })
  expect(res.status).toBe(401)
})
