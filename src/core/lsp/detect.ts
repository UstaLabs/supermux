import { existsSync } from "node:fs"
import { findDartBin, findGoplsBin, findRustAnalyzerBin, resolveBinPath, type LspServerSpec } from "./catalog"

// Whether a binary is available on PATH. Bun.which returns the resolved path
// or null. Used to check install prerequisites (bun/go/rustup).
export function isInstalled(bin: string): boolean {
  try {
    return !!Bun.which(bin)
  } catch {
    return false
  }
}

// Whether a server itself is installed. Node servers live at a known path under
// bun's global bin dir (and aren't necessarily on PATH); native servers are
// resolved on PATH or under ~/.mux/lsp.
export function isServerInstalled(spec: LspServerSpec): boolean {
  if (spec.command) {
    if (spec.command.includes("/")) return existsSync(spec.command)
    return isInstalled(spec.command)
  }
  if (spec.runtime === "node") return existsSync(resolveBinPath(spec))
  if (spec.id === "dart") return findDartBin() !== null
  if (spec.id === "gopls") return findGoplsBin() !== null
  if (spec.id === "rust-analyzer") return findRustAnalyzerBin() !== null
  return isInstalled(spec.bin)
}

export function prereqMissing(requires: string | undefined): boolean {
  if (!requires) return false
  return !isInstalled(requires)
}
