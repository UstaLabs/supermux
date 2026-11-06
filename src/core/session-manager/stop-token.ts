// The per-boot token of the broker's local stop request (POST /internal/stop, platform-layer spec Q8). On Windows
// every external stop is TerminateProcess, which skips the broker's graceful shutdown; the desktop app reads this
// file (its own user's, 0600) and asks the broker to stop instead. A new token every boot: an old one stops nothing.
import { randomBytes } from "node:crypto"
import { renameSync, writeFileSync } from "node:fs"
import { join } from "node:path"

export const STOP_TOKEN_FILE = "stop-token"

/** Writes a fresh token to [stateDir]/stop-token and returns it. Written aside and renamed in, so a reader never sees half. */
export function writeStopToken(stateDir: string): string {
  const token = randomBytes(24).toString("hex")
  const path = join(stateDir, STOP_TOKEN_FILE)
  const aside = `${path}.${process.pid}.tmp`
  writeFileSync(aside, token, { mode: 0o600 }) // platform-ok: POSIX mode; on Windows the state dir is the user's profile, private by its ACL. files.makePrivate (step 7) takes this over
  renameSync(aside, path)
  return token
}
