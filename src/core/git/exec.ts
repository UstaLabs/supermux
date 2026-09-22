// The ONLY child-process helpers the worktree/git-inspection code may use. The
// broker is one event loop: a synchronous git call freezes every session, and
// a freeze past 2 s makes new Claude shims give up registering (2026-09-22).
import { execFile, spawn } from "node:child_process"

export function gitAsync(cwd: string, args: string[], opts?: { timeoutMs?: number }): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile("git", args, { cwd, encoding: "utf-8", timeout: opts?.timeoutMs ?? 30_000, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) {
        const msg = String(stderr || "").trim() || err.message
        reject(new Error(msg))
        return
      }
      resolve(String(stdout).trim())
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
    const child = spawn(cmd, args, { cwd: opts.cwd, env: opts.env as NodeJS.ProcessEnv | undefined, stdio: ["ignore", "pipe", "pipe"] })
    const append = (b: Buffer) => { out += b.toString("utf-8"); if (out.length > max) out = out.slice(out.length - max) }
    child.stdout.on("data", append)
    child.stderr.on("data", append)
    const timer = opts.timeoutMs ? setTimeout(() => { timedOut = true; child.kill("SIGKILL") }, opts.timeoutMs) : undefined
    child.on("error", (e) => { if (timer) clearTimeout(timer); resolve({ code: -1, output: String(e.message), timedOut }) })
    child.on("close", (code) => { if (timer) clearTimeout(timer); resolve({ code: code ?? -1, output: out, timedOut }) })
  })
}
