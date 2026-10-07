import { join } from "path"
import { homedir } from "os"
import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"
import { codexCredentialFreshness } from "../../../../packages/supermux-core/src/environment/index.js"

export { codexCredentialFreshness }

/** Device-login spawn descriptor. `codex login --device-auth` prints the
 * device URL + code on plain stdout — no PTY needed. */
export function loginSpawnCommand(): LoginSpawnCommand {
  const env = { ...process.env } as Record<string, string>
  const cmd = resolveCommand(["codex"], env, process.platform) ?? "codex"
  env.CODEX_HOME = join(homedir(), ".codex")
  return { cmd, args: ["login", "--device-auth"], env }
}
