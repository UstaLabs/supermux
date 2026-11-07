import { expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs"
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

// Bun.spawn without `env` passes the environment the process started with (tests/runtime-assumptions.test.ts), and
// windowsHide is false by default in Bun and Node: a console window flashes up for each child on a Windows desktop.
test("spawnDefaults: the environment as it is at the call, and no console window", () => {
  process.env.MUX_SPAWN_DEFAULTS_PROBE = "now"
  try {
    const defaults = proc.spawnDefaults()
    expect(defaults.windowsHide).toBe(true)
    expect(defaults.env.MUX_SPAWN_DEFAULTS_PROBE).toBe("now")
    defaults.env.MUX_SPAWN_DEFAULTS_PROBE = "changed"
    expect(process.env.MUX_SPAWN_DEFAULTS_PROBE).toBe("now")
  } finally {
    delete process.env.MUX_SPAWN_DEFAULTS_PROBE
  }
})

// Eight ways to end a process tree, three of them in the broker and core: on Windows a .cmd shim's cmd.exe is the root,
// so killing only the child left the real CLI running; elsewhere only a detached child leads a group to signal.
test("killTree on Windows runs taskkill over the whole tree, with no window", () => {
  const started: unknown[][] = []
  const spawn = (...args: unknown[]) => { started.push(args); return { on: () => undefined } }
  proc.killTree(4321, { platform: "win32", spawn })
  expect(started).toEqual([["taskkill", ["/PID", "4321", "/T", "/F"], { stdio: "ignore", windowsHide: true }]])
})

test("killTree elsewhere signals the group the process leads, else the process, and a gone one is no error", () => {
  const sent: [number, string][] = []
  const noGroup = (pid: number, signal: string) => {
    sent.push([pid, signal])
    if (pid < 0) throw Object.assign(new Error("ESRCH"), { code: "ESRCH" })
  }
  proc.killTree(77, { platform: "linux", kill: noGroup })
  expect(sent).toEqual([[-77, "SIGTERM"], [77, "SIGTERM"]])
  const leader: [number, string][] = []
  proc.killTree(78, { platform: "darwin", signal: "SIGKILL", kill: (pid, signal) => { leader.push([pid, signal]) } })
  expect(leader).toEqual([[-78, "SIGKILL"]])
  const gone = () => { throw Object.assign(new Error("ESRCH"), { code: "ESRCH" }) }
  expect(() => proc.killTree(79, { platform: "linux", kill: gone })).not.toThrow()
})

test("killTree on Windows ends the process itself when taskkill cannot be started", () => {
  const sent: number[] = []
  const missing = () => { throw Object.assign(new Error("spawn taskkill ENOENT"), { code: "ENOENT" }) }
  proc.killTree(4322, { platform: "win32", spawn: missing, kill: (pid) => { sent.push(pid) } })
  expect(sent).toEqual([4322])
})

// Worktree hooks are POSIX scripts (.mux/worktree-setup.sh, .mux/verify.sh). The broker ran them with `bash` from PATH
// (on Windows: missing, since Git's installer puts only Git\cmd there, or WSL's launcher, which runs them inside Linux)
// and through cmd.exe.
test("posixShell elsewhere is the bash on PATH", () => {
  expect(proc.posixShell({ PATH: "/usr/bin:/bin" }, { platform: "linux", fileExists: (p) => p === "/bin/bash" })).toBe("/bin/bash")
  expect(proc.posixShell({ PATH: "/usr/bin" }, { platform: "linux", fileExists: () => false })).toBeNull()
})

test("posixShell on Windows is Git for Windows' bash, beside its git or where Git installs, never WSL's", () => {
  const env = { Path: "C:\\Windows\\System32;D:\\Tools\\Git\\cmd", SystemRoot: "C:\\Windows", ProgramFiles: "C:\\Program Files" }
  const beside = windowsFiles("C:\\Windows\\System32\\bash.exe", "D:\\Tools\\Git\\cmd\\git.exe", "D:\\Tools\\Git\\bin\\bash.exe")
  expect(proc.posixShell(env, beside)).toBe("D:\\Tools\\Git\\bin\\bash.exe")
  const installed = windowsFiles("C:\\Windows\\System32\\bash.exe", "C:\\Program Files\\Git\\bin\\bash.exe")
  expect(proc.posixShell({ ...env, Path: "C:\\Windows\\System32" }, installed)).toBe("C:\\Program Files\\Git\\bin\\bash.exe")
  expect(proc.posixShell({ ...env, Path: "C:\\Windows\\System32" }, windowsFiles("C:\\Windows\\System32\\bash.exe"))).toBeNull()
})

// One rule for starting a Windows shim without a shell reading its arguments (BatBadBut), for the launcher and core's
// login alike. Core's own rule refused " % ! anywhere, the resolved .cmd path included: a user named "Ana!" could not
// sign in. The launcher's (cross-spawn's) escapes them, with delayed expansion off.
test("windowsLaunch: a .cmd or .bat shim runs through cmd.exe, delayed expansion off, its line passed verbatim", () => {
  const env = { ComSpec: "C:\\Windows\\system32\\cmd.exe" }
  const shim = "C:\\Users\\Ana!\\AppData\\Roaming\\npm\\claude.cmd"
  const args = ["--email", 'a!b%PATH%"c&d']
  expect(proc.windowsLaunch(shim, args, { env })).toEqual({
    command: "C:\\Windows\\system32\\cmd.exe",
    args: ["/d", "/v:off", "/s", "/c", proc.windowsCmdCommandLine(shim, args)],
    windowsVerbatimArguments: true,
  })
  expect(proc.windowsCmdCommandLine(shim, args).startsWith('"C:\\Users\\Ana^!\\')).toBe(true)
  expect(proc.windowsLaunch("tool.BAT", [], { env: {} }).command).toBe("cmd.exe")
})

test("windowsLaunch: a .ps1 runs through PowerShell -File with its arguments quoted by the runtime; an .exe as it is", () => {
  const env = { PATH: "C:\\PS" }
  const fileExists = (path: string) => path.toLowerCase() === "c:\\ps\\pwsh.exe"
  expect(proc.windowsLaunch("C:\\npm\\tool.ps1", ["a b"], { env, fileExists })).toEqual({
    command: "C:\\PS\\pwsh.exe",
    args: ["-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", "C:\\npm\\tool.ps1", "a b"],
  })
  expect(() => proc.windowsLaunch("C:\\npm\\tool.ps1", [], { env: { PATH: "" }, fileExists: () => false })).toThrow("PowerShell")
  expect(proc.windowsLaunch("C:\\bin\\claude.exe", ["x y"], { env })).toEqual({ command: "C:\\bin\\claude.exe", args: ["x y"] })
})

test.skipIf(process.platform !== "win32")("windowsLaunch: a .cmd shim in a folder named Ana! gets every argument intact", () => {
  const dir = join(mkdtempSync(join(tmpdir(), "launch-")), "Ana!")
  mkdirSync(dir)
  const script = join(dir, "argv.js")
  writeFileSync(script, "require('fs').writeFileSync(process.argv[2], JSON.stringify(process.argv.slice(3)))")
  const shim = join(dir, "tool.cmd")
  writeFileSync(shim, `@"${process.execPath}" "${script}" %*\r\n`)
  const out = join(dir, "out.json")
  const args = ["plain", "a b", 'q"uote', "bang!", "%PATH%", "amp&er", "car^et", "pipe|x", "lt<gt>", "trail\\", ""]
  const plan = proc.windowsLaunch(shim, [out, ...args], { env: process.env })
  const result = spawnSync(plan.command, plan.args, { windowsVerbatimArguments: plan.windowsVerbatimArguments, windowsHide: true })
  expect(result.status).toBe(0)
  expect(JSON.parse(readFileSync(out, "utf8"))).toEqual(args)
})
