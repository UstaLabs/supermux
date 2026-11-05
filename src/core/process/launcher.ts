import {
  spawn as nodeSpawn, spawnSync as nodeSpawnSync, type ChildProcess, type SpawnOptions,
  type SpawnSyncOptions, type SpawnSyncReturns,
} from "child_process"
import { win32 } from "path"
import { proc } from "@supermux/platform"

export type CommandEnvironment = proc.CommandEnvironment
export type FileExists = (path: string) => boolean

export interface ResolveCommandDeps {
  fileExists?: FileExists
}

/** proc.resolve (PATH read from [env] at the call), in the argument order this module's callers use. */
export function resolveCommand(
  names: readonly string[],
  env: CommandEnvironment,
  platform?: NodeJS.Platform,
  deps: ResolveCommandDeps = {},
): string | null {
  return proc.resolve(names, env, { platform, fileExists: deps.fileExists })
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
const CMD_META = /([()\][%!^"`<>&|;, *?])/g

function escapeCmdCommand(value: string): string {
  return value.replace(CMD_META, "^$1")
}

// Linear equivalent of the hardened cross-spawn lookahead forms. A small
// state machine makes the complexity obvious and preserves the established
// behavior: double every slash before a quote or the closing wrapper quote,
// then add the slash that escapes an embedded quote.
function escapeWindowsQuoteSlashes(value: string): string {
  const escaped: string[] = []
  let slashes = 0
  for (const char of value) {
    if (char === "\\") {
      slashes++
      continue
    }
    if (char === '"') {
      escaped.push("\\".repeat(slashes * 2 + 1), char)
    } else {
      escaped.push("\\".repeat(slashes), char)
    }
    slashes = 0
  }
  escaped.push("\\".repeat(slashes * 2))
  return escaped.join("")
}

function escapeCmdArgument(value: string): string {
  let escaped = escapeWindowsQuoteSlashes(value)
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
      command: proc.envValue(env, "ComSpec", "win32") || "cmd.exe",
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
    ...proc.spawnDefaults(),
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
    ...proc.spawnDefaults(),
    ...spawnOptions,
    ...(wrapped.windowsVerbatimArguments ? { windowsVerbatimArguments: true } : {}),
  })
}
