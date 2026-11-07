// scripts/windows-vm/assert-msi.ps1 on Windows PowerShell 5.1. With exactly one install folder, its candidate list was a
// single string, and `.Count` on it throws under Set-StrictMode -Version Latest ("The property 'Count' cannot be found
// on this object"): the 2026-11-07 Windows smoke of the v0.17.6 dry run hit it.
import { expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { copyFileSync, mkdirSync, mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

test.skipIf(process.platform !== "win32")("assert-msi finds the one install folder and reports its three executables", () => {
  const root = mkdtempSync(join(tmpdir(), "assert-msi-"))
  try {
    const resources = join(root, "pf", "supermux", "app", "resources")
    mkdirSync(resources, { recursive: true })
    // Any real x64 PE does for the check: this test's own runtime.
    for (const name of ["supermux-broker.exe", "mux-sessiond.exe", "frpc.exe"]) copyFileSync(process.execPath, join(resources, name))
    const env = { ...process.env, ProgramFiles: join(root, "pf"), "ProgramFiles(x86)": join(root, "none"), LOCALAPPDATA: join(root, "none") }
    const script = join(import.meta.dir, "..", "scripts", "windows-vm", "assert-msi.ps1")
    const run = spawnSync("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script], { env, encoding: "utf8", windowsHide: true })
    expect(run.status, `${run.stdout}\n${run.stderr}`).toBe(0)
    expect(run.stdout).toContain("SUPERMUX_MSI_ASSERT_OK")
    for (const name of ["supermux-broker.exe", "mux-sessiond.exe", "frpc.exe"]) expect(run.stdout).toContain(name)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
