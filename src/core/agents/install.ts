// Installs an agent CLI on the broker host, headlessly. Each agent has its own
// official installer; we run it through `bash -lc` with NO TTY (stdin ignored)
// and a forced non-interactive env so `curl|bash` / npm installers take their
// non-interactive branch and can never hang waiting for a prompt. Dependency-
// injected (spawn + isInstalled) so it unit-tests without real installs.
import { spawn as defaultSpawn, type ChildProcess } from "child_process"
import type { AgentKind } from "../../shared/agents"
import { makeLogger } from "../../shared/log"

const log = makeLogger("agents/install")

/** Official, non-interactive installer per agent. */
export const INSTALL_RECIPES: Record<AgentKind, string> = {
  claude: "curl -fsSL https://claude.ai/install.sh | bash",
  codex: "npm install -g @openai/codex",
  cursor: "curl https://cursor.com/install -fsS | bash",
  opencode: "curl -fsSL https://opencode.ai/install | bash",
  grok: "curl -fsSL https://x.ai/cli/install.sh | bash",
}

export type InstallState = "running" | "done" | "failed"
export interface InstallJob {
  state: InstallState
  log: string
  exitCode: number | null
}

// Narrow seam for tests (Node's `spawn` is heavily overloaded; the fake only
// implements this call shape).
export type InstallSpawnFn = (
  cmd: string,
  args: string[],
  opts: { env: Record<string, string>; stdio: ("pipe" | "ignore" | "inherit")[] },
) => ChildProcess

export interface InstallDeps {
  spawn?: InstallSpawnFn
  /** Re-probe whether the agent's binary is on PATH (after the installer ran). */
  isInstalled: (kind: AgentKind) => boolean
}

const MAX_LOG = 64 * 1024 // keep the tail bounded; installers can be chatty

/**
 * Start a non-interactive install for `kind`. Returns a live `job` (mutated in
 * place as the child runs — the caller stores it and polls it) plus a `done`
 * promise that resolves once the install settles. Throws if `kind` has no recipe.
 */
export function startInstall(kind: AgentKind, deps: InstallDeps): { job: InstallJob; done: Promise<void> } {
  const recipe = INSTALL_RECIPES[kind]
  if (!recipe) throw new Error(`no install recipe for agent: ${kind}`)

  const spawnFn = deps.spawn ?? (defaultSpawn as unknown as InstallSpawnFn)
  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    // Force non-interactive across the common installer ecosystems.
    CI: "1",
    NONINTERACTIVE: "1",
    DEBIAN_FRONTEND: "noninteractive",
    npm_config_yes: "true",
  }

  const job: InstallJob = { state: "running", log: "", exitCode: null }
  // stdio[0] = "ignore" → the installer's stdin is NOT a TTY.
  const child = spawnFn("bash", ["-lc", recipe], { env, stdio: ["ignore", "pipe", "pipe"] })

  const append = (chunk: unknown) => {
    job.log += String(chunk)
    if (job.log.length > MAX_LOG) job.log = job.log.slice(-MAX_LOG)
  }
  child.stdout?.on("data", append)
  child.stderr?.on("data", append)

  const done = new Promise<void>((resolve) => {
    const finish = (code: number | null) => {
      job.exitCode = code
      // "done" only if the installer succeeded AND the binary is actually on
      // PATH now — a 0 exit that produced no usable binary is still a failure.
      job.state = code === 0 && deps.isInstalled(kind) ? "done" : "failed"
      log.info("agent_install_finished", { kind, state: job.state, exitCode: code })
      resolve()
    }
    child.on("error", (err: Error) => {
      append(`\n${err.message}`)
      finish(null)
    })
    child.on("exit", (code) => finish(code))
  })

  log.info("agent_install_started", { kind })
  return { job, done }
}

export interface InstallManager {
  /** Start (or no-op onto a still-running) install. `alreadyRunning` lets the
   * HTTP layer answer 409 without starting a duplicate. */
  start: (kind: AgentKind) => { job: InstallJob; alreadyRunning: boolean }
  /** Latest job for the agent (running or settled), or undefined if never started. */
  get: (kind: AgentKind) => InstallJob | undefined
}

/** Owns one install job per agent (mirrors the agent-login manager). */
export function createInstallManager(deps: InstallDeps): InstallManager {
  const jobs = new Map<AgentKind, InstallJob>()
  return {
    start(kind) {
      const existing = jobs.get(kind)
      if (existing && existing.state === "running") return { job: existing, alreadyRunning: true }
      const { job } = startInstall(kind, deps)
      jobs.set(kind, job)
      return { job, alreadyRunning: false }
    },
    get(kind) {
      return jobs.get(kind)
    },
  }
}
