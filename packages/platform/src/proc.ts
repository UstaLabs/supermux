// Processes. Only what the runtimes get wrong or leave out (platform-layer spec §3.3): Node and Bun signal a
// process the same way everywhere, but say nothing about who owns it; Bun.which searches the PATH the process started
// with, never .ps1, and Node has no which at all.
import {
  spawn as nodeSpawn, spawnSync as nodeSpawnSync, type ChildProcess, type SpawnOptions, type SpawnSyncOptions,
  type SpawnSyncReturns,
} from "node:child_process"
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

// Starting a Windows shim without a shell reading its arguments (BatBadBut): the strategy of mature Windows Node
// launchers (cross-spawn). Quotes and the backslashes before them are escaped for CommandLineToArgvW, then every cmd.exe
// metacharacter is caret-escaped, with delayed expansion off. One rule for the broker's launcher and core's login.
const CMD_META = /([()\][%!^"`<>&|;, *?])/g

// Linear: double every backslash before a quote or the closing wrapper quote, then add the one that escapes the quote.
function escapeWindowsQuoteSlashes(value: string): string {
  const escaped: string[] = []
  let slashes = 0
  for (const char of value) {
    if (char === "\\") {
      slashes++
      continue
    }
    escaped.push(char === '"' ? "\\".repeat(slashes * 2 + 1) : "\\".repeat(slashes), char)
    slashes = 0
  }
  escaped.push("\\".repeat(slashes * 2))
  return escaped.join("")
}

function escapeCmdArgument(value: string): string {
  // The first caret pass protects the value in the inner command; the second keeps those carets through cmd /s /c's
  // outer parse of the quoted command line.
  return `"${escapeWindowsQuoteSlashes(value)}"`.replace(CMD_META, "^$1").replace(CMD_META, "^$1")
}

/** The command line `cmd.exe /d /v:off /s /c` runs for [command] with [args], each passed on exactly as given. */
export function windowsCmdCommandLine(command: string, args: readonly string[]): string {
  return `"${[command.replace(CMD_META, "^$1"), ...args.map(escapeCmdArgument)].join(" ")}"`
}

/** How Windows starts a resolved command: what to spawn, and whether its line goes on verbatim (no runtime quoting). */
export interface WindowsLaunch { command: string; args: string[]; windowsVerbatimArguments?: boolean }

/**
 * How to start the resolved [command] with [args] on Windows, no argument ever read by a shell: a .cmd or .bat shim
 * through cmd.exe ([windowsCmdCommandLine], verbatim), a .ps1 through PowerShell's -File (pwsh, else Windows
 * PowerShell, found on [options]' PATH), anything else as it is. Throws when a .ps1 has no PowerShell to run it.
 */
export function windowsLaunch(command: string, args: readonly string[], options: { env?: CommandEnvironment; fileExists?: (path: string) => boolean } = {}): WindowsLaunch {
  const env = options.env ?? process.env
  const extension = win32.extname(command).toLowerCase()
  if (extension === ".cmd" || extension === ".bat") {
    return {
      command: envValue(env, "ComSpec", "win32") || "cmd.exe",
      args: ["/d", "/v:off", "/s", "/c", windowsCmdCommandLine(command, args)],
      windowsVerbatimArguments: true,
    }
  }
  if (extension === ".ps1") {
    const powerShell = resolve(["pwsh", "powershell"], env, { platform: "win32", ...(options.fileExists ? { fileExists: options.fileExists } : {}) })
    if (!powerShell) throw new Error("PowerShell was not found on PATH (tried pwsh.exe and powershell.exe)")
    return { command: powerShell, args: ["-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", command, ...args] }
  }
  return { command, args: [...args] }
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

/** node's spawn as [spawnCommand] takes it (a test passes its own). */
export type SpawnLike = (command: string, args: readonly string[], options: SpawnOptions) => ChildProcess
/** node's spawnSync as [spawnCommandSync] takes it. */
export type SpawnSyncLike = (command: string, args: readonly string[], options: SpawnSyncOptions) => SpawnSyncReturns<Buffer>

export interface SpawnCommandOptions extends SpawnOptions {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** Starts the process; node's spawn by default. */
  spawn?: SpawnLike
  fileExists?: (path: string) => boolean
}

export interface SpawnCommandSyncOptions extends SpawnSyncOptions {
  platform?: NodeJS.Platform
  spawnSync?: SpawnSyncLike
  fileExists?: (path: string) => boolean
}

// What starts [command] with [args]. On Windows a bare name is first found on [env]'s PATH ([resolve]): the runtime's own
// search knows no .cmd or .ps1 (an npm install of opencode is opencode.cmd) and looks in the current folder first. Then
// it is wrapped by its kind ([windowsLaunch]). A name found nowhere, and any command elsewhere, as given.
function launchPlan(command: string, args: readonly string[], platform: NodeJS.Platform, env: CommandEnvironment, fileExists?: (path: string) => boolean): WindowsLaunch {
  if (platform !== "win32") return { command, args: [...args] }
  const files = fileExists ? { fileExists } : {}
  const named = isExplicitPath(command, platform) ? command : resolve([command], env, { platform, ...files }) ?? command
  return windowsLaunch(named, args, { env, ...files })
}

/**
 * Starts [command] with node's spawn, never through a general shell. On Windows a bare name is first found on the env's
 * PATH, then a .cmd/.bat shim goes through cmd.exe with every argument escaped and a .ps1 through PowerShell's -File
 * ([windowsLaunch]); anything else starts as it is. Every start begins from [spawnDefaults] (the live env, no console
 * window), which the caller's own options override. The broker's launcher and core's keeper both start their programs
 * here.
 */
export function spawnCommand(command: string, args: readonly string[], options: SpawnCommandOptions = {}): ChildProcess {
  const { platform = process.platform, spawn = nodeSpawn as unknown as SpawnLike, fileExists, ...spawnOptions } = options
  const wrapped = launchPlan(command, args, platform, (spawnOptions.env ?? process.env) as CommandEnvironment, fileExists)
  return spawn(wrapped.command, wrapped.args, {
    ...spawnDefaults(),
    ...spawnOptions,
    ...(wrapped.windowsVerbatimArguments ? { windowsVerbatimArguments: true } : {}),
  })
}

/** The synchronous twin of [spawnCommand], with the same Windows wrappers and defaults. */
export function spawnCommandSync(command: string, args: readonly string[], options: SpawnCommandSyncOptions = {}): SpawnSyncReturns<Buffer> {
  const { platform = process.platform, spawnSync = nodeSpawnSync as unknown as SpawnSyncLike, fileExists, ...spawnOptions } = options
  const wrapped = launchPlan(command, args, platform, (spawnOptions.env ?? process.env) as CommandEnvironment, fileExists)
  return spawnSync(wrapped.command, wrapped.args, {
    ...spawnDefaults(),
    ...spawnOptions,
    ...(wrapped.windowsVerbatimArguments ? { windowsVerbatimArguments: true } : {}),
  })
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
