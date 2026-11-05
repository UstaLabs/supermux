import { expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { proc } from "@supermux/platform"

test("a live process is alive, a pid that never existed is not", () => {
  expect(proc.isAlive(process.pid)).toBe(true)
  expect(proc.isAlive(0)).toBe(false)
  expect(proc.isAlive(-1)).toBe(false)
  expect(proc.isAlive(2 ** 31 - 2)).toBe(false)
})

// #36: kill(pid, 0) answering EPERM means the process exists but belongs to someone else (on Windows another session
// or an elevated one). The broker's pid file counted it as dead and took over a live broker's claim.
test("EPERM means alive, ESRCH means gone", () => {
  const thrower = (code: string) => () => { throw Object.assign(new Error(code), { code }) }
  expect(proc.isAliveWith(1234, thrower("EPERM"))).toBe(true)
  expect(proc.isAliveWith(1234, thrower("ESRCH"))).toBe(false)
  expect(proc.isAliveWith(1234, () => true)).toBe(true)
})

const windowsFiles = (...files: string[]) => ({
  platform: "win32" as const,
  fileExists: (path: string) => files.some((file) => file.toLowerCase() === path.toLowerCase()),
})

// The PATH is read from the environment given, at the call. Bun.which without one searches the PATH the process
// started with, so a folder added later (main.ts adds the agent bin folders) was never searched.
test("resolve searches the PATH of the environment it is given, at the call", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-resolve-"))
  const file = process.platform === "win32" ? "mux-tool.cmd" : "mux-tool"
  writeFileSync(join(dir, file), process.platform === "win32" ? "@echo off\r\n" : "#!/bin/sh\n", { mode: 0o755 })
  expect(proc.resolve(["mux-tool"], { PATH: dir })).toBe(join(dir, file))
  expect(proc.resolve(["mux-tool"], { PATH: "" })).toBeNull()
})

test("resolve on Windows: .exe, then .cmd, then .ps1 within a name, names in the caller's order, Path in any case", () => {
  const env = { Path: "C:\\One;D:\\Two", PathExt: ".Ps1;.CmD;.Exe" }
  expect(proc.resolve(["tool"], env, windowsFiles("C:\\One\\tool.cmd", "D:\\Two\\tool.exe"))).toBe("D:\\Two\\tool.exe")
  expect(proc.resolve(["cursor-agent", "agent"], { PATH: "C:\\Cursor" }, windowsFiles("C:\\Cursor\\agent.exe"))).toBe("C:\\Cursor\\agent.exe")
  expect(proc.resolve(["C:\\Tools\\codex"], {}, windowsFiles("C:\\Tools\\codex.CMD"))).toBe("C:\\Tools\\codex.cmd")
})

// Real files and a ":"-separated PATH: a Windows temp folder (C:\...) cannot be put in one.
test.skipIf(process.platform === "win32")("resolve on POSIX: a folder named like the command is not the command", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-resolve-"))
  mkdirSync(join(dir, "first", "tool"), { recursive: true })
  mkdirSync(join(dir, "second"))
  writeFileSync(join(dir, "second", "tool"), "#!/bin/sh\n", { mode: 0o755 })
  const path = `${join(dir, "first")}:${join(dir, "second")}`
  expect(proc.resolve(["tool"], { PATH: path }, { platform: "linux" })).toBe(join(dir, "second", "tool"))
})

test("envValue reads a Windows variable in any letter case, and elsewhere by its exact name", () => {
  expect(proc.envValue({ ComSpec: "C:\\Windows\\cmd.exe" }, "COMSPEC", "win32")).toBe("C:\\Windows\\cmd.exe")
  expect(proc.envValue({ Path: "/x" }, "PATH", "linux")).toBeUndefined()
  expect(proc.envValue({ PATH: "/x" }, "PATH", "linux")).toBe("/x")
})
