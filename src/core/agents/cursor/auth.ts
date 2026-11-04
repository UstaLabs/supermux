/** Device-login spawn descriptor. cursor-agent prints the login URL on
 * stdout; NO_OPEN_BROWSER stops it from opening one on the broker host. */
import { resolveCommand } from "../../process/launcher"
import type { LoginSpawnCommand } from "../login/spawn-command"
import { cursorCredentialFreshness as libraryCursorCredentialFreshness } from "../../../../packages/supermux-core/src/environment/index.js"

export function cursorCredentialFreshness(path: string): number {
  return libraryCursorCredentialFreshness(path)
}

/** What every surface says when Cursor no longer accepts the login (CoreError code `account_expired`). */
export const CURSOR_SIGN_IN_EXPIRED = "Cursor sign-in expired — sign in to Cursor again."

/**
 * cursor-agent's ACP answer for a login it no longer accepts (expired or revoked), on session/new and
 * session/load: JSON-RPC -32000, ACP's auth_required ("Authentication required … run 'agent login'").
 */
export function cursorRefusedLogin(error: unknown): boolean {
  if (typeof error !== "object" || error === null) return false
  const { code, message, data } = error as { code?: unknown; message?: unknown; data?: { message?: unknown } | null }
  return code === -32000 && [message, data?.message].some((words) => typeof words === "string" && /authenticat/i.test(words))
}

export function loginSpawnCommand(): LoginSpawnCommand {
  const env = { ...process.env } as Record<string, string>
  const cmd = resolveCommand(["cursor-agent"], env, process.platform) ?? "cursor-agent"
  env.NO_OPEN_BROWSER = "1"
  return { cmd, args: ["login"], env }
}
