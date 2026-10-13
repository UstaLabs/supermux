import { resolveCommand } from "../../process/launcher"
import { ptyLoginSpawnCommand, shQuote, type LoginSpawnCommand } from "../login/spawn-command"

/** System login = the user's OWN agy login (as if they ran `agy` in a terminal): agy writes its token to
 * ~/.gemini/antigravity-cli. Print mode reads the pasted code from the controlling terminal, so it runs
 * under a pty. No pty on Windows → null (API key or Accounts → Sign in with Google there).
 * agy prints an accounts.google.com OAuth URL, then waits ~60s for the pasted authorization code. */
export function loginSpawnCommand(platform = process.platform): LoginSpawnCommand | null {
  if (platform === "win32") return null
  const env = { ...process.env } as Record<string, string>
  delete env.GEMINI_API_KEY
  const cmd = resolveCommand(["agy"], env, platform) ?? "agy"
  return { ...ptyLoginSpawnCommand(`stty cols 600; exec ${shQuote(cmd)} '-p=Reply with the single word OK.'`, "supermux-agy", platform), env }
}
