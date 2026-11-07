// The git family's synchronous runner, for the callers that cannot await yet: branches, integrate, remote, pr, clone
// and the editor's synchronous repo scan. Each call blocks the broker's one event loop until the command ends, so new
// code takes exec.ts's async helpers. Through the launcher: the live env, no console window, Windows shims run safely.
import { spawnCommandSync } from "../process/launcher"

/**
 * [cmd] run to its end: ok with its stdout trimmed, or not ok (a non-zero exit, its timeout, no such command) with its
 * stdout and stderr, trimmed. [env], when given, is the whole environment.
 */
export function runSync(
  cmd: string,
  args: string[],
  opts: { cwd?: string; timeoutMs: number; env?: Record<string, string | undefined> },
): { ok: boolean; out: string } {
  const env = opts.env ? { env: opts.env as NodeJS.ProcessEnv } : {}
  const r = spawnCommandSync(cmd, args, { ...env, cwd: opts.cwd, encoding: "utf-8", timeout: opts.timeoutMs, stdio: ["pipe", "pipe", "pipe"] })
  const stdout = r.stdout?.toString() ?? ""
  if (!r.error && r.status === 0) return { ok: true, out: stdout.trim() }
  return { ok: false, out: `${stdout}${r.stderr?.toString() ?? ""}`.trim() }
}

/** [runSync] of git in [cwd]. */
export function gitSync(cwd: string, args: string[], timeoutMs: number): { ok: boolean; out: string } {
  return runSync("git", args, { cwd, timeoutMs })
}

/** [runSync]'s stdout, trimmed; when the command fails, an error with what it said (or that it failed). */
export function runSyncOrThrow(
  cmd: string,
  args: string[],
  opts: { cwd?: string; timeoutMs: number; env?: Record<string, string | undefined> },
): string {
  const r = runSync(cmd, args, opts)
  if (!r.ok) throw new Error(r.out || `${cmd} ${args[0] ?? ""} failed`.trim())
  return r.out
}
