// Installs an agent CLI on the broker host, headlessly. Each agent has its own
// official installer, per OS:
//   • macOS / Linux: the vendor's `curl … | bash` script through `bash -lc`.
//   • Windows: the vendor's PowerShell script (`irm … | iex`) through Windows
//     PowerShell 5.1 by its fixed path — there is no bash on a fresh Windows.
//   • "builtin": the vendor publishes no Windows script (OpenCode, Grok), so the
//     broker downloads, verifies and unpacks the release itself (install-builtin.ts).
// Every recipe is per user: no admin rights (no sudo, no UAC), no node, no git.
// Scripts run with NO TTY (stdin ignored) and a forced non-interactive env so they
// take their non-interactive branch and can never hang waiting for a prompt.
// Dependency-injected (spawn + builtin deps + isInstalled) so it unit-tests
// without real installs.
import { spawn as defaultSpawn, type ChildProcess } from "child_process"
import { existsSync, lstatSync, mkdirSync, readlinkSync, symlinkSync, unlinkSync } from "fs"
import { posix } from "path"
import { homedir } from "os"
import { agentDisplayName, type AgentKind } from "../../shared/agents"
import { makeLogger } from "../../shared/log"
import { addToUserPath, windowsPowerShellPath } from "../windows/user-install"
import { withAgentBinDirs, withNodeBinDirs } from "./bin-dirs"
import { BUILTIN_INSTALLERS, realBuiltinDeps, type BuiltinInstallDeps } from "./install-builtin"
import { LINUX_BUILTIN_INSTALLERS, realLinuxBuiltinDeps, type LinuxBuiltinDeps } from "./install-builtin-linux"
import { resolveCommand } from "../process/launcher"

const log = makeLogger("agents/install")

export type InstallShell = "bash" | "powershell" | "builtin"
/** How to install one agent on one OS. For "builtin", [script] names a BUILTIN_INSTALLERS entry. */
export interface InstallRecipe {
  shell: InstallShell
  script: string
  /** Windows: a dir (may use %VARS%) to put on the user PATH after a successful install, for a
   * vendor installer that leaves it to the user. */
  userPathDir?: string
}
export type InstallOs = "posix" | "win32"

/** Official, non-interactive, per-user installer per agent and OS. `null` = can't be installed there. */
export const INSTALL_RECIPES: Record<InstallOs, Record<AgentKind, InstallRecipe | null>> = {
  posix: {
    claude: { shell: "bash", script: "curl -fsSL https://claude.ai/install.sh | bash" },
    // The standalone installer verifies its own SHA-256 and needs no node. Without
    // CODEX_NON_INTERACTIVE it may read /dev/tty for a confirmation.
    codex: { shell: "bash", script: "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh" },
    cursor: { shell: "bash", script: "curl https://cursor.com/install -fsS | bash" },
    // ~/.opencode/bin is on the broker's PATH (bin-dirs.ts); don't let it edit rc files.
    opencode: { shell: "bash", script: "curl -fsSL https://opencode.ai/install | bash -s -- --no-modify-path" },
    grok: { shell: "bash", script: "curl -fsSL https://x.ai/cli/install.sh | bash" },
  },
  win32: {
    // claude's installer only TELLS the user to add %USERPROFILE%\.local\bin to PATH by hand.
    claude: { shell: "powershell", script: "irm https://claude.ai/install.ps1 | iex", userPathDir: "%USERPROFILE%\\.local\\bin" },
    codex: { shell: "powershell", script: "$env:CODEX_NON_INTERACTIVE='1'; irm https://chatgpt.com/codex/install.ps1 | iex" },
    cursor: { shell: "powershell", script: "irm 'https://cursor.com/install?win32=true' | iex" },
    opencode: { shell: "builtin", script: "opencode-windows" },
    grok: { shell: "builtin", script: "grok-windows" },
  },
}

const OS_NAMES: Partial<Record<NodeJS.Platform, string>> = { win32: "Windows", darwin: "macOS", linux: "Linux" }

/**
 * macOS/Linux without curl (Ubuntu Desktop ships wget, not curl). The claude, codex and grok
 * scripts download with curl OR wget, so only fetching the script changes. The cursor and
 * OpenCode scripts call curl themselves, so on Linux the broker installs those two itself
 * (install-builtin-linux.ts).
 */
export const NO_CURL_RECIPES: Record<AgentKind, { wget?: string; linuxBuiltin?: string }> = {
  claude: { wget: "wget --no-verbose -O- https://claude.ai/install.sh | bash" },
  codex: { wget: "wget --no-verbose -O- https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh" },
  cursor: { linuxBuiltin: "cursor-linux" },
  opencode: { linuxBuiltin: "opencode-linux" },
  grok: { wget: "wget --no-verbose -O- https://x.ai/cli/install.sh | bash" },
}

/**
 * The recipe for [kind] on [platform], or why there is none (a message for the user).
 * [hasCommand] answers "is <name> on PATH?" (macOS/Linux: curl, wget); without it curl is assumed.
 */
export function installRecipeFor(
  kind: AgentKind,
  platform: NodeJS.Platform,
  hasCommand: (name: string) => boolean = () => true,
): InstallRecipe | { unsupported: string } {
  const os: InstallOs | null = platform === "win32" ? "win32" : platform === "darwin" || platform === "linux" ? "posix" : null
  const recipe = os ? INSTALL_RECIPES[os][kind] : null
  if (!recipe) {
    return { unsupported: `${agentDisplayName(kind)} can't be installed from supermux on ${OS_NAMES[platform] ?? platform}: not supported on this OS.` }
  }
  if (os !== "posix" || hasCommand("curl")) return recipe
  const alt = NO_CURL_RECIPES[kind]
  if (alt.linuxBuiltin && platform === "linux") return { shell: "builtin", script: alt.linuxBuiltin }
  if (alt.wget && hasCommand("wget")) return { shell: "bash", script: alt.wget }
  return { unsupported: `${agentDisplayName(kind)}'s installer needs curl${alt.wget ? " or wget" : ""}, and this computer has neither. Install curl (e.g. sudo apt install curl), then try again.` }
}

/** Stops a Windows PowerShell 5.1 script from crawling: its progress bar slows Invoke-WebRequest
 * many times over, and an older default may still lack TLS 1.2. */
export const POWERSHELL_PREAMBLE =
  "$ProgressPreference = 'SilentlyContinue'; " +
  "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; "

/** The exact program + argv a recipe runs (no shell in between). Not for "builtin". */
export function installCommand(recipe: InstallRecipe, env: Record<string, string | undefined>): { cmd: string; args: string[] } {
  if (recipe.shell === "powershell") {
    return {
      cmd: windowsPowerShellPath(env),
      args: ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", POWERSHELL_PREAMBLE + recipe.script],
    }
  }
  // pipefail: a download that fails in `curl … | bash` must fail the job with ITS code. Without
  // it bash runs the empty script, exits 0, and "curl: command not found" reads as success.
  return { cmd: "bash", args: ["-lc", `set -o pipefail; ${recipe.script}`] }
}

/**
 * macOS/Linux: a CLI whose install dir is on no PATH but the broker's (OpenCode's ~/.opencode/bin:
 * `--no-modify-path`, or the builtin) also gets a link in ~/.local/bin — on PATH in a stock Ubuntu
 * login and where claude/codex/cursor already live — so it works in the user's own terminals.
 * No rc file is edited. Paths are relative to the home dir.
 */
export const LOCAL_BIN_LINKS: Partial<Record<AgentKind, { name: string; target: string }>> = {
  opencode: { name: "opencode", target: ".opencode/bin/opencode" },
}

/** The file operations [linkIntoLocalBin] needs. */
export interface LinkFs {
  /** What is at [p] (not following a symlink). */
  kind: (p: string) => "missing" | "symlink" | "other"
  exists: (p: string) => boolean
  readlink: (p: string) => string
  mkdir: (p: string) => void
  unlink: (p: string) => void
  symlink: (target: string, path: string) => void
}

export const realLinkFs: LinkFs = {
  kind: (p) => {
    try {
      return lstatSync(p).isSymbolicLink() ? "symlink" : "other"
    } catch {
      return "missing"
    }
  },
  exists: (p) => existsSync(p),
  readlink: (p) => readlinkSync(p),
  mkdir: (p) => { mkdirSync(p, { recursive: true }) },
  unlink: (p) => unlinkSync(p),
  symlink: (target, path) => symlinkSync(target, path),
}

/**
 * Link `~/.local/bin/<name>` → `~/<target>`. Replaces a symlink of ours (one already pointing into
 * the target's dir, or dangling); never touches a file or someone else's symlink — it says so
 * instead. Returns the line for the install log, or null when there is nothing to say.
 */
export function linkIntoLocalBin(home: string, link: { name: string; target: string }, fs: LinkFs): string | null {
  const target = posix.join(home, link.target)
  if (!fs.exists(target)) return null
  const dir = posix.join(home, ".local", "bin")
  const path = posix.join(dir, link.name)
  const what = fs.kind(path)
  if (what === "other") return `Not linking ${path}: a file someone else put there is in the way (${link.name} still runs in supermux).`
  if (what === "symlink") {
    const current = fs.readlink(path)
    const resolved = current.startsWith("/") ? current : posix.join(dir, current)
    if (resolved === target) return null
    const ours = posix.dirname(resolved) === posix.dirname(target) || !fs.exists(path)
    if (!ours) return `Not linking ${path}: it already points to ${current} (${link.name} still runs in supermux).`
    fs.unlink(path)
  }
  fs.mkdir(dir)
  fs.symlink(target, path)
  return `Linked ${path} -> ${target}, so ${link.name} also works in your own terminals.`
}

export type InstallState = "running" | "done" | "failed"
export interface InstallJob {
  state: InstallState
  log: string
  exitCode: number | null
  /** Why it failed, in a sentence (unsupported OS, the installer couldn't start, a refused download, …). */
  error?: string
}

// Narrow seam for tests (Node's `spawn` is heavily overloaded; the fake only
// implements this call shape).
export type InstallSpawnFn = (
  cmd: string,
  args: string[],
  opts: { env: Record<string, string>; stdio: ("pipe" | "ignore" | "inherit")[]; windowsHide?: boolean },
) => ChildProcess

export interface InstallDeps {
  spawn?: InstallSpawnFn
  /** Re-probe whether the agent's binary is on PATH (after the installer ran). */
  isInstalled: (kind: AgentKind) => boolean
  /** Home dir for resolving per-user bin dirs. Defaults to homedir(). */
  home?: string
  /** Defaults to process.platform. */
  platform?: NodeJS.Platform
  /** The environment installers start from. Defaults to process.env. */
  env?: Record<string, string | undefined>
  /** The builtin installers' machine access (fetch, fs, registry, …). Defaults to the real ones. */
  builtin?: Omit<BuiltinInstallDeps, "log">
  /** The same for the Linux builtins (curl missing). Defaults to the real ones. */
  linuxBuiltin?: Omit<LinuxBuiltinDeps, "log">
  /** Is <name> on the installers' PATH? Defaults to a lookup on [env]'s PATH. */
  hasCommand?: (name: string) => boolean
  /** macOS/Linux: the ~/.local/bin links ([LOCAL_BIN_LINKS]). Defaults to the real file system. */
  linkFs?: LinkFs
  /** Runs once the installer ended, before `isInstalled`: e.g. pick up the registry PATH it changed. */
  refreshPath?: () => Promise<unknown>
  /** Called once an install settles (done OR failed) — the set of installed agents may have moved. */
  onSettled?: (kind: AgentKind, job: InstallJob) => void
}

const MAX_LOG = 64 * 1024 // keep the tail bounded; installers can be chatty
const EXIT_OUTPUT_GRACE_MS = 2_000

/** Set PATH on [env] under the key it already uses (Windows keys are case-insensitive: `Path`). */
function setPath(env: Record<string, string>, value: string, platform: NodeJS.Platform): void {
  const key = platform === "win32" ? Object.keys(env).find((k) => k.toLowerCase() === "path") ?? "Path" : "PATH"
  env[key] = value
}

function getPath(env: Record<string, string>, platform: NodeJS.Platform): string | undefined {
  if (platform !== "win32") return env.PATH
  const key = Object.keys(env).find((k) => k.toLowerCase() === "path")
  return key ? env[key] : undefined
}

/** The installer's environment: the broker's, forced non-interactive, with the agent bin dirs on PATH. */
export function installEnv(base: Record<string, string | undefined>, home: string, platform: NodeJS.Platform): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(base)) if (typeof v === "string") env[k] = v
  Object.assign(env, {
    // Force non-interactive across the common installer ecosystems.
    CI: "1",
    NONINTERACTIVE: "1",
    DEBIAN_FRONTEND: "noninteractive",
    npm_config_yes: "true",
    CODEX_NON_INTERACTIVE: "1",
  })
  const path = withAgentBinDirs(withNodeBinDirs(getPath(env, platform), home, platform), home, platform, env)
  setPath(env, path, platform)
  return env
}

/**
 * Start a non-interactive install for `kind`. Returns a live `job` (mutated in
 * place as the installer runs — the caller stores it and polls it) plus a `done`
 * promise that resolves once the install settles. Never throws: an OS without a
 * recipe gives an already-failed job that says so.
 */
export function startInstall(kind: AgentKind, deps: InstallDeps): { job: InstallJob; done: Promise<void> } {
  const platform = deps.platform ?? process.platform
  const job: InstallJob = { state: "running", log: "", exitCode: null }
  const append = (chunk: unknown) => {
    job.log += String(chunk)
    if (job.log.length > MAX_LOG) job.log = job.log.slice(-MAX_LOG)
  }

  const baseEnv = deps.env ?? process.env
  const home = deps.home ?? homedir()
  const hasCommand = deps.hasCommand ?? ((name: string) => resolveCommand([name], installEnv(baseEnv, home, platform), platform) !== null)
  const recipe = installRecipeFor(kind, platform, hasCommand)
  if ("unsupported" in recipe) {
    job.state = "failed"
    job.error = recipe.unsupported
    append(`${recipe.unsupported}\n`)
    log.info("agent_install_unsupported", { kind, platform })
    return { job, done: Promise.resolve() }
  }

  let settled = false
  const done = new Promise<void>((resolve) => {
    const finish = async (code: number | null, error?: string) => {
      if (settled) return
      settled = true
      job.exitCode = code
      if (code === 0 && recipe.userPathDir && platform === "win32") {
        const machine = deps.builtin ?? realBuiltinDeps(deps.env ?? process.env)
        try {
          const wrote = await addToUserPath(machine, recipe.userPathDir, machine.env)
          if (wrote) append(`Added ${recipe.userPathDir} to your PATH.\n`)
        } catch (err) {
          append(`Couldn't add ${recipe.userPathDir} to your PATH: ${err instanceof Error ? err.message : String(err)}\n`)
        }
      }
      const localLink = LOCAL_BIN_LINKS[kind]
      if (code === 0 && localLink && platform !== "win32") {
        try {
          const line = linkIntoLocalBin(home, localLink, deps.linkFs ?? realLinkFs)
          if (line) append(`${line}\n`)
        } catch (err) {
          append(`Couldn't link ${localLink.name} into ~/.local/bin: ${err instanceof Error ? err.message : String(err)}\n`)
        }
      }
      try {
        await deps.refreshPath?.()
      } catch (err) {
        log.warn("agent_install_refresh_path_failed", { kind, err: String(err) })
      }
      // "done" only if the installer succeeded AND the binary is actually on
      // PATH now — a 0 exit that produced no usable binary is still a failure.
      const installed = code === 0 && deps.isInstalled(kind)
      job.state = installed ? "done" : "failed"
      // A non-zero exit speaks for itself (the client shows the code and the log tail).
      if (!installed) {
        const reason = error ?? (code === 0
          ? `The installer finished, but supermux can't find the ${agentDisplayName(kind)} CLI on this computer.`
          : undefined)
        if (reason) job.error = reason
      }
      log.info("agent_install_finished", { kind, state: job.state, exitCode: code, shell: recipe.shell })
      resolve()
    }

    if (recipe.shell === "builtin") {
      const line = (l: string) => append(`${l}\n`)
      const windows = BUILTIN_INSTALLERS[recipe.script]
      const linux = LINUX_BUILTIN_INSTALLERS[recipe.script]
      const run = windows
        ? () => windows({ ...(deps.builtin ?? realBuiltinDeps(baseEnv)), log: line })
        : linux
          ? () => linux({ ...(deps.linuxBuiltin ?? realLinuxBuiltinDeps(home)), log: line })
          : null
      if (!run) {
        void finish(null, `no builtin installer named ${recipe.script}`)
        return
      }
      run().then(
        () => finish(0),
        (err: unknown) => {
          const message = err instanceof Error ? err.message : String(err)
          append(`${message}\n`)
          void finish(1, message)
        },
      )
      return
    }

    const env = installEnv(baseEnv, home, platform)
    const { cmd, args } = installCommand(recipe, env)
    const spawnFn = deps.spawn ?? (defaultSpawn as unknown as InstallSpawnFn)
    let child: ChildProcess
    try {
      // stdio[0] = "ignore" → the installer's stdin is NOT a TTY.
      child = spawnFn(cmd, args, { env, stdio: ["ignore", "pipe", "pipe"], windowsHide: true })
    } catch (err) {
      const message = `couldn't start ${cmd}: ${err instanceof Error ? err.message : String(err)}`
      append(`${message}\n`)
      void finish(null, message)
      return
    }
    // Attached synchronously, before anything else: an unhandled `error` (ENOENT
    // for a missing shell) would take the whole broker down.
    child.on("error", (err: Error) => {
      const message = `couldn't start ${cmd}: ${err.message}`
      append(`\n${message}\n`)
      void finish(null, message)
    })
    // `exit` can come before the last output: wait for `close` (all output read),
    // but not forever — a background process the installer left behind may hold
    // the pipes open.
    child.on("exit", (code) => {
      const timer = setTimeout(() => { void finish(code) }, EXIT_OUTPUT_GRACE_MS)
      child.once("close", () => {
        clearTimeout(timer)
        void finish(code)
      })
    })
    child.stdout?.on("data", append)
    child.stderr?.on("data", append)
  })

  log.info("agent_install_started", { kind, shell: recipe.shell })
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
      const { job, done } = startInstall(kind, deps)
      jobs.set(kind, job)
      void done.then(() => deps.onSettled?.(kind, job))
      return { job, alreadyRunning: false }
    },
    get(kind) {
      return jobs.get(kind)
    },
  }
}
