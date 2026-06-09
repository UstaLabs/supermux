import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { acquirePidFile, releasePidFile, isProcessAlive } from "../src/core/session-manager/pid-file"

let dir: string
beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "agentmux-pid-")) })
afterEach(() => rmSync(dir, { recursive: true, force: true }))

test("acquire writes our pid", () => {
  const f = join(dir, "broker.pid")
  acquirePidFile(f)
  expect(existsSync(f)).toBe(true)
})

test("acquire fails if file exists and that pid is alive", () => {
  const f = join(dir, "broker.pid")
  // Pretend a process from our own pid is alive (it is — we're it)
  Bun.write(f, String(process.pid))
  expect(() => acquirePidFile(f)).toThrow(/already running/)
})

test("acquire takes over if file exists and that pid is dead", () => {
  const f = join(dir, "broker.pid")
  Bun.write(f, "99999999")  // implausible pid
  acquirePidFile(f)
  // No throw = success
})

test("isProcessAlive reports false for non-existent pid", () => {
  expect(isProcessAlive(99999999)).toBe(false)
})

test("isProcessAlive reports true for own pid", () => {
  expect(isProcessAlive(process.pid)).toBe(true)
})
