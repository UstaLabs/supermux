import {
  spawn as nodeSpawn, spawnSync as nodeSpawnSync, type ChildProcess, type SpawnOptions,
  type SpawnSyncOptions, type SpawnSyncReturns,
} from "child_process"
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

/** The command line a .cmd/.bat shim runs with: @supermux/platform's one rule (BatBadBut-safe), shared with core's login. */
export const windowsCmdCommandLine = proc.windowsCmdCommandLine

/** Spawn an already-resolved command without ever passing user argv through a
 * general-purpose shell. Only Windows shim file formats receive fixed wrappers. */
export function spawnCommand(command: string, args: readonly string[], options: SpawnCommandOptions = {}): ChildProcess {
  const { platform = process.platform, spawn = nodeSpawn as unknown as SpawnLike, fileExists, ...spawnOptions } = options
  const env = (spawnOptions.env ?? process.env) as CommandEnvironment
  const wrapped: proc.WindowsLaunch = platform === "win32" ? proc.windowsLaunch(command, args, { env, ...(fileExists ? { fileExists } : {}) }) : { command, args: [...args] }
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
  const wrapped: proc.WindowsLaunch = platform === "win32" ? proc.windowsLaunch(command, args, { env, ...(fileExists ? { fileExists } : {}) }) : { command, args: [...args] }
  return spawnSync(wrapped.command, wrapped.args, {
    ...proc.spawnDefaults(),
    ...spawnOptions,
    ...(wrapped.windowsVerbatimArguments ? { windowsVerbatimArguments: true } : {}),
  })
}
