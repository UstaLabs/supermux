import { writeFileSync, mkdirSync, chmodSync, readFileSync } from "fs"
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

/** Writes the per-session opencode config under a session-private
 * XDG_CONFIG_HOME (so it never touches the user's workdir). It registers the
 * mux-shim as a local MCP server — giving opencode the orchestration tools
 * (reply/spawn/rename/…) with this session's `MUX_SESSION_ID` baked in — and
 * points opencode at our identity/reply preamble via `instructions`.
 *
 * GUARDRAIL: `sessionId` MUST equal the registry row id and the socket filename
 * (`${socketsDir}/${sessionId}.sock`). If they diverge the shim's register
 * frame is not recognised and the broker spawns a phantom session. */
export function writeOpenCodeConfig(opts: {
  configHome: string
  shimCommand: string
  shimArgs: string[]
  sessionName: string
  socketsDir: string
  sessionId: string
  instructionsPath?: string
  pluginPaths?: string[]
  skillsPaths?: string[]
  /** Overrides the global `provider` block (tests inject; production reads disk). */
  providerConfig?: Record<string, unknown>
}): string {
  const dir = join(opts.configHome, "opencode")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const config: Record<string, unknown> = {
    $schema: "https://opencode.ai/config.json",
    mcp: {
      mux: {
        type: "local",
        command: [opts.shimCommand, ...opts.shimArgs],
        enabled: true,
        environment: {
          MUX_SESSION_ID: opts.sessionId,
          MUX_DISPLAY_NAME: opts.sessionName,
          MUX_AGENT_KIND: "opencode",
          MUX_SOCKETS_DIR: opts.socketsDir,
        },
      },
    },
  }
  const provider = opts.providerConfig ?? readGlobalProviderConfig()
  if (provider) config.provider = provider
  if (opts.instructionsPath) config.instructions = [opts.instructionsPath]
  if (opts.pluginPaths?.length) config.plugin = opts.pluginPaths
  if (opts.skillsPaths?.length) {
    config.skills = { paths: opts.skillsPaths }
  }
  const path = join(dir, "opencode.json")
  writeFileSync(path, JSON.stringify(config, null, 2) + "\n", { encoding: "utf8", mode: 0o600 })
  chmodSync(path, 0o600)
  return path
}
