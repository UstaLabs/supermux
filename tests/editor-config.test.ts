import { test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { SettingsStore } from "../src/core/settings/store"
import { isLspServerEnabled, mergeEditorConfigPatch, parseEditorConfig } from "../src/core/settings/editor-config"
import { resolveServerForPath } from "../src/core/lsp/registry"
import { parseCustomLspServerDef, stripSudoPrefix } from "../src/core/settings/editor-config"

test("isLspServerEnabled defaults to true", () => {
  expect(isLspServerEnabled("typescript", {})).toBe(true)
  expect(isLspServerEnabled("dart", parseEditorConfig({}))).toBe(true)
})

test("disabled server is excluded from resolveServerForPath", () => {
  const cfg = parseEditorConfig({ lsp: { servers: { dart: { enabled: false } } } })
  expect(isLspServerEnabled("dart", cfg)).toBe(false)
  expect(resolveServerForPath("lib/main.dart", cfg)).toBeUndefined()
  expect(resolveServerForPath("lib/main.dart")).toBeDefined()
})

test("editor config persists in settings store", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-editor-"))
  const db = openDb(join(dir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const s = new SettingsStore(db)
  s.setEditorConfig({ lsp: { servers: { typescript: { enabled: false } } } })
  const reloaded = new SettingsStore(db)
  expect(isLspServerEnabled("typescript", reloaded.getEditorConfig())).toBe(false)
})

test("setEditorConfig ignores unknown server ids", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-editor-2-"))
  const db = openDb(join(dir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const s = new SettingsStore(db)
  s.setEditorConfig({ lsp: { servers: { "not-a-server": { enabled: false }, dart: { enabled: false } } } })
  const cfg = s.getEditorConfig()
  expect(cfg.lsp?.servers?.["not-a-server"]).toBeUndefined()
  expect(isLspServerEnabled("dart", cfg)).toBe(false)
})

test("stripSudoPrefix removes leading sudo", () => {
  expect(stripSudoPrefix("sudo apt install -y zls")).toBe("apt install -y zls")
  expect(stripSudoPrefix("zls")).toBe("zls")
})

test("parseCustomLspServerDef strips sudo from install command", () => {
  const def = parseCustomLspServerDef({
    label: "Zig",
    command: "zls",
    extensions: [".zig"],
    installCmd: "sudo apt install -y zls",
  })
  expect(def.installCmd).toBe("apt install -y zls")
})

test("parseEditorConfig strips sudo from stored custom servers on load", () => {
  const cfg = parseEditorConfig({
    lsp: {
      custom: {
        zls: { label: "Zig", command: "zls", extensions: [".zig"], installCmd: "sudo apt install -y zls" },
      },
    },
  })
  expect(cfg.lsp?.custom?.zls?.installCmd).toBe("apt install -y zls")
})

test("addCustomLspServer persists and resolves", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-editor-custom-"))
  const db = openDb(join(dir, "t.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const s = new SettingsStore(db)
  s.addCustomLspServer("zls", parseCustomLspServerDef({
    label: "Zig",
    command: "zls",
    extensions: ".zig",
  }))
  expect(resolveServerForPath("a.zig", s.getEditorConfig())?.id).toBe("zls")
  s.removeCustomLspServer("zls")
  expect(resolveServerForPath("a.zig", s.getEditorConfig())).toBeUndefined()
})

test("mergeEditorConfigPatch merges per-server flags", () => {
  const merged = mergeEditorConfigPatch(
    { lsp: { servers: { typescript: { enabled: false } } } },
    { lsp: { servers: { dart: { enabled: false } } } },
  )
  expect(isLspServerEnabled("typescript", merged)).toBe(false)
  expect(isLspServerEnabled("dart", merged)).toBe(false)
})
