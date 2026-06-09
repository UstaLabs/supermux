import { homedir } from "os"

/**
 * Portable home-directory resolver. Prefers an explicit, non-empty $HOME, and
 * falls back to os.homedir() so paths never resolve to "/.codex" when HOME is
 * unset (e.g. under systemd or a bare container). Use this everywhere instead
 * of `process.env.HOME ?? ""`.
 */
export function home(): string {
  return process.env.HOME || homedir()
}
