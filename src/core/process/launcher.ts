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

export type SpawnLike = proc.SpawnLike
export type SpawnCommandOptions = proc.SpawnCommandOptions
export type SpawnSyncLike = proc.SpawnSyncLike
export type SpawnCommandSyncOptions = proc.SpawnCommandSyncOptions

/** The command line a .cmd/.bat shim runs with: @supermux/platform's one rule (BatBadBut-safe), shared with core's login. */
export const windowsCmdCommandLine = proc.windowsCmdCommandLine

/** proc.spawnCommand: an already-resolved command, never through a general shell (Windows .cmd/.ps1 shims wrapped). */
export const spawnCommand = proc.spawnCommand

/**
 * Bun.spawn with this layer's defaults first (proc.spawnDefaults: the environment as it is now, where Bun's own default
 * is the one this process started with, and no console window on Windows). Bun's options pass through and win,
 * `terminal` included. [argv] runs as given: a command that may be a Windows .cmd/.ps1 shim goes through spawnCommand.
 */
export function spawnProcess<
  const In extends Bun.SpawnOptions.Writable = "ignore",
  const Out extends Bun.SpawnOptions.Readable = "pipe",
  const Err extends Bun.SpawnOptions.Readable = "inherit",
>(argv: string[], options?: Bun.SpawnOptions.SpawnOptions<In, Out, Err>): Bun.Subprocess<In, Out, Err> {
  return Bun.spawn(argv, { ...proc.spawnDefaults(), ...options })
}

/** Bun.spawnSync with the same defaults as [spawnProcess]. */
export function spawnProcessSync<
  const In extends Bun.SpawnOptions.Writable = "ignore",
  const Out extends Bun.SpawnOptions.Readable = "pipe",
  const Err extends Bun.SpawnOptions.Readable = "pipe",
>(argv: string[], options?: Bun.SpawnOptions.SpawnSyncOptions<In, Out, Err>): Bun.SyncSubprocess<Out, Err> {
  return Bun.spawnSync(argv, { ...proc.spawnDefaults(), ...options })
}

/** proc.spawnCommandSync: the synchronous twin, with the same Windows wrappers and never a shell. */
export const spawnCommandSync = proc.spawnCommandSync
