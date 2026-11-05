// Processes. Only what the runtimes get wrong or leave out (platform-layer spec §3.3): Node and Bun signal a
// process the same way everywhere, but say nothing about who owns it; Bun.which searches the PATH the process started
// with, never .ps1, and Node has no which at all.
import { spawn as nodeSpawn } from "node:child_process"
import { accessSync, constants, statSync } from "node:fs"
import { posix, win32 } from "node:path"

/** Whether a process with [pid] exists. `EPERM` (it exists but is someone else's) is alive; only `ESRCH` is gone. */
export function isAlive(pid: number): boolean {
  return isAliveWith(pid, (p) => process.kill(p, 0))
}

/** [isAlive] with the signal-0 call injected, for tests. */
export function isAliveWith(pid: number, signal0: (pid: number) => unknown): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false
  try {
    signal0(pid)
    return true
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM"
  }
}

export type CommandEnvironment = Record<string, string | undefined>

export interface ResolveOptions {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** Whether a candidate is a command (tests); a file, and on POSIX an executable one, by default. */
  fileExists?: (path: string) => boolean
}

/** Variable [name] of [env], read the way the OS reads it: in any letter case on Windows (`Path`), exactly elsewhere. */
export function envValue(env: CommandEnvironment, name: string, platform: NodeJS.Platform = process.platform): string | undefined {
  if (platform !== "win32") return env[name]
  let value: string | undefined
  for (const key of Object.keys(env)) {
    if (key.toLowerCase() === name.toLowerCase()) value = env[key]
  }
  return value
}

function windowsExtensions(env: CommandEnvironment): string[] {
  // Keep broker-supported launch types deterministic. PATHEXT can add native
  // executable suffixes, but cannot reorder exe/cmd/ps1 or make ps1 invisible.
  const preferred = [".exe", ".cmd", ".ps1"]
  // Read PATHEXT case-insensitively because Windows commonly exposes it as
  // `PATHEXT` while test/service environments may use another casing. We do
  // not adopt arbitrary associations (.js, .py, …): spawnCommand only has
  // safe, explicit wrappers for the formats below.
  const supportedAssociations = new Set([".exe", ".cmd", ".ps1", ".bat"])
  const safeExtras = (envValue(env, "PATHEXT", "win32") ?? "")
    .split(";")
    .map((ext) => ext.trim().toLowerCase())
    .filter((ext) => supportedAssociations.has(ext) && !preferred.includes(ext))
  return [...preferred, ...new Set(safeExtras)]
}

function isCommandFile(path: string, platform: NodeJS.Platform): boolean {
  try {
    if (!statSync(path).isFile()) return false
    if (platform !== "win32") accessSync(path, constants.X_OK)
    return true
  } catch {
    return false
  }
}

function isExplicitPath(command: string, platform: NodeJS.Platform): boolean {
  return platform === "win32"
    ? /^[A-Za-z]:[\\/]/.test(command) || command.includes("\\") || command.includes("/")
    : command.includes("/")
}

/**
 * The first of [names] found as a command: a path as given, a bare name in the PATH of [env], read at the call (a
 * folder added since the process started counts, which Bun.which without a PATH misses). On Windows, within a name:
 * .exe, then .cmd, then .ps1, then the .bat that PATHEXT adds, the kinds the broker's launcher can start. Null when
 * none is found.
 */
export function resolve(names: readonly string[], env: CommandEnvironment = process.env, options: ResolveOptions = {}): string | null {
  const platform = options.platform ?? process.platform
  const fileExists = options.fileExists ?? ((path: string) => isCommandFile(path, platform))
  const pathApi = platform === "win32" ? win32 : posix
  const pathDirs = (envValue(env, "PATH", platform) ?? "")
    .split(platform === "win32" ? ";" : ":")
    .filter(Boolean)

  for (const name of names) {
    const explicit = isExplicitPath(name, platform)
    if (platform !== "win32") {
      const candidates = explicit ? [name] : pathDirs.map((dir) => pathApi.join(dir, name))
      for (const candidate of candidates) if (fileExists(candidate)) return candidate
      continue
    }

    const hasExtension = /\.[^\\/.]+$/.test(name)
    const extensions = hasExtension ? [""] : windowsExtensions(env)
    const bases = explicit ? [""] : pathDirs
    // Alias priority is caller-defined; within an alias prefer executable kind
    // before PATH directory, matching name.exe -> name.cmd -> name.ps1.
    for (const extension of extensions) {
      for (const base of bases) {
        const candidate = explicit ? `${name}${extension}` : win32.join(base, `${name}${extension}`)
        if (fileExists(candidate)) return candidate
      }
    }
  }
  return null
}

/**
 * The options every spawn starts from: the environment as it is at the call (Bun.spawn without `env` passes the one
 * the process started with, so a PATH or a .env variable set since never reaches the child), and no console window on
 * Windows (windowsHide is false by default in Bun and Node). Spread first, so a caller's own `env` or `windowsHide`
 * wins: `Bun.spawn(argv, { ...proc.spawnDefaults(), stdout: "pipe" })`.
 */
export function spawnDefaults(): { env: Record<string, string | undefined>; windowsHide: boolean } {
  return { env: { ...process.env }, windowsHide: true }
}

export interface KillTreeOptions {
  /** The signal on POSIX; SIGTERM by default. Windows has none: taskkill /F ends the tree. */
  signal?: NodeJS.Signals
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** Starts taskkill (tests); node:child_process's spawn by default. */
  spawn?: (command: string, args: string[], options: { stdio: "ignore"; windowsHide: true }) => { on(event: "error", listener: () => void): unknown }
  /** Sends a signal (tests); process.kill by default. */
  kill?: (pid: number, signal: NodeJS.Signals) => void
}

/**
 * Ends [pid] and what it started. Windows: `taskkill /T /F` (it has no signals, and a .cmd shim's cmd.exe is the root,
 * not the CLI). Elsewhere: the signal to the process group [pid] leads (a detached child, a terminal's session), else to
 * [pid] alone. A process already gone is not an error.
 */
export function killTree(pid: number, options: KillTreeOptions = {}): void {
  const kill = options.kill ?? ((target: number, signal: NodeJS.Signals) => { process.kill(target, signal) })
  const signal = options.signal ?? "SIGTERM"
  if ((options.platform ?? process.platform) === "win32") {
    // Without taskkill, the process itself at least (process.kill is TerminateProcess there).
    const alone = () => { try { kill(pid, signal) } catch { /* gone */ } }
    try {
      ;(options.spawn ?? nodeSpawn)("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore", windowsHide: true }).on("error", alone)
    } catch { alone() }
    return
  }
  try {
    kill(-pid, signal)
  } catch {
    try { kill(pid, signal) } catch { /* gone */ }
  }
}

/**
 * A bash that runs a POSIX shell script (a worktree's .mux/worktree-setup.sh or .mux/verify.sh): the bash on PATH. On
 * Windows, Git for Windows' bash.exe: on PATH, beside the git on PATH, or where Git installs, but never WSL's
 * System32\bash.exe, which runs the script inside Linux. Null when there is none (MinGit, the git the broker installs,
 * has no bash).
 */
export function posixShell(env: CommandEnvironment = process.env, options: ResolveOptions = {}): string | null {
  const platform = options.platform ?? process.platform
  if (platform !== "win32") return resolve(["bash"], env, options)
  const fileExists = options.fileExists ?? ((path: string) => isCommandFile(path, platform))
  const system = (envValue(env, "SystemRoot", platform) ?? "C:\\Windows").toLowerCase()
  const candidates = (envValue(env, "PATH", platform) ?? "").split(";").filter(Boolean).map((dir) => win32.join(dir, "bash.exe"))
  // <Git>\cmd\git.exe (or <Git>\bin\git.exe) has its bash at <Git>\bin\bash.exe.
  const git = resolve(["git"], env, { platform, fileExists })
  if (git) candidates.push(win32.join(win32.dirname(win32.dirname(git)), "bin", "bash.exe"))
  const local = envValue(env, "LOCALAPPDATA", platform)
  for (const base of [envValue(env, "ProgramFiles", platform), envValue(env, "ProgramW6432", platform), envValue(env, "ProgramFiles(x86)", platform), local && win32.join(local, "Programs")]) {
    if (base) candidates.push(win32.join(base, "Git", "bin", "bash.exe"))
  }
  return candidates.find((path) => !path.toLowerCase().startsWith(system) && fileExists(path)) ?? null
}
