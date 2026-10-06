import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { acquirePidFile, releasePidFile, isProcessAlive } from "../src/core/session-manager/pid-file"

let dir: string
beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "agentmux-pid-")) })
afterEach(() => rmSync(dir, { recursive: true, force: true }))

test("acquire writes our pid", () => {
  const f = join(dir, "broker.pid")
  acquirePidFile(f)
  expect(readFileSync(f, "utf8")).toBe(String(process.pid))
})

test("acquire fails if file exists and that pid is a live broker", () => {
  const f = join(dir, "broker.pid")
  writeFileSync(f, "4242")
  expect(() => acquirePidFile(f, { pid: process.pid, isAlive: () => true, isBroker: () => true, sleep: () => {} }))
    .toThrow(/already running/)
})

test("acquire takes over if file exists and that pid is dead", () => {
  const f = join(dir, "broker.pid")
  writeFileSync(f, "99999999")  // implausible pid
  acquirePidFile(f)
  expect(readFileSync(f, "utf8")).toBe(String(process.pid))
})

test("release removes our own pid file", () => {
  const f = join(dir, "broker.pid")
  acquirePidFile(f)
  releasePidFile(f)
  expect(existsSync(f)).toBe(false)
})

test("isProcessAlive reports false for non-existent pid", () => {
  expect(isProcessAlive(99999999)).toBe(false)
})

test("isProcessAlive reports true for own pid", () => {
  expect(isProcessAlive(process.pid)).toBe(true)
})
