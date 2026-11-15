import { join } from "path"
import { host } from "@supermux/platform"
import { SOCKETS_DIR, STATE_DIR } from "../../shared/paths"
import { withDirFirst } from "./bin-dirs"

type Env = Record<string, string | undefined>

/** Where the broker writes the `supermux` launcher (src/cli/launcher.ts), next to `mux-credential`. */
export const SUPERMUX_BIN_DIR = join(STATE_DIR, "bin")

/**
 * What an agent's own process needs to run the `supermux` CLI (#156): the session's identity, which the CLI turns into
 * its socket (the same one the mux-shim uses), and the launcher's folder first on PATH. Every agent gets it; before,
 * only Claude's process had the identity (for its global ~/.claude.json shim) and the others had it only in the MCP
 * server's env. `base` is the env the agent would get (its PATH is kept, after the bin dir).
 */
export function sessionCliEnv(base: Env, kind: string, sessionId: string, sessionName: string, opts: { platform?: NodeJS.Platform; binDir?: string; socketsDir?: string } = {}): Record<string, string> {
  const platform = opts.platform ?? host.os()
  const pathKey = Object.keys(base).find((k) => k.toLowerCase() === "path") ?? (platform === "win32" ? "Path" : "PATH")
  const path = base[pathKey] ?? process.env[pathKey] ?? process.env.PATH
  return {
    MUX_SESSION_ID: sessionId,
    MUX_DISPLAY_NAME: sessionName,
    MUX_AGENT_KIND: kind,
    MUX_SOCKETS_DIR: opts.socketsDir ?? SOCKETS_DIR,
    [pathKey]: withDirFirst(path, opts.binDir ?? SUPERMUX_BIN_DIR, platform),
  }
}
