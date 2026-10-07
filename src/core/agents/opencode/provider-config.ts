import { readFileSync } from "fs"
import { join } from "path"
import { home } from "../../../shared/home"

/** Strips // and /* *\/ comments so an `opencode.jsonc` parses as JSON. Naive
 * on purpose — it skips comment markers inside string literals, which is the
 * only case that matters for a hand-edited config. */
function stripJsonComments(raw: string): string {
  let out = ""
  let inString = false
  let inLine = false
  let inBlock = false
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i]!
    const next = raw[i + 1]
    if (inLine) {
      if (c === "\n") { inLine = false; out += c }
      continue
    }
    if (inBlock) {
      if (c === "*" && next === "/") { inBlock = false; i++ }
      continue
    }
    if (inString) {
      if (c === "\\") { out += c + (next ?? ""); i++; continue }
      if (c === '"') inString = false
      out += c
      continue
    }
    if (c === '"') { inString = true; out += c; continue }
    if (c === "/" && next === "/") { inLine = true; i++; continue }
    if (c === "/" && next === "*") { inBlock = true; i++; continue }
    out += c
  }
  return out
}

/** Reads the `provider` block from the user's REAL global opencode config.
 *
 * Sessions spawn with XDG_CONFIG_HOME redirected to a session-private dir, so
 * they never see ~/.config/opencode. That isolation is deliberate for MCP and
 * permissions, but it also hides custom provider/model declarations — and any
 * model the models.dev registry doesn't carry exists ONLY as such a
 * declaration. Without this bridge the launcher offers those models (it lists
 * from the global config) and the session then dies with
 * ProviderModelNotFoundError. Never throws: a missing or malformed global
 * config just yields no provider block. */
export function readGlobalProviderConfig(opts: {
  configDir?: string
  readFileFn?: (path: string) => string
} = {}): Record<string, unknown> | undefined {
  const dir = opts.configDir ?? join(home(), ".config", "opencode")
  const read = opts.readFileFn ?? ((p: string) => readFileSync(p, "utf8"))
  // opencode.json wins over .jsonc, matching opencode's own precedence.
  for (const file of ["opencode.json", "opencode.jsonc"]) {
    try {
      const parsed = JSON.parse(stripJsonComments(read(join(dir, file))))
      const provider = parsed?.provider
      if (provider && typeof provider === "object" && Object.keys(provider).length > 0) {
        return provider as Record<string, unknown>
      }
    } catch {
      // Unreadable or malformed — try the next candidate.
    }
  }
  return undefined
}
