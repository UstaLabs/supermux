import { homedir, userInfo } from "os"

/**
 * Portable home-directory resolver. Prefers an explicit, non-empty $HOME, and
 * falls back to os.homedir() so paths never resolve to "/.codex" when HOME is
 * unset (e.g. under systemd or a bare container). Use this everywhere instead
 * of `process.env.HOME ?? ""`.
 */
export function home(): string {
  return process.env.HOME || homedir()
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
