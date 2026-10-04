import { Database } from "bun:sqlite"
import { describe, expect, test } from "bun:test"
import { defaultKeepAwakeSettings, parseKeepAwakeBody, resolveKeepAwakeSettings, SETTINGS_KEY_KEEP_AWAKE } from "./keep-awake-config"
import { SettingsStore } from "./store"

function db() {
  const d = new Database(":memory:")
  d.exec("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
  return d
}

describe("keep-awake defaults", () => {
  test("on for a desktop-managed broker, off otherwise; 'Also on battery' on", () => {
    expect(defaultKeepAwakeSettings({ MUX_MANAGED_BY: "desktop" })).toEqual({ enabled: true, onBattery: true })
    expect(defaultKeepAwakeSettings({})).toEqual({ enabled: false, onBattery: true })
    expect(defaultKeepAwakeSettings({ MUX_MANAGED_BY: "systemd" })).toEqual({ enabled: false, onBattery: true })
  })

  test("MUX_KEEP_AWAKE=1|0 overrides the default", () => {
    expect(defaultKeepAwakeSettings({ MUX_KEEP_AWAKE: "1" }).enabled).toBe(true)
    expect(defaultKeepAwakeSettings({ MUX_MANAGED_BY: "desktop", MUX_KEEP_AWAKE: "0" }).enabled).toBe(false)
    expect(defaultKeepAwakeSettings({ MUX_KEEP_AWAKE: "yes" }).enabled).toBe(false) // only 1/0
  })

  test("a saved choice wins over the env override", () => {
    expect(resolveKeepAwakeSettings({ enabled: false }, { MUX_KEEP_AWAKE: "1" })).toEqual({ enabled: false, onBattery: true })
    expect(resolveKeepAwakeSettings({ onBattery: false }, { MUX_KEEP_AWAKE: "1" })).toEqual({ enabled: true, onBattery: false })
    expect(resolveKeepAwakeSettings("garbage", { MUX_MANAGED_BY: "desktop" })).toEqual({ enabled: true, onBattery: true })
  })

  test("request bodies: booleans only, at least one field", () => {
    expect(parseKeepAwakeBody({ enabled: true })).toEqual({ enabled: true })
    expect(parseKeepAwakeBody({ enabled: false, onBattery: false, x: 1 })).toEqual({ enabled: false, onBattery: false })
    expect("error" in parseKeepAwakeBody({ enabled: "yes" })).toBe(true)
    expect("error" in parseKeepAwakeBody({})).toBe(true)
    expect("error" in parseKeepAwakeBody([])).toBe(true)
    expect("error" in parseKeepAwakeBody(null)).toBe(true)
  })
})

describe("SettingsStore keep-awake persistence", () => {
  test("only the fields the user set are saved, and they survive a reload", () => {
    const d = db()
    const env = { MUX_MANAGED_BY: "desktop" }
    const store = new SettingsStore(d)
    expect(store.getKeepAwake(env)).toEqual({ enabled: true, onBattery: true })
    expect(store.setKeepAwake({ onBattery: false }, env)).toEqual({ enabled: true, onBattery: false })
    expect(store.get<unknown>(SETTINGS_KEY_KEEP_AWAKE)).toEqual({ onBattery: false })
    // `enabled` is still the default, so a later env change shows through …
    const reloaded = new SettingsStore(d)
    expect(reloaded.getKeepAwake({ MUX_KEEP_AWAKE: "0" })).toEqual({ enabled: false, onBattery: false })
    // … until the user chooses it.
    reloaded.setKeepAwake({ enabled: true }, {})
    expect(new SettingsStore(d).getKeepAwake({ MUX_KEEP_AWAKE: "0" })).toEqual({ enabled: true, onBattery: false })
  })
})
