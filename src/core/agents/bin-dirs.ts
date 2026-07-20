import { join, delimiter } from "path"
import { readdirSync } from "fs"

// Directories the official agent installers drop their binaries into. The broker
// is a long-running process: when the user installs an agent via the settings
// button, the installer edits shell rc files (e.g. ~/.bashrc) the broker never
// sourced — so a freshly-installed CLI is invisible to both detection
// (`hasBinary`) and spawning unless these dirs are on the broker's PATH.
//
//   • opencode      → ~/.opencode/bin   (NOT ~/.local/bin — this is the one that bit us)
//   • claude, cursor → ~/.local/bin
//   • bun globals    → ~/.bun/bin
//   • npm global     → ~/.npm-global/bin (common user prefix; system prefixes are already on PATH)
//
// Prepending a not-yet-existing dir is harmless and intentional: PATH lookup is
// dynamic, so the dir resolves as soon as the installer populates it.
export function agentBinDirs(home: string): string[] {
  return [
    join(home, ".opencode", "bin"),
    join(home, ".local", "bin"),
    join(home, ".bun", "bin"),
    join(home, ".npm-global", "bin"),
  ]
}

/** `path` with any agent bin dirs not already present prepended (deduped, order preserved). */
export function withAgentBinDirs(path: string | undefined, home: string): string {
  const existing = (path ?? "").split(delimiter).filter(Boolean)
  const have = new Set(existing)
  const add = agentBinDirs(home).filter((d) => !have.has(d))
  return [...add, ...existing].join(delimiter)
}

// Common Node.js / npm binary locations. The broker spawns installers via
// `bash -lc`, but on macOS the user's PATH setup (nvm, volta, Homebrew) often
// lives in .zshrc — which bash never sources. Prepending these lets npm-based
// recipes (codex) find the binary without requiring the user to duplicate their
// shell config into .bash_profile.
export function nodeBinDirs(home: string): string[] {
  const dirs = [
    "/opt/homebrew/bin",
    "/usr/local/bin",
    join(home, ".volta", "bin"),
    join(home, ".fnm"),
    join(home, ".local", "share", "fnm"),
  ]
  const nvm = resolveNvmBin(home)
  if (nvm) dirs.unshift(nvm)
  return dirs
}

function resolveNvmBin(home: string): string | null {
  const versionsDir = join(home, ".nvm", "versions", "node")
  try {
    const versions = readdirSync(versionsDir).filter((d) => d.startsWith("v"))
    if (versions.length === 0) return null
    versions.sort((a, b) => {
      const pa = a.slice(1).split(".").map(Number)
      const pb = b.slice(1).split(".").map(Number)
      for (let i = 0; i < 3; i++) if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pb[i] ?? 0) - (pa[i] ?? 0)
      return 0
    })
    return join(versionsDir, versions[0]!, "bin")
  } catch {
    return null
  }
}

/** `path` with node bin dirs prepended (for installer subprocesses). */
export function withNodeBinDirs(path: string | undefined, home: string): string {
  const existing = (path ?? "").split(delimiter).filter(Boolean)
  const have = new Set(existing)
  const add = nodeBinDirs(home).filter((d) => !have.has(d))
  return [...add, ...existing].join(delimiter)
}
