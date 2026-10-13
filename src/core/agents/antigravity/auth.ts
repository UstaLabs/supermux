import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { resolveCommand } from "../../process/launcher"
import { ptyLoginSpawnCommand, shQuote, type LoginSpawnCommand } from "../login/spawn-command"

/** System login = the user's OWN agy login (as if they ran `agy` in a terminal): agy writes its token to
 * ~/.gemini/antigravity-cli. Print mode reads the pasted code from the controlling terminal, so it runs
 * under a pty. No pty on Windows → null (API key or Accounts → Sign in with Google there).
 * agy prints an accounts.google.com OAuth URL, then waits ~60s for the pasted authorization code.
 * The login is a real one-word model turn, so it runs in a throwaway empty cwd (removed by `cleanup`),
 * and the host browser is suppressed: the user opens the URL on their own device. */
export function loginSpawnCommand(platform = process.platform): LoginSpawnCommand | null {
  if (platform === "win32") return null
  const env = { ...process.env } as Record<string, string>
  delete env.GEMINI_API_KEY
  delete env.DISPLAY
  delete env.WAYLAND_DISPLAY
  env.BROWSER = "true"
  const cmd = resolveCommand(["agy"], env, platform) ?? "agy"
  const cwd = mkdtempSync(join(tmpdir(), "supermux-agy-login-"))
  return {
    ...ptyLoginSpawnCommand(`stty cols 600; exec ${shQuote(cmd)} '-p=Reply with the single word OK.'`, "supermux-agy", platform),
    env,
    cwd,
    cleanup: () => rmSync(cwd, { recursive: true, force: true }),
  }
}
