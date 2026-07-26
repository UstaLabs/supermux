import { spawn as defaultSpawn, type ChildProcess } from "child_process"
import type { AcpClient } from "./acp-client"
import { makeLogger } from "../../../shared/log"
import { resolveCommand, spawnCommand, type FileExists } from "../../process/launcher"

const log = makeLogger("agents/grok/runner")

/** Cap retained stderr so a chatty child can't balloon memory; last N chars keep
 * the most recent (usually most relevant) diagnostic lines. */
const STDERR_CAP = 4_000

export type GrokRunner = (opts: {
  workdir: string
  env: Record<string, string>
  client: AcpClient
  /** code + recent stderr so the adapter can surface a faithful error to the user. */
  onExit: (code: number | null, stderr?: string) => void
  /** Passed as `--model`; ACP session/set_model can change it later without a respawn. */
  model?: string
  /** Passed as `--reasoning-effort`. There is no ACP method to change this on a live
   * agent (session/set_reasoning_effort is "Method not found"), so an effort switch
   * has to respawn the child. */
  effort?: string
}) => { kill: () => void }

/** Real runner: spawns `grok agent [--model M] [--reasoning-effort E] --always-approve stdio`.
 * Flags precede the `stdio` subcommand (`grok agent [OPTIONS] [COMMAND]`). Points the
 * client's writes at the child's stdin (appending the newline framing) and feeds the
 * child's stdout back into the client.
 *
 * `--always-approve` auto-approves tool execution, matching cursor's `--force`: the
 * broker drives grok unattended, so an interactive permission prompt would deadlock
 * the turn. The adapter still answers session/request_permission defensively. */
export function makeRealGrokRunner(deps: {
  platform?: NodeJS.Platform
  fileExists?: FileExists
  spawn?: (command: string, args: string[], options: Record<string, unknown>) => ChildProcess
} = {}): GrokRunner {
  return ({ workdir, env, client, onExit, model, effort }) => {
  const args = [
    "agent",
    ...(model ? ["--model", model] : []),
    ...(effort ? ["--reasoning-effort", effort] : []),
    "--always-approve",
    "stdio",
  ]
  const childEnv = { ...process.env, ...env } as Record<string, string>
  const platform = deps.platform ?? process.platform
  const shouldResolve = !deps.spawn || deps.platform !== undefined || deps.fileExists !== undefined
  const command = shouldResolve ? (resolveCommand(["grok"], childEnv, platform, { fileExists: deps.fileExists }) ?? "grok") : "grok"
  const child = spawnCommand(command, args, {
    platform, fileExists: deps.fileExists, spawn: (deps.spawn ?? defaultSpawn) as never,
    cwd: workdir,
    env: childEnv,
    stdio: ["pipe", "pipe", "pipe"],
  })
  let stderrBuf = ""
  child.stdout.setEncoding("utf8")
  child.stdout.on("data", (chunk: string) => client.feed(chunk))
  child.stderr.setEncoding("utf8")
  child.stderr.on("data", (d: string) => {
    stderrBuf += d
    if (stderrBuf.length > STDERR_CAP) stderrBuf = stderrBuf.slice(-STDERR_CAP)
    // Keep a short debug line; the full buffered tail rides out via onExit for the UI.
    log.debug("grok_stderr", { d: d.slice(0, 500) })
  })
  child.on("exit", (code) => {
    const stderr = stderrBuf.trim() || undefined
    log.info("grok_exit", { code, stderr: stderr?.slice(0, 500) })
    onExit(code, stderr)
  })
  child.on("error", (e) => {
    log.warn("grok_spawn_error", { err: String(e) })
    onExit(null, String(e))
  })
  client.setWrite((line: string) => {
    if (child.stdin!.writable) child.stdin!.write(line + "\n")
  })
  return { kill: () => { try { child.kill("SIGTERM") } catch {} } }
  }
}

export const realGrokRunner: GrokRunner = makeRealGrokRunner()
