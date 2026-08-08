import { claudeLoginSpawnCommand, type LoginSpawnCommand } from "../login/spawn-command"

/** Device/browser-login spawn descriptor. The PTY wrapper itself lives in
 * login/spawn-command.ts (platform-split `script` mechanics predate this
 * module); this leaf exposes it on claude's auth surface like every other
 * kind's auth.ts does. */
export function loginSpawnCommand(): LoginSpawnCommand {
  return claudeLoginSpawnCommand()
}
