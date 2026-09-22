/** Device-login spawn descriptor. cursor-agent prints the login URL on
 * stdout; NO_OPEN_BROWSER stops it from opening one on the broker host. */
import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"
import { cursorCredentialFreshness as libraryCursorCredentialFreshness } from "../../../../packages/supermux-core/src/environment/index.js"

export function cursorCredentialFreshness(path: string): number {
  return libraryCursorCredentialFreshness(path)
}

export function loginSpawnCommand(): LoginSpawnCommand {
  const env = { ...process.env } as Record<string, string>
  const cmd = resolveCommand(["cursor-agent", "agent"], env, process.platform) ?? "cursor-agent"
  env.NO_OPEN_BROWSER = "1"
  return { cmd, args: ["login"], env }
}
