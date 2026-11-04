// Facts about the runtime that supermux's own code relies on. Each one is a behaviour we chose to build on, or
// around, after checking it (see the platform-layer spec, §3.2). If a Bun upgrade changes one, this file goes red
// before users notice. The Windows lane runs it too, so a fact that differs on Windows shows up there.
import { expect, test } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "node:fs"
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
