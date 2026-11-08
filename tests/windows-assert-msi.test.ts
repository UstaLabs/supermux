// scripts/windows-vm/assert-msi.ps1 on Windows PowerShell 5.1. With exactly one install folder, its candidate list was a
// single string, and `.Count` on it throws under Set-StrictMode -Version Latest ("The property 'Count' cannot be found
// on this object"): the 2026-11-07 Windows smoke of the v0.17.6 dry run hit it.
import { expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

// The fake install goes where a per-user install would be, %LOCALAPPDATA%\Programs\supermux: a child process gets its
// own %ProgramFiles% from Windows whatever the parent passes. A machine with supermux really installed has a second
// candidate, which the script rightly refuses, so the test needs one without.
const realInstall = [process.env.ProgramFiles, process.env["ProgramFiles(x86)"]].some(dir => dir && existsSync(join(dir, "supermux")))

test.skipIf(process.platform !== "win32" || realInstall)("assert-msi finds the one install folder and reports its two executables", () => {
  const root = mkdtempSync(join(tmpdir(), "assert-msi-"))
  try {
    const resources = join(root, "Programs", "supermux", "app", "resources")
    mkdirSync(resources, { recursive: true })
    // Any real x64 PE does for the check: this test's own runtime.
    // No frpc.exe: the tunnel runs inside the broker (903e629ba), and an MSI without one is complete.
    for (const name of ["supermux-broker.exe", "mux-sessiond.exe"]) copyFileSync(process.execPath, join(resources, name))
    // Windows' variable names ignore case: drop every spelling of LOCALAPPDATA before setting it, so only one reaches it.
    // PSModulePath goes too: under the lane's PowerShell 7 it names PowerShell 7's modules, where Windows PowerShell 5.1
    // finds no Get-FileHash ("not recognized"); without it, 5.1 builds its own.
    const env: Record<string, string | undefined> = Object.fromEntries(
      Object.entries(process.env).filter(([name]) => !["LOCALAPPDATA", "PSMODULEPATH"].includes(name.toUpperCase())),
    )
    env.LOCALAPPDATA = root
    const script = join(import.meta.dir, "..", "scripts", "windows-vm", "assert-msi.ps1")
    const run = spawnSync("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script], { env, encoding: "utf8", windowsHide: true })
    expect(run.status, `${run.stdout}\n${run.stderr}`).toBe(0)
    // Resolve-Path may spell the temp folder differently (a short 8.3 name): only its end is certain.
    expect(run.stdout).toMatch(/INSTALL_DIR=.+\\Programs\\supermux\s/)
    expect(run.stdout).toContain("SUPERMUX_MSI_ASSERT_OK")
    for (const name of ["supermux-broker.exe", "mux-sessiond.exe"]) expect(run.stdout).toContain(name)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
