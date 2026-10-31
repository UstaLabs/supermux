import { win32 } from "path"
import { resolveCommand, type CommandEnvironment, type ResolveCommandDeps } from "../../process/launcher"

/**
 * The program a Claude keeper runs. The keeper spawns it without a shell, which on Windows starts only
 * an .exe: an npm install puts claude.cmd / claude.ps1 shims on PATH, so the claude.exe the package
 * ships behind them is what runs. Elsewhere the bare name resolves (an npm link is executable there).
 */
export function claudeCommand(
  env: CommandEnvironment,
  platform: NodeJS.Platform = process.platform,
  deps: ResolveCommandDeps = {},
): string {
  if (platform !== "win32") return "claude"
  const found = resolveCommand(["claude"], env, "win32", deps)
  // Not installed: the spawn fails and says so.
  if (!found) return "claude"
  if (win32.extname(found).toLowerCase() === ".exe") return found
  const packaged = win32.join(win32.dirname(found), "node_modules", "@anthropic-ai", "claude-code", "bin", "claude.exe")
  const exists = deps.fileExists ?? ((path: string) => resolveCommand([path], {}, "win32") !== null)
  if (exists(packaged)) return packaged
  throw new Error(`Claude Code was found only as ${found}, a script supermux cannot start. Install the native Claude Code (irm https://claude.ai/install.ps1 | iex) and restart supermux.`)
}
