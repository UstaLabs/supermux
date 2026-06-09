import { spawn } from "node:child_process"
import { lspPathEnv, muxLspBinDir } from "./paths"
import type { LspInstallSpec } from "./catalog"

export interface InstallHandle {
  cancel(): void
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
  const child = spawn(bin, args, {
    stdio: ["ignore", "pipe", "pipe"],
    env: installEnv(spec?.env),
  })
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
