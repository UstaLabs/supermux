// The ONLY child-process helpers the worktree/git-inspection code may use. The
// broker is one event loop: a synchronous git call freezes every session, and
// a freeze past 2 s makes new Claude shims give up registering (2026-09-22).
// Every call starts from proc.spawnDefaults: the live env, no console window on Windows. A caller that cannot await
// yet has exec-sync.ts.
import { execFile, spawn } from "node:child_process"
import { proc } from "@supermux/platform"

/** `trim: false` keeps stdout byte-exact (needed for `-z` / porcelain output whose
 *  first record may start with a space). `okExitCodes` lists non-zero exits that
 *  still carry the answer on stdout (`git diff --no-index` exits 1 when the paths differ). */
export function gitAsync(
  cwd: string,
  args: string[],
  opts?: { timeoutMs?: number; trim?: boolean; okExitCodes?: number[]; env?: Record<string, string | undefined> },
): Promise<string> {
  const timeoutMs = opts?.timeoutMs ?? 30_000
  const env = opts?.env ? { env: opts.env as NodeJS.ProcessEnv } : {}
  return new Promise((resolve, reject) => {
    execFile("git", args, { ...proc.spawnDefaults(), ...env, cwd, encoding: "utf-8", timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      const code = (err as { code?: unknown } | null)?.code
      if (err && !(typeof code === "number" && opts?.okExitCodes?.includes(code))) {
        // A git cut short says so: its stderr then holds only half-written progress.
        const { killed, signal } = err as { killed?: boolean; signal?: string | null }
        const msg = killed
          ? `git ${args[0]} timed out after ${timeoutMs >= 1000 ? `${Math.round(timeoutMs / 1000)} s` : `${timeoutMs} ms`}`
          : signal ? `git ${args[0]} was stopped (${signal})` : String(stderr || "").trim() || err.message
        reject(new Error(msg))
        return
      }
      resolve(opts?.trim === false ? String(stdout) : String(stdout).trim())
    })
  })
}

export interface RunResult { code: number; output: string; timedOut: boolean }

export function runAsync(
  cmd: string,
  args: string[],
  opts: { cwd: string; env?: Record<string, string | undefined>; timeoutMs?: number; maxOutput?: number },
): Promise<RunResult> {
  const max = opts.maxOutput ?? 64 * 1024
  return new Promise((resolve) => {
    let out = ""
    let timedOut = false
    const env = opts.env ? { env: opts.env as NodeJS.ProcessEnv } : {}
    const child = spawn(cmd, args, { ...proc.spawnDefaults(), ...env, cwd: opts.cwd, stdio: ["ignore", "pipe", "pipe"] })
    const append = (b: Buffer) => { out += b.toString("utf-8"); if (out.length > max) out = out.slice(out.length - max) }
    child.stdout.on("data", append)
    child.stderr.on("data", append)
    const timer = opts.timeoutMs ? setTimeout(() => { timedOut = true; proc.stop(child) }, opts.timeoutMs) : undefined
    child.on("error", (e) => { if (timer) clearTimeout(timer); resolve({ code: -1, output: String(e.message), timedOut }) })
    child.on("close", (code) => { if (timer) clearTimeout(timer); resolve({ code: code ?? -1, output: out, timedOut }) })
  })
}

