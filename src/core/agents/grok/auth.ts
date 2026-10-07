import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"

/** Device-login spawn descriptor. grok prints the device URL + code on plain
 * stdout — no PTY needed, same as codex. */
export function loginSpawnCommand(): LoginSpawnCommand {
  const env = { ...process.env } as Record<string, string>
  const cmd = resolveCommand(["grok"], env, process.platform) ?? "grok"
  return { cmd, args: ["login", "--device-auth"], env }
}
