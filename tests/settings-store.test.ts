import { test, expect, beforeEach } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { SettingsStore } from "../src/core/settings/store"

function freshDb() {
  const dir = mkdtempSync(join(tmpdir(), "mux-settings-"))
  const db = openDb(join(dir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return db
}

test("write-through get/set + getCurator default", () => {
  const db = freshDb()
  const s = new SettingsStore(db)
  expect(s.get("nope")).toBeUndefined()
  expect(s.getCurator().enabled).toBe(false)
  expect(s.getCurator().hour).toBe(1)
  s.set("foo", { a: 1 })
  expect(s.get<{ a: number }>("foo")).toEqual({ a: 1 })
})

test("curator config persists across store instances (same db)", () => {
  const db = freshDb()
  new SettingsStore(db).setCurator({ enabled: true, hour: 9, minute: 15 })
  const reloaded = new SettingsStore(db)
  expect(reloaded.getCurator()).toEqual({ enabled: true, hour: 9, minute: 15 })
  expect(reloaded.has("curator")).toBe(true)
})

test("app config: getAppConfig resolves env when store empty", () => {
  const db = freshDb()
  const s = new SettingsStore(db)
  const c = s.getAppConfig({ MUX_PA_NAME: "envpa" })
  expect(c.paName).toBe("envpa")
  expect(c.onboarded).toBe(false)
})

test("app config: setAppConfig persists and wins over env across instances", () => {
  const db = freshDb()
  new SettingsStore(db).setAppConfig({ paName: "stored", onboarded: true })
  const reloaded = new SettingsStore(db)
  const c = reloaded.getAppConfig({ MUX_PA_NAME: "envpa" })
  expect(c.paName).toBe("stored")
  expect(c.onboarded).toBe(true)
  expect(reloaded.has("app")).toBe(true)
})

test("app config: partial store still reveals env seed for unset fields", () => {
  const db = freshDb()
  new SettingsStore(db).setAppConfig({ telegramBotToken: "xyz" })
  const c = new SettingsStore(db).getAppConfig({ MUX_PA_NAME: "envpa" })
  expect(c.telegramBotToken).toBe("xyz")
  expect(c.paName).toBe("envpa") // never set in store → env wins
})

test("app config: storage stays sparse (unset fields not persisted)", () => {
  const db = freshDb()
  const s = new SettingsStore(db)
  s.setAppConfig({ telegramBotToken: "xyz" })
  const raw = s.get<Record<string, unknown>>("app")!
  expect(raw.telegramBotToken).toBe("xyz")
  expect("paName" in raw).toBe(false) // sparse: not densified to defaults
})

test("app config: explicitly cleared string reveals env on read", () => {
  const db = freshDb()
  new SettingsStore(db).setAppConfig({ telegramBotToken: "" })
  const c = new SettingsStore(db).getAppConfig({ MUX_TELEGRAM_BOT_TOKEN: "envtok" })
  expect(c.telegramBotToken).toBe("envtok")
})
