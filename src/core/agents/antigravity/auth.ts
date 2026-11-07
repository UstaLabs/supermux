import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { delimiter, join } from "node:path"
import { resolveCommand } from "../../process/launcher"
import { ptyLoginSpawnCommand, type LoginSpawnCommand } from "../login/spawn-command"
import { antigravityUpdaterEnv } from "../../../../packages/supermux-core/src/antigravity/updater.js"

/** No-op stand-ins for the browser openers agy calls directly (xdg-open on Linux, open on macOS);
 * BROWSER/DISPLAY do not stop it. The user opens the URL on their own device, never the broker host. */
const BROWSER_OPENERS = ["xdg-open", "open"]

/** System login = the user's OWN agy login (as if they ran `agy` in a terminal): agy writes its token to
 * ~/.gemini/antigravity-cli. Print mode reads the pasted code from the controlling terminal, so it runs
 * under a pty (a ConPTY on Windows). Windows opens URLs through the shell, not a PATH command, so there
 * the no-op openers do not apply and agy may also open the host's browser.
 * agy prints an accounts.google.com OAuth URL, then waits ~60s for the pasted authorization code.
 * The login is a real one-word model turn, so it runs in a throwaway empty cwd; a private `.bin` of
 * no-op openers leads PATH. Both live in one temp dir that `cleanup` removes. */
export function loginSpawnCommand(platform = process.platform): LoginSpawnCommand | null {
  const env = { ...process.env } as Record<string, string>
  delete env.GEMINI_API_KEY
  delete env.DISPLAY
  delete env.WAYLAND_DISPLAY
  if (platform !== "win32") env.BROWSER = "true"
  Object.assign(env, antigravityUpdaterEnv(platform))
  const cmd = resolveCommand(["agy"], env, platform) ?? "agy"
  const root = mkdtempSync(join(tmpdir(), "supermux-agy-login-"))
  const cwd = join(root, "cwd")
  const bin = join(root, ".bin")
  try {
    mkdirSync(cwd, { mode: 0o700 })
    mkdirSync(bin, { mode: 0o700 })
    if (platform !== "win32") for (const name of BROWSER_OPENERS) writeFileSync(join(bin, name), "#!/bin/sh\nexit 0\n", { mode: 0o700 })
  } catch (err) {
    rmSync(root, { recursive: true, force: true })
    throw err
  }
  env.PATH = [bin, env.PATH].filter(Boolean).join(delimiter)
  return {
    ...ptyLoginSpawnCommand(cmd, ["-p=Reply with the single word OK."], "supermux-agy", platform),
    env,
    cwd,
    cleanup: () => rmSync(root, { recursive: true, force: true }),
  }
}
