// A command run to its end through the launcher: never a shell, a Windows .cmd/.ps1 shim wrapped, the live env, no
// console window. For the callers that only want its answer (keep-awake's probes, the git requirement, the Windows
// install's PowerShell), each of which had its own execFile callback.
import type { ChildProcess } from "node:child_process"
import { proc } from "@supermux/platform"
import { spawnCommand } from "./launcher"

export interface RunResult {
  /** Its exit code; null when it could not start or was stopped. */
  code: number | null
  stdout: string
  stderr: string
  /** Why it could not start (ENOENT, say). */
  error?: string
  /** It ran past its timeout and was stopped. */
  timedOut: boolean
}

export interface RunOptions {
  cwd?: string
  /** The whole environment; the live one by default. */
  env?: NodeJS.ProcessEnv
  /** Stops it (its process tree) after this long. */
  timeoutMs?: number
  /** What it keeps of stdout and of stderr, each; 1 Mi characters by default. */
  maxChars?: number
}

/** [command] with [args], run to its end: its exit code and output, or the error that kept it from starting. */
export function runCommand(command: string, args: readonly string[], opts: RunOptions = {}): Promise<RunResult> {
  return new Promise((resolve) => {
    const max = opts.maxChars ?? 1 << 20
    let stdout = ""
    let stderr = ""
    let timedOut = false
    let settled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const done = (code: number | null, error?: string) => {
      if (settled) return
      settled = true
      if (timer) clearTimeout(timer)
      resolve({ code, stdout, stderr, timedOut, ...(error ? { error } : {}) })
    }
    let child: ChildProcess
    try {
      child = spawnCommand(command, args, { ...(opts.cwd ? { cwd: opts.cwd } : {}), ...(opts.env ? { env: opts.env } : {}), stdio: ["ignore", "pipe", "pipe"] })
    } catch (error) {
      done(null, error instanceof Error ? error.message : String(error))
      return
    }
    if (opts.timeoutMs) timer = setTimeout(() => { timedOut = true; if (child.pid) proc.killTree(child.pid) }, opts.timeoutMs)
    const keep = (text: string, chunk: Buffer) => (text.length < max ? (text + chunk.toString("utf8")).slice(0, max) : text)
    child.stdout!.on("data", (chunk: Buffer) => { stdout = keep(stdout, chunk) })
    child.stderr!.on("data", (chunk: Buffer) => { stderr = keep(stderr, chunk) })
    child.on("error", (error) => done(null, error.message))
    child.on("close", (code) => done(code))
  })
}
