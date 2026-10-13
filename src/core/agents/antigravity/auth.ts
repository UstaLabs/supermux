import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { delimiter, join } from "node:path"
import { resolveCommand } from "../../process/launcher"
import { ptyLoginSpawnCommand, shQuote, type LoginSpawnCommand } from "../login/spawn-command"

/** No-op stand-ins for the browser openers agy calls directly (xdg-open on Linux, open on macOS);
 * BROWSER/DISPLAY do not stop it. The user opens the URL on their own device, never the broker host. */
const BROWSER_OPENERS = ["xdg-open", "open"]

/** System login = the user's OWN agy login (as if they ran `agy` in a terminal): agy writes its token to
 * ~/.gemini/antigravity-cli. Print mode reads the pasted code from the controlling terminal, so it runs
 * under a pty. No pty on Windows → null (API key or Accounts → Sign in with Google there).
 * agy prints an accounts.google.com OAuth URL, then waits ~60s for the pasted authorization code.
 * The login is a real one-word model turn, so it runs in a throwaway empty cwd; a private `.bin` of
 * no-op openers leads PATH. Both live in one temp dir that `cleanup` removes. */
export function loginSpawnCommand(platform = process.platform): LoginSpawnCommand | null {
  if (platform === "win32") return null
  const env = { ...process.env } as Record<string, string>
  delete env.GEMINI_API_KEY
  delete env.DISPLAY
  delete env.WAYLAND_DISPLAY
  env.BROWSER = "true"
  const cmd = resolveCommand(["agy"], env, platform) ?? "agy"
  const root = mkdtempSync(join(tmpdir(), "supermux-agy-login-"))
  const cwd = join(root, "cwd")
  const bin = join(root, ".bin")
  try {
    mkdirSync(cwd, { mode: 0o700 })
    mkdirSync(bin, { mode: 0o700 })
    for (const name of BROWSER_OPENERS) writeFileSync(join(bin, name), "#!/bin/sh\nexit 0\n", { mode: 0o700 })
  } catch (err) {
    rmSync(root, { recursive: true, force: true })
    throw err
  }
  env.PATH = [bin, env.PATH].filter(Boolean).join(delimiter)
  return {
    ...ptyLoginSpawnCommand(`stty cols 600; exec ${shQuote(cmd)} '-p=Reply with the single word OK.'`, "supermux-agy", platform),
    env,
    cwd,
    cleanup: () => rmSync(root, { recursive: true, force: true }),
  }
}
