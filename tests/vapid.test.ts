import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, statSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { loadOrGenerateVapid } from "../src/core/push/vapid"

let tmpDir: string
beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "cmux-vapid-")) })
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("generates a new keypair when file does not exist", () => {
  const path = join(tmpDir, "push-keys.json")
  const keys = loadOrGenerateVapid(path, "mailto:test@example.com")
  expect(typeof keys.publicKey).toBe("string")
  expect(typeof keys.privateKey).toBe("string")
  expect(keys.publicKey.length).toBeGreaterThan(40)
  expect(keys.subject).toBe("mailto:test@example.com")
})

test("persists with 0o600 perms", () => {
  const path = join(tmpDir, "push-keys.json")
  loadOrGenerateVapid(path, "mailto:test@example.com")
  const mode = statSync(path).mode & 0o777
  expect(mode).toBe(0o600)
})

test("subsequent calls return the same keys", () => {
  const path = join(tmpDir, "push-keys.json")
  const a = loadOrGenerateVapid(path, "mailto:test@example.com")
  const b = loadOrGenerateVapid(path, "mailto:other@example.com")  // subject ignored on re-read
  expect(b.publicKey).toBe(a.publicKey)
  expect(b.privateKey).toBe(a.privateKey)
})

test("throws clear error on corrupted file", () => {
  const path = join(tmpDir, "push-keys.json")
  writeFileSync(path, "not-json{{{", { mode: 0o600 })
  expect(() => loadOrGenerateVapid(path, "mailto:test@example.com"))
    .toThrow(/corrupted or invalid/)
})

test("throws clear error on file missing required fields", () => {
  const path = join(tmpDir, "push-keys.json")
  writeFileSync(path, JSON.stringify({ subject: "x" }), { mode: 0o600 })
  expect(() => loadOrGenerateVapid(path, "mailto:test@example.com"))
    .toThrow(/missing publicKey or privateKey/)
})
