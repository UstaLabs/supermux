import {
  spawn as nodeSpawn, spawnSync as nodeSpawnSync, type ChildProcess, type SpawnOptions,
  type SpawnSyncOptions, type SpawnSyncReturns,
} from "child_process"
import { accessSync, constants, statSync } from "fs"
import { posix, win32 } from "path"

export type CommandEnvironment = Record<string, string | undefined>
export type FileExists = (path: string) => boolean

export interface ResolveCommandDeps {
  fileExists?: FileExists
}

function envValue(env: CommandEnvironment, name: string, platform: NodeJS.Platform): string | undefined {
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

function defaultFileProbe(path: string, platform: NodeJS.Platform): boolean {
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

/** Resolve against the supplied environment at call time. No PATH snapshot is
 * retained, so a CLI installed after broker startup becomes immediately visible. */
export function resolveCommand(
  names: readonly string[],
  env: CommandEnvironment,
  platform: NodeJS.Platform = process.platform,
  deps: ResolveCommandDeps = {},
): string | null {
  const fileExists = deps.fileExists ?? ((path: string) => defaultFileProbe(path, platform))
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

export type SpawnLike = (command: string, args: readonly string[], options: SpawnOptions) => ChildProcess

export interface SpawnCommandOptions extends SpawnOptions {
  platform?: NodeJS.Platform
  spawn?: SpawnLike
  fileExists?: FileExists
}

export type SpawnSyncLike = (
  command: string, args: readonly string[], options: SpawnSyncOptions,
) => SpawnSyncReturns<Buffer>

export interface SpawnCommandSyncOptions extends SpawnSyncOptions {
  platform?: NodeJS.Platform
  spawnSync?: SpawnSyncLike
  fileExists?: FileExists
}

// This is the quoting strategy used by mature Windows Node launchers. Quotes
// and trailing slashes are escaped for CommandLineToArgvW, then cmd.exe
// metacharacters are caret-escaped. Delayed expansion is explicitly disabled.
const CMD_META = /([()%!^"<>&|;, *?])/g

function escapeCmdCommand(value: string): string {
  return value.replace(CMD_META, "^$1")
}

function escapeCmdArgument(value: string): string {
  let escaped = value.replace(/(\\*)"/g, "$1$1\\\"")
  escaped = escaped.replace(/(\\*)$/, "$1$1")
  escaped = `"${escaped}"`
  // The first pass protects the value in the inner command; the second keeps
  // those carets intact through cmd /s /c's outer quoted-command parse.
  escaped = escaped.replace(CMD_META, "^$1").replace(CMD_META, "^$1")
  return escaped
}

export function windowsCmdCommandLine(command: string, args: readonly string[]): string {
  const inner = [escapeCmdCommand(command), ...args.map(escapeCmdArgument)].join(" ")
  return `"${inner}"`
}

function wrappedWindowsCommand(
  command: string,
  args: readonly string[],
  env: CommandEnvironment,
  fileExists?: FileExists,
): { command: string; args: string[]; windowsVerbatimArguments?: boolean } {
  const extension = win32.extname(command).toLowerCase()
  if (extension === ".cmd" || extension === ".bat") {
    return {
      command: envValue(env, "ComSpec", "win32") || "cmd.exe",
      args: ["/d", "/v:off", "/s", "/c", windowsCmdCommandLine(command, args)],
      windowsVerbatimArguments: true,
    }
  }
  if (extension === ".ps1") {
    const powerShell = resolveCommand(["pwsh", "powershell"], env, "win32", { fileExists })
    if (!powerShell) throw new Error("PowerShell was not found on PATH (tried pwsh.exe and powershell.exe)")
    return {
      command: powerShell,
      args: ["-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", command, ...args],
    }
  }
  return { command, args: [...args] }
}

/** Spawn an already-resolved command without ever passing user argv through a
 * general-purpose shell. Only Windows shim file formats receive fixed wrappers. */
export function spawnCommand(command: string, args: readonly string[], options: SpawnCommandOptions = {}): ChildProcess {
  const { platform = process.platform, spawn = nodeSpawn as unknown as SpawnLike, fileExists, ...spawnOptions } = options
  const env = (spawnOptions.env ?? process.env) as CommandEnvironment
  const wrapped = platform === "win32" ? wrappedWindowsCommand(command, args, env, fileExists) : { command, args: [...args] }
  return spawn(wrapped.command, wrapped.args, {
    ...spawnOptions,
    ...(wrapped.windowsVerbatimArguments ? { windowsVerbatimArguments: true } : {}),
  })
}

/** Synchronous twin used by boot/plugin lifecycle paths that historically used
 * execFileSync. It shares the exact Windows wrappers and never enables a shell. */
export function spawnCommandSync(
  command: string, args: readonly string[], options: SpawnCommandSyncOptions = {},
): SpawnSyncReturns<Buffer> {
  const { platform = process.platform, spawnSync = nodeSpawnSync as unknown as SpawnSyncLike, fileExists, ...spawnOptions } = options
  const env = (spawnOptions.env ?? process.env) as CommandEnvironment
  const wrapped = platform === "win32" ? wrappedWindowsCommand(command, args, env, fileExists) : { command, args: [...args] }
  return spawnSync(wrapped.command, wrapped.args, {
    ...spawnOptions,
    ...(wrapped.windowsVerbatimArguments ? { windowsVerbatimArguments: true } : {}),
  })
}
