// Facts about the runtime that supermux's own code relies on. Each one is a behaviour we chose to build on, or
// around, after checking it (see the platform-layer spec, §3.2). If a Bun upgrade changes one, this file goes red
// before users notice. The Windows lane runs it too, so a fact that differs on Windows shows up there.
import { expect, test } from "bun:test"
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { createServer } from "node:net"
import { homedir, tmpdir } from "node:os"
import { join } from "node:path"

// Why src/shared/home.ts reads the environment itself instead of calling os.homedir(): Bun's os.homedir() is not a
// dependable view of HOME / USERPROFILE set at runtime (a test preload, an override). On Linux and macOS it keeps
// the home the process STARTED with; on Windows it follows a USERPROFILE set later (checked on 1.3.14 and 1.4.4;
// Node follows the change everywhere). Code that must honour an override reads process.env.
test("Bun's os.homedir() follows a runtime USERPROFILE on Windows, and ignores a runtime HOME elsewhere", () => {
  const before = homedir()
  const elsewhere = join(tmpdir(), "mux-not-a-home")
  const saved = { HOME: process.env.HOME, USERPROFILE: process.env.USERPROFILE }
  process.env.HOME = process.env.USERPROFILE = elsewhere
  try {
    expect(homedir()).toBe(process.platform === "win32" ? elsewhere : before)
  } finally {
    // Assigning undefined would store the string "undefined": delete instead.
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
})

// Why the Windows build runs Bun >= 1.4.0: from 1.4.0 Bun refuses to start a .cmd/.bat file with an argument that
// cmd.exe would read as a command separator ("BatBadBut"). Bun 1.3.14 passes it through, so a "safe&calc" argument
// ran calc. A Windows-only fact: elsewhere a .cmd file is not a program.
test.skipIf(process.platform !== "win32")("Bun refuses a .cmd argument that cmd.exe would split into a second command", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-batbadbut-"))
  try {
    const script = join(dir, "echo-args.cmd")
    writeFileSync(script, "@echo off\r\necho %*\r\n")
    let output = ""
    const run = () => {
      const result = Bun.spawnSync([script, "safe&echo INJECTED"], { stdout: "pipe", stderr: "pipe", windowsHide: true })
      output = result.stdout.toString()
    }
    expect(run).toThrow()
    expect(output).not.toContain("INJECTED")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

// Folder links are junctions on Windows (files.linkDirectory, #37). Two things the code relies on there, unverified when
// they were written (platform-layer spec A.3 "Links"): readlink gives the target back as it was given (Antigravity and
// the account homes compare it to decide whether a link is current; a `\\?\` prefix or another case would rebuild
// links at every prepare and never clean one up), and a recursive delete removes the link, never what it points to (a
// delete that followed a junction would take the user's plugins or history with it).
test("readlink of a folder link gives back the target as it was given", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-junction-"))
  try {
    mkdirSync(join(dir, "target"))
    symlinkSync(join(dir, "target"), join(dir, "link"), "junction")
    expect(readlinkSync(join(dir, "link"))).toBe(join(dir, "target"))
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("a recursive delete removes a folder link, never what it points to", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-junction-"))
  try {
    mkdirSync(join(dir, "target"))
    writeFileSync(join(dir, "target", "precious.txt"), "keep me")
    mkdirSync(join(dir, "doomed"))
    symlinkSync(join(dir, "target"), join(dir, "doomed", "link"), "junction")
    rmSync(join(dir, "doomed"), { recursive: true, force: true })
    expect(existsSync(join(dir, "doomed"))).toBe(false)
    expect(readFileSync(join(dir, "target", "precious.txt"), "utf8")).toBe("keep me")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

// Why files.lstatEntry answers "socket" for an entry lstat refuses on Windows (§2.2 #7): Bun cannot lstat an AF_UNIX
// socket file there (EACCES), so a check that lstats a leftover socket (the MCP host's, a keeper's) must not take that
// for an error. If a Bun upgrade starts reading them, this goes red, and lstatEntry can answer from lstat everywhere.
test("lstat reads a socket file as a socket, except on Windows, where Bun refuses it (EACCES)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-sock-"))
  const server = createServer()
  try {
    const path = join(dir, "s.sock")
    await new Promise<void>(resolve => server.listen(path, resolve))
    if (process.platform === "win32") {
      let code: string | undefined
      try { lstatSync(path) } catch (error) { code = (error as NodeJS.ErrnoException).code }
      expect(code).toBe("EACCES")
    } else {
      expect(lstatSync(path).isSocket()).toBe(true)
    }
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()))
    rmSync(dir, { recursive: true, force: true })
  }
})

// Why proc.spawnDefaults passes `env` on every spawn: Bun.spawn without one gives the child the environment the process
// STARTED with, so a PATH or a .env variable main.ts set since never reaches it (node:child_process passes the live one).
// If a Bun upgrade passes the live environment, this goes red, and the default is only belt and braces.
test("a variable set after start reaches a Bun.spawn child only through env", () => {
  const key = "MUX_RUNTIME_LATE_VAR"
  process.env[key] = "set-after-start"
  try {
    const probe = [process.execPath, "-e", `console.log(process.env.${key} ?? "missing")`]
    expect(Bun.spawnSync(probe).stdout.toString().trim()).toBe("missing")
    expect(Bun.spawnSync(probe, { env: { ...process.env } }).stdout.toString().trim()).toBe("set-after-start")
  } finally {
    delete process.env[key]
  }
})
