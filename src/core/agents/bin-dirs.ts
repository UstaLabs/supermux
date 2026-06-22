import { join, delimiter } from "path"

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
