import { resolveCommand, spawnCommand, type FileExists, type SpawnLike } from "../process/launcher"
import { lspPathEnv, muxLspBinDir } from "./paths"
import type { LspInstallSpec } from "./catalog"

export interface InstallHandle {
  cancel(): void
}

/** How an LSP install or server process starts: on this OS with node's spawn, unless a test says otherwise. */
export interface LspLaunch {
  platform?: NodeJS.Platform
  spawn?: SpawnLike
  fileExists?: FileExists
}

/**
 * [command] as the launcher should start it: found on [env]'s PATH when it is a bare name, so a Windows .cmd or .ps1
 * shim (npm, a user's own server) is known to be one and goes through cmd.exe or PowerShell. Unfound, it stays as
 * named and its spawn says so.
 */
export function lspCommand(command: string, env: NodeJS.ProcessEnv, launch: LspLaunch = {}): string {
  return resolveCommand([command], env, launch.platform, { fileExists: launch.fileExists }) ?? command
}

/** Headless broker installs: no apt/snap prompts, no TTY. */
export function installEnv(extra?: Record<string, string>): NodeJS.ProcessEnv {
  const muxBin = muxLspBinDir()
  return {
    ...process.env,
    PATH: lspPathEnv(),
    DEBIAN_FRONTEND: "noninteractive",
    APT_LISTCHANGES_FRONTEND: "none",
    NEEDRESTART_MODE: "a",
    CI: "true",
    GOBIN: muxBin,
    RUSTUP_IO_SYNTAX: "plain",
    ...extra,
  }
}

// Run a (catalog-defined) install command, streaming combined stdout+stderr
// lines to `onLine`, and calling `onDone(ok)` when it exits. The argv is
// always from the catalog — never built from client input.
export function runInstall(
  cmd: string[],
  onLine: (line: string) => void,
  onDone: (ok: boolean) => void,
  spec?: Pick<LspInstallSpec, "env">,
  launch: LspLaunch = {},
): InstallHandle {
  const [bin, ...args] = cmd
  if (!bin) {
    onDone(false)
    return { cancel: () => {} }
  }
  let settled = false
  const finish = (ok: boolean) => {
    if (settled) return
    settled = true
    onDone(ok)
  }
  const env = installEnv(spec?.env)
  const child = spawnCommand(lspCommand(bin, env, launch), args, { stdio: ["ignore", "pipe", "pipe"], env, ...launch })
  const pipe = (c: Buffer) => {
    for (const line of c.toString("utf8").split(/\r?\n/)) {
      if (line.trim()) onLine(line)
    }
  }
  child.stdout?.on("data", pipe)
  child.stderr?.on("data", pipe)
  child.on("exit", (code) => finish(code === 0))
  child.on("error", (err) => {
    onLine(`failed to launch ${bin}: ${err?.message ?? String(err)}`)
    finish(false)
  })
  return { cancel: () => { try { child.kill("SIGTERM") } catch {} } }
}
