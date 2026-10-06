import { join, delimiter, win32 } from "path"
import { readdirSync } from "fs"
import { expandWindowsVars, type RegistryScope } from "../git/requirement"

type Env = Record<string, string | undefined>

// Directories the official agent installers drop their binaries into. The broker
// is a long-running process: when the user installs an agent via the settings
// button, the installer edits shell rc files (e.g. ~/.bashrc) or the Windows
// user PATH in the registry, neither of which this process ever re-reads — so a
// freshly-installed CLI is invisible to both detection (`hasBinary`) and
// spawning unless these dirs are on the broker's PATH.
//
// macOS / Linux:
//   • claude, cursor, codex → ~/.local/bin   (cursor edits no rc file at all)
//   • opencode              → ~/.opencode/bin (NOT ~/.local/bin — this is the one that bit us)
//   • grok                  → ~/.grok/bin
//   • bun globals           → ~/.bun/bin
//   • npm global            → ~/.npm-global/bin (common user prefix; system prefixes are already on PATH)
// Windows:
//   • claude   → %USERPROFILE%\.local\bin
//   • codex    → %LOCALAPPDATA%\Programs\OpenAI\Codex\bin
//   • cursor   → %LOCALAPPDATA%\cursor-agent
//   • opencode → %LOCALAPPDATA%\Programs\opencode   (our builtin installer)
//   • grok     → %USERPROFILE%\.grok\bin             (our builtin installer)
//   • npm / bun globals → %APPDATA%\npm, %USERPROFILE%\.bun\bin
//
// Prepending a not-yet-existing dir is harmless and intentional: PATH lookup is
// dynamic, so the dir resolves as soon as the installer populates it.
export function agentBinDirs(home: string, platform: NodeJS.Platform = process.platform, env: Env = process.env): string[] {
  if (platform === "win32") {
    const local = envValue(env, "LOCALAPPDATA") || win32.join(home, "AppData", "Local")
    const roaming = envValue(env, "APPDATA") || win32.join(home, "AppData", "Roaming")
    return [
      win32.join(home, ".local", "bin"),
      win32.join(local, "Programs", "OpenAI", "Codex", "bin"),
      win32.join(local, "cursor-agent"),
      win32.join(local, "Programs", "opencode"),
      win32.join(home, ".grok", "bin"),
      win32.join(home, ".bun", "bin"),
      win32.join(roaming, "npm"),
    ]
  }
  return [
    join(home, ".opencode", "bin"),
    join(home, ".local", "bin"),
    join(home, ".grok", "bin"),
    join(home, ".bun", "bin"),
    join(home, ".npm-global", "bin"),
  ]
}

/** Case-insensitive on Windows, as the OS is. */
function envValue(env: Env, name: string): string | undefined {
  const key = Object.keys(env).find((k) => k.toLowerCase() === name.toLowerCase())
  return key ? env[key] : undefined
}

function pathDelimiter(platform: NodeJS.Platform): string {
  return platform === "win32" ? ";" : platform === process.platform ? delimiter : ":"
}

/** How PATH entries compare: Windows ignores case and a trailing backslash. */
function pathKey(dir: string, platform: NodeJS.Platform): string {
  return platform === "win32" ? dir.trim().replace(/[\\/]+$/, "").toLowerCase() : dir
}

/** `path` with any agent bin dirs not already present prepended (deduped, order preserved). */
export function withAgentBinDirs(path: string | undefined, home: string, platform: NodeJS.Platform = process.platform, env: Env = process.env): string {
  return prependDirs(path, agentBinDirs(home, platform, env), platform)
}

function prependDirs(path: string | undefined, dirs: string[], platform: NodeJS.Platform): string {
  const sep = pathDelimiter(platform)
  const existing = (path ?? "").split(sep).filter(Boolean)
  const have = new Set(existing.map((d) => pathKey(d, platform)))
  const add: string[] = []
  for (const d of dirs) {
    const k = pathKey(d, platform)
    if (have.has(k)) continue
    have.add(k)
    add.push(d)
  }
  return [...add, ...existing].join(sep)
}

/**
 * Windows: append to [env]'s PATH every dir of the user and machine registry PATH it lacks (the
 * %VARS% expanded). An installer that sets the user PATH (codex, cursor, …) changes the
 * registry, never this running process; this picks its dir up without a restart. Returns the
 * dirs it added. No-op elsewhere.
 */
export async function refreshPathFromRegistry(
  env: Env,
  readRegistryPath: (scope: RegistryScope) => Promise<string | null>,
  platform: NodeJS.Platform = process.platform,
): Promise<string[]> {
  if (platform !== "win32") return []
  const [user, machine] = await Promise.all([
    readRegistryPath("user").catch(() => null),
    readRegistryPath("machine").catch(() => null),
  ])
  const key = Object.keys(env).find((k) => k.toLowerCase() === "path") ?? "PATH"
  const existing = (env[key] ?? "").split(";").filter(Boolean)
  const have = new Set(existing.map((d) => pathKey(d, "win32")))
  const added: string[] = []
  for (const raw of [user, machine].flatMap((p) => (p ?? "").split(";"))) {
    const dir = expandWindowsVars(raw.trim(), env)
    if (!dir || dir.includes("%")) continue
    const k = pathKey(dir, "win32")
    if (have.has(k)) continue
    have.add(k)
    added.push(dir)
  }
  if (added.length) env[key] = [...existing, ...added].join(";")
  return added
}

// Common Node.js / npm binary locations. The broker spawns installers via
// `bash -lc`, but on macOS the user's PATH setup (nvm, volta, Homebrew) often
// lives in .zshrc — which bash never sources. Prepending these lets npm-based
// recipes (codex) find the binary without requiring the user to duplicate their
// shell config into .bash_profile.
export function nodeBinDirs(home: string): string[] {
  const dirs = [
    "/opt/homebrew/bin",
    "/usr/local/bin",
    join(home, ".volta", "bin"),
    join(home, ".fnm"),
    join(home, ".local", "share", "fnm"),
  ]
  const nvm = resolveNvmBin(home)
  if (nvm) dirs.unshift(nvm)
  return dirs
}

function resolveNvmBin(home: string): string | null {
  const versionsDir = join(home, ".nvm", "versions", "node")
  try {
    const versions = readdirSync(versionsDir).filter((d) => d.startsWith("v"))
    if (versions.length === 0) return null
    versions.sort((a, b) => {
      const pa = a.slice(1).split(".").map(Number)
      const pb = b.slice(1).split(".").map(Number)
      for (let i = 0; i < 3; i++) if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pb[i] ?? 0) - (pa[i] ?? 0)
      return 0
    })
    return join(versionsDir, versions[0]!, "bin")
  } catch {
    return null
  }
}

/** `path` with node bin dirs prepended (for installer subprocesses). */
export function withNodeBinDirs(path: string | undefined, home: string, platform: NodeJS.Platform = process.platform): string {
  // Windows has none of these locations, and Windows installers never need node.
  if (platform === "win32") return path ?? ""
  return prependDirs(path, nodeBinDirs(home), platform)
}
