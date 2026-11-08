// src/core/worktree/verify.ts
import { proc } from "@supermux/platform"
import { existsSync } from "fs"
import { join } from "path"
import { spawnCommandSync } from "../process/launcher"

/** The verify command is exactly the repo's `.mux/verify.sh`, or none.
 *  (Auto-detection now lives in verify-suggest.ts, used to GENERATE this file.) */
export function resolveVerifyCommand(worktreeDir: string): string | null {
  return existsSync(join(worktreeDir, ".mux", "verify.sh")) ? "bash .mux/verify.sh" : null
}

/**
 * Run the command in the worktree; capture combined output. A POSIX command line, run by bash everywhere (on Windows Git
 * for Windows' bash, proc.posixShell), never by cmd.exe.
 */
export function runVerify(worktreeDir: string, command: string): { ok: boolean; output: string } {
  const bash = proc.posixShell()
  if (!bash) return { ok: false, output: "Verify needs bash to run .mux/verify.sh: on Windows, install Git for Windows." }
  const r = spawnCommandSync(bash, ["-c", command], { cwd: worktreeDir, encoding: "utf-8", timeout: 600_000, stdio: ["ignore", "pipe", "pipe"] })
  const stdout = r.stdout?.toString() ?? ""
  if (!r.error && r.status === 0) return { ok: true, output: stdout }
  return { ok: false, output: `${stdout}${r.stderr?.toString() ?? ""}`.trim() || String(r.error?.message ?? `exit ${r.status}`) }
}
