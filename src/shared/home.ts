import { userInfo } from "os"
import { dirs } from "@supermux/platform"

/**
 * The user's home folder, by the runtime's rule (dirs.home, Q2): %USERPROFILE% on Windows, $HOME elsewhere. Read from
 * the environment at the call, so a test's or an override's HOME counts, and os.homedir() when it is unset (under
 * systemd or a bare container), so paths never resolve to "/.codex". Use this everywhere instead of `process.env.HOME`.
 */
export function home(): string {
  return dirs.home()
}

/**
 * Portable username resolver. Prefers explicit $USER/$LOGNAME, then falls back to
 * os.userInfo().username so it's never empty under a bare shell (e.g. some WSL or
 * systemd contexts where neither env var is set). Use this everywhere instead of
 * `process.env.USER` — printing an unexpanded `$USER` into a copy-paste command
 * (or spawning with an empty username) is exactly the rough edge to avoid.
 */
export function username(): string {
  const env = process.env.USER || process.env.LOGNAME
  if (env) return env
  try {
    return userInfo().username || ""
  } catch {
    return ""
  }
}
