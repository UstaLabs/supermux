import { join } from "node:path"
import { home } from "../../shared/home"

/** User-local LSP tooling (no sudo): SDKs and `go install` binaries. */
export function muxLspHome(): string {
  return process.env.MUX_LSP_DIR || join(home(), ".mux", "lsp")
}

export function muxLspBinDir(): string {
  return join(muxLspHome(), "bin")
}

export function bunGlobalBinDir(): string {
  const root = process.env.BUN_INSTALL || join(home(), ".bun")
  return join(root, "bin")
}

/** PATH for install + spawn: mux bin, bun global bin, then system PATH. */
export function lspPathEnv(): string {
  return [muxLspBinDir(), bunGlobalBinDir(), process.env.PATH].filter(Boolean).join(":")
}
