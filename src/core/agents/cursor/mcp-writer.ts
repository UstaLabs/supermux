import { writeFileSync, mkdirSync, chmodSync } from "fs"
import { join } from "path"

export function writeCursorMcpConfig(opts: {
  sessionHome: string
  shimCommand: string
  shimArgs: string[]
  sessionName: string
  socketsDir: string
  sessionId?: string
}): void {
  const cursorDir = join(opts.sessionHome, ".cursor")
  mkdirSync(cursorDir, { recursive: true, mode: 0o700 })
  // mkdirSync(recursive:true) does NOT downgrade an existing dir's mode.
  // resolveCursorAuth's cpSync may have left .cursor at the source's mode
  // (potentially 0o755 from Cursor IDE), so re-assert 0o700 here since
  // mcp.json holds session identity env that shouldn't be locally readable.
  chmodSync(cursorDir, 0o700)
  const sessionId = opts.sessionId ?? opts.sessionName
  const json = {
    mcpServers: {
      "mux-shim": {
        command: opts.shimCommand,
        args: opts.shimArgs,
        env: {
          MUX_SESSION_ID: sessionId,
          MUX_DISPLAY_NAME: opts.sessionName,
          MUX_AGENT_KIND: "cursor",
          MUX_SOCKETS_DIR: opts.socketsDir,
        },
      },
    },
  }
  const path = join(cursorDir, "mcp.json")
  writeFileSync(path, JSON.stringify(json, null, 2), { encoding: "utf8", mode: 0o600 })
  chmodSync(path, 0o600)
}
