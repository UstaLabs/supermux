import { writeFileSync, mkdirSync, chmodSync } from "fs"
import { join } from "path"

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
