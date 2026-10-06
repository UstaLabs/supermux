import { expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import {
  acquirePidFile, isBrokerProcessWith, looksLikeBrokerCommand, releasePidFile,
  type BrokerProbeDeps, type PidFileDeps,
} from "./pid-file"

const deps = (o: Partial<BrokerProbeDeps>): BrokerProbeDeps => ({
  platform: "linux",
  readProcCmdline: () => { throw new Error("no /proc") },
  run: () => null,
  ...o,
})

const pidDeps = (o: Partial<PidFileDeps>): PidFileDeps => ({
  pid: 4242,
  isAlive: () => true,
  isBroker: () => true,
  sleep: () => {},
  ...o,
})

const tmpPid = () => join(mkdtempSync(join(tmpdir(), "pidfile-")), "broker.pid")

test("broker commands: the compiled binary and bun running the entry, nothing else", () => {
  expect(looksLikeBrokerCommand("/home/u/.mux/state/bin/supermux-broker")).toBe(true)
  expect(looksLikeBrokerCommand("C:\\Users\\a\\supermux-broker.exe")).toBe(true)
  expect(looksLikeBrokerCommand("/usr/bin/bun\0/home/u/supermux/src/main.ts\0")).toBe(true)
  expect(looksLikeBrokerCommand("/home/u/.bun/bin/bun src/cli.ts")).toBe(true)
  // The desktop app is never a broker, whatever its path says.
  expect(looksLikeBrokerCommand("/Applications/Supermux Desktop.app/Contents/MacOS/Supermux Desktop")).toBe(false)
  expect(looksLikeBrokerCommand("/opt/supermux/bin/supermux")).toBe(false)
  expect(looksLikeBrokerCommand("/usr/share/applications/../../opt/supermux/bin/supermux")).toBe(false)
  expect(looksLikeBrokerCommand("C:\\Program Files\\supermux\\supermux.exe")).toBe(false)
  expect(looksLikeBrokerCommand("/usr/bin/bun\0run\0dev")).toBe(false)
  expect(looksLikeBrokerCommand("tmux attach -t mux")).toBe(false)
})

test("the CLI-installed broker is a broker; the desktop app of the same name is not", () => {
  expect(looksLikeBrokerCommand("/home/u/.local/bin/supermux")).toBe(true)
  expect(looksLikeBrokerCommand("/home/u/.local/bin/supermux\0")).toBe(true)
  expect(looksLikeBrokerCommand("/usr/local/bin/supermux")).toBe(true)
  expect(looksLikeBrokerCommand("\"C:\\Users\\a\\AppData\\Local\\supermux\\bin\\supermux.exe\"")).toBe(true)
  expect(looksLikeBrokerCommand("/opt/supermux/bin/supermux")).toBe(false)
  expect(looksLikeBrokerCommand("\"C:\\Program Files\\supermux\\supermux.exe\"")).toBe(false)
  expect(looksLikeBrokerCommand("/Applications/Supermux Desktop.app/Contents/MacOS/Supermux Desktop")).toBe(false)
  expect(looksLikeBrokerCommand("/home/u/projects/supermux/tool")).toBe(false)
})

test("linux: cmdline match / no match", () => {
  expect(isBrokerProcessWith(9, deps({ readProcCmdline: () => "/usr/bin/bun\0src/main.ts\0" }))).toBe(true)
  expect(isBrokerProcessWith(9, deps({ readProcCmdline: () => "/usr/bin/vim\0notes.txt" }))).toBe(false)
  expect(isBrokerProcessWith(9, deps({ readProcCmdline: () => "/opt/supermux/bin/supermux\0" }))).toBe(false)
})

test("darwin: ps match / no match", () => {
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "/opt/homebrew/bin/bun /Users/a/supermux/src/main.ts\n" }))).toBe(true)
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "/Applications/Safari.app\n" }))).toBe(false)
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "/Applications/Supermux Desktop.app/Contents/MacOS/Supermux Desktop\n" }))).toBe(false)
})

test("darwin: ps failure or empty output assumes a broker", () => {
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => null }))).toBe(true)
  expect(isBrokerProcessWith(9, deps({ platform: "darwin", run: () => "  \n" }))).toBe(true)
})

test("win32: powershell command line match", () => {
  const calls: string[][] = []
  const run = (c: string[]) => { calls.push(c); return "\"C:\\Users\\a\\supermux-broker.exe\"\r\n" }
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run }))).toBe(true)
  expect(calls[0]?.[0]).toBe("powershell.exe")
  expect(calls).toHaveLength(1)
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run: () => "\"C:\\Program Files\\supermux\\supermux.exe\"\r\n" }))).toBe(false)
})

test("win32: powershell fails, tasklist decides", () => {
  const run = (c: string[]) => c[0] === "tasklist" ? "\"bun.exe\",\"9\",\"Console\"\r\n" : null
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run }))).toBe(true)
  const other = (c: string[]) => c[0] === "tasklist" ? "\"notepad.exe\",\"9\"\r\n" : null
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run: other }))).toBe(false)
  // Image name only: supermux.exe may be the CLI broker, so the uncertain fallback says broker.
  const app = (c: string[]) => c[0] === "tasklist" ? "\"supermux.exe\",\"9\"\r\n" : null
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run: app }))).toBe(true)
})

test("win32: both fail assumes a broker", () => {
  expect(isBrokerProcessWith(9, deps({ platform: "win32", run: () => null }))).toBe(true)
})

test("acquire creates the file 0600 with our pid", () => {
  const path = tmpPid()
  acquirePidFile(path, pidDeps({}))
  expect(readFileSync(path, "utf8")).toBe("4242")
  if (process.platform !== "win32") {
    const { statSync } = require("fs") as typeof import("fs")
    expect(statSync(path).mode & 0o777).toBe(0o600)
  }
})

test("acquire refuses a live broker pid", () => {
  const path = tmpPid()
  writeFileSync(path, "777")
  expect(() => acquirePidFile(path, pidDeps({}))).toThrow(/already running as pid 777/)
  expect(readFileSync(path, "utf8")).toBe("777")
})

test("acquire replaces a dead pid, a non-broker pid and garbage", () => {
  for (const [content, d] of [
    ["777", pidDeps({ isAlive: () => false })],
    ["777", pidDeps({ isBroker: () => false })],
    ["not a pid", pidDeps({})],
  ] as const) {
    const path = tmpPid()
    writeFileSync(path, content)
    acquirePidFile(path, d)
    expect(readFileSync(path, "utf8")).toBe("4242")
  }
})

test("acquire treats its own pid as stale (a restarted process reusing it)", () => {
  const path = tmpPid()
  writeFileSync(path, "4242")
  let probed = false
  acquirePidFile(path, pidDeps({ isAlive: () => { probed = true; return true } }))
  expect(probed).toBe(false)
  expect(readFileSync(path, "utf8")).toBe("4242")
})

test("acquire waits for a half-written claim, then honours it", () => {
  const path = tmpPid()
  writeFileSync(path, "")
  const sleeps: number[] = []
  // The other broker finishes writing while we wait.
  const d = pidDeps({ sleep: (ms) => { sleeps.push(ms); writeFileSync(path, "999") } })
  expect(() => acquirePidFile(path, d)).toThrow(/pid 999/)
  expect(sleeps.length).toBe(1)
})

test("acquire takes over an empty file that stays empty", () => {
  const path = tmpPid()
  writeFileSync(path, "")
  acquirePidFile(path, pidDeps({}))
  expect(readFileSync(path, "utf8")).toBe("4242")
})

test("release removes only our own claim", () => {
  const path = tmpPid()
  writeFileSync(path, "999")
  releasePidFile(path, 4242)
  expect(existsSync(path)).toBe(true)
  writeFileSync(path, "4242\n")
  releasePidFile(path, 4242)
  expect(existsSync(path)).toBe(false)
  releasePidFile(path, 4242) // already gone: no throw
})

test("acquirePidFile with the real probes replaces a dead pid", async () => {
  const path = tmpPid()
  const child = Bun.spawn(["true"])
  await child.exited
  writeFileSync(path, String(child.pid))
  acquirePidFile(path)
  expect(readFileSync(path, "utf8")).toBe(String(process.pid))
})
