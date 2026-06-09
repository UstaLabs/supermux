// src/core/worktree/verify.ts
import { execSync } from "child_process"
import { existsSync } from "fs"
import { join } from "path"

/** The verify command is exactly the repo's `.mux/verify.sh`, or none.
 *  (Auto-detection now lives in verify-suggest.ts, used to GENERATE this file.) */
export function resolveVerifyCommand(worktreeDir: string): string | null {
  return existsSync(join(worktreeDir, ".mux", "verify.sh")) ? "bash .mux/verify.sh" : null
}

/** Run the command in the worktree; capture combined output. */
export function runVerify(worktreeDir: string, command: string): { ok: boolean; output: string } {
  try {
    const out = execSync(command, { cwd: worktreeDir, encoding: "utf-8", timeout: 600_000, stdio: ["ignore", "pipe", "pipe"] })
    return { ok: true, output: out }
  } catch (e: any) {
    return { ok: false, output: `${e?.stdout?.toString?.() ?? ""}${e?.stderr?.toString?.() ?? ""}`.trim() || String(e?.message ?? e) }
  }
}
