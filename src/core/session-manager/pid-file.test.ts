import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { acquirePidFile, isBrokerProcessWith, type BrokerProbeDeps } from "./pid-file"

const deps = (o: Partial<BrokerProbeDeps>): BrokerProbeDeps => ({
  platform: "linux",
  readProcCmdline: () => { throw new Error("no /proc") },
  run: () => null,
  ...o,
})

test("linux: cmdline match / no match", () => {
  expect(isBrokerProcessWith(9, deps({ readProcCmdline: () => "/usr/bin/bun\0run\0broker" }))).toBe(true)
  expect(isBrokerProcessWith(9, deps({ readProcCmdline: () => "/usr/bin/vim\0notes.txt" }))).toBe(false)
})

test("darwin: ps match / no match", () => {
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "/opt/homebrew/bin/bun run x\n" }))).toBe(true)
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "/Applications/Safari.app\n" }))).toBe(false)
})

test("darwin: ps failure or empty output assumes a broker", () => {
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => null }))).toBe(true)
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "  \n" }))).toBe(true)
})

test("win32: powershell path match", () => {
  const calls: string[][] = []
  const run = (c: string[]) => { calls.push(c); return "C:\\Users\\a\\supermux-broker.exe\r\n" }
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run }))).toBe(true)
  expect(calls[0]?.[0]).toBe("powershell.exe")
  expect(calls).toHaveLength(1)
})

test("win32: powershell fails, tasklist decides", () => {
  const run = (c: string[]) => c[0] === "tasklist" ? "\"bun.exe\",\"9\",\"Console\"\r\n" : null
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run }))).toBe(true)
  const other = (c: string[]) => c[0] === "tasklist" ? "\"notepad.exe\",\"9\"\r\n" : null
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run: other }))).toBe(false)
})

test("win32: both fail assumes a broker", () => {
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run: () => null }))).toBe(true)
})

test("acquirePidFile refuses a live broker pid and replaces a dead one", async () => {
  const dir = mkdtempSync(join(tmpdir(), "pidfile-"))
  const path = join(dir, "broker.pid")
  writeFileSync(path, String(process.pid)) // this bun test process: alive, and a bun process
  expect(() => acquirePidFile(path)).toThrow(/already running/)

  const child = Bun.spawn(["true"])
  await child.exited
  writeFileSync(path, String(child.pid))
  acquirePidFile(path)
  expect(readFileSync(path, "utf8")).toBe(String(process.pid))
})
