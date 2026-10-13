import { existsSync, mkdirSync, readdirSync, readFileSync, readlinkSync, rmdirSync, rmSync, statSync, symlinkSync, unlinkSync } from "node:fs"
import { join, resolve, sep } from "node:path"
import { requireSpec } from "./spec.js"
import type { AntigravityEnvironmentSpec, McpServerSpec, PreparedEnvironment } from "./types.js"
import { lstatSafe, writeFileNoFollow } from "./write.js"

/**
 * agy finds its config only through $HOME and its shell tool inherits $HOME, so each session runs
 * in a "shadow HOME": every top-level entry of the real HOME is symlinked in, except `.gemini`,
 * which is a real dir whose `config/mcp_config.json` and `config/plugins/supermux-*` are generated.
 * INVARIANT: nothing here ever writes, renames or deletes under the user's real HOME.
 */
export const ANTIGRAVITY_RESERVED_PREFIX = "supermux-"

const DEFAULT_SHARED_HISTORY = ["conversations", "brain"]

function dirLinkType(): "junction" | "dir" {
  return process.platform === "win32" ? "junction" : "dir"
}

function readdirSafe(dir: string): string[] {
  try {
    return readdirSync(dir)
  } catch {
    return []
  }
}

function isDirSafe(path: string): boolean {
  try {
    return statSync(path).isDirectory()
  } catch {
    return false
  }
}

function linkOrSkip(target: string, path: string, type: "junction" | "dir" | "file"): void {
  try {
    symlinkSync(target, path, type)
  } catch (err) {
    if (process.platform === "win32" && (err as NodeJS.ErrnoException).code === "EPERM") return
    throw err
  }
}

/** A real (non-symlink) dir under the shadow home; a symlink in its place is removed (never followed). */
function ensureRealDir(path: string): void {
  if (lstatSafe(path)?.isSymbolicLink()) unlinkSync(path)
  mkdirSync(path, { recursive: true, mode: 0o700 })
}

/** Refuses when a shadow path component would lead out of the shadow home via a symlink. */
function assertNoLinkedParents(shadowHome: string, rel: string[]): void {
  let cur = shadowHome
  for (const part of rel) {
    cur = join(cur, part)
    if (lstatSafe(cur)?.isSymbolicLink()) throw new Error(`refusing to write through symlink ${cur}`)
  }
}

/**
 * Mirrors `sourceDir` into `dir` as symlinks. Only ever creates/removes symlinks in `dir`.
 * Non-link entries in `dir` (agent-created content) are left alone.
 */
function syncLinks(dir: string, sourceDir: string, skip: (name: string) => boolean, keep: (name: string) => boolean): void {
  for (const name of readdirSafe(sourceDir)) {
    if (skip(name)) continue
    const target = join(sourceDir, name)
    const path = join(dir, name)
    const current = lstatSafe(path)
    if (current?.isSymbolicLink()) {
      if (readlinkSync(path) === target) continue
      unlinkSync(path)
    } else if (current) {
      continue
    }
    linkOrSkip(target, path, isDirSafe(target) ? dirLinkType() : "file")
  }
  for (const name of readdirSafe(dir)) {
    if (skip(name) || keep(name)) continue
    const path = join(dir, name)
    if (!lstatSafe(path)?.isSymbolicLink()) continue
    const target = join(sourceDir, name)
    if (readlinkSync(path) !== target) continue
    if (lstatSafe(target)) continue
    unlinkSync(path)
  }
}

export async function prepareAntigravityEnvironment(spec: AntigravityEnvironmentSpec): Promise<PreparedEnvironment> {
  requireSpec(spec, ["home", "userHome", "platform"])
  const home = resolve(spec.home)
  const userHome = resolve(spec.userHome)
  const userGemini = join(userHome, ".gemini")
  if (home === userHome || home === userGemini || home.startsWith(userGemini + sep)) {
    throw new TypeError("antigravity shadow home must not be the user's home or inside ~/.gemini")
  }
  mkdirSync(home, { recursive: true, mode: 0o700 })

  syncLinks(home, userHome, (n) => n === ".gemini", () => false)

  const gemini = join(home, ".gemini")
  ensureRealDir(gemini)
  const userCli = join(userGemini, "antigravity-cli")
  linkAntigravityCli(home, userCli)
  syncLinks(gemini, userGemini, (n) => n === "antigravity-cli" || n === "config", () => false)

  const config = join(gemini, "config")
  const userConfig = join(userGemini, "config")
  ensureRealDir(config)
  syncLinks(config, userConfig, (n) => n === "mcp_config.json" || n === "plugins", () => false)

  const plugins = join(config, "plugins")
  ensureRealDir(plugins)
  const reserved = (n: string) => n.startsWith(ANTIGRAVITY_RESERVED_PREFIX)
  syncLinks(plugins, join(userConfig, "plugins"), reserved, reserved)

  const { path } = writeAntigravityMcpConfig({ shadowHome: home, userHome, servers: [] })
  return {
    env: { HOME: home, ...(spec.platform === "win32" ? { USERPROFILE: home } : {}) },
    files: [path],
    credentials: existsSync(join(userCli, "antigravity-oauth-token")) ? "canonical" : "none",
  }
}

/** Points <shadow>/.gemini/antigravity-cli at cliDir (replaces a different symlink; refuses a real dir). */
export function linkAntigravityCli(shadowHome: string, cliDir: string): void {
  const gemini = join(shadowHome, ".gemini")
  assertNoLinkedParents(shadowHome, [".gemini"])
  mkdirSync(gemini, { recursive: true, mode: 0o700 })
  const path = join(gemini, "antigravity-cli")
  const current = lstatSafe(path)
  if (current?.isSymbolicLink()) {
    if (readlinkSync(path) === cliDir) return
    unlinkSync(path)
  } else if (current) {
    throw new Error(`refusing to replace non-symlink ${path}`)
  }
  linkOrSkip(cliDir, path, dirLinkType())
}

/**
 * Account cli dirs share history: <accountCli>/<name> → <userCli>/<name> for names whose real dir
 * exists and whose account entry is missing or an empty dir. Never creates anything under userCli.
 */
export function shareAntigravityHistory(accountCliDir: string, userCliDir: string, names: string[] = DEFAULT_SHARED_HISTORY): void {
  for (const name of names) {
    const target = join(userCliDir, name)
    if (!isDirSafe(target)) continue
    const path = join(accountCliDir, name)
    const current = lstatSafe(path)
    if (current?.isSymbolicLink()) continue
    if (current) {
      if (!current.isDirectory() || readdirSafe(path).length > 0) continue
      rmdirSync(path)
    }
    linkOrSkip(target, path, dirLinkType())
  }
}

function readJsonObject(path: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(readFileSync(path, "utf8"))
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function nativeServer(s: McpServerSpec): { command: string; args: string[]; env: Record<string, string> } {
  return { command: s.command, args: s.args, env: s.env }
}

/**
 * Writes <shadow>/.gemini/config/mcp_config.json = the user's real config (other keys kept) plus
 * `servers` as supermux-<name>[-N]; a clash renames ours, never theirs. Returns native→context aliases.
 */
export function writeAntigravityMcpConfig(opts: { shadowHome: string; userHome: string; servers: McpServerSpec[] }): {
  path: string
  aliases: Record<string, string>
} {
  const base = readJsonObject(join(opts.userHome, ".gemini", "config", "mcp_config.json"))
  const rawUser = base.mcpServers
  const user = rawUser && typeof rawUser === "object" && !Array.isArray(rawUser) ? (rawUser as Record<string, unknown>) : {}
  const out: Record<string, unknown> = {}
  const aliases: Record<string, string> = {}
  for (const s of opts.servers) {
    let native = ANTIGRAVITY_RESERVED_PREFIX + s.name
    for (let n = 2; native in user || native in out; n++) native = `${ANTIGRAVITY_RESERVED_PREFIX}${s.name}-${n}`
    out[native] = nativeServer(s)
    aliases[native] = s.name
  }
  assertNoLinkedParents(opts.shadowHome, [".gemini", "config"])
  const dir = join(opts.shadowHome, ".gemini", "config")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const path = join(dir, "mcp_config.json")
  if (lstatSafe(path)?.isSymbolicLink()) unlinkSync(path)
  writeFileNoFollow(path, JSON.stringify({ ...base, mcpServers: { ...user, ...out } }, null, 2) + "\n", 0o600)
  return { path, aliases }
}

export type AntigravityPluginWrapper = {
  /** Must start with ANTIGRAVITY_RESERVED_PREFIX. */
  name: string
  skills?: string
  mcpServers: McpServerSpec[]
}

/**
 * Removes every <shadow>/.gemini/config/plugins/supermux-* entry (a symlinked entry only loses the
 * link), then writes the wrappers (plugin.json, skills symlink, mcp_config.json). Returns their paths.
 */
export function writeAntigravityPlugins(opts: { shadowHome: string; wrappers: AntigravityPluginWrapper[] }): string[] {
  for (const w of opts.wrappers) {
    if (!w.name.startsWith(ANTIGRAVITY_RESERVED_PREFIX) || w.name.includes("/") || w.name.includes("\\") || w.name.includes("..")) {
      throw new TypeError(`antigravity plugin wrapper name must start with "${ANTIGRAVITY_RESERVED_PREFIX}": ${w.name}`)
    }
  }
  assertNoLinkedParents(opts.shadowHome, [".gemini", "config", "plugins"])
  const dir = join(opts.shadowHome, ".gemini", "config", "plugins")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  for (const name of readdirSafe(dir)) {
    if (!name.startsWith(ANTIGRAVITY_RESERVED_PREFIX)) continue
    const path = join(dir, name)
    if (lstatSafe(path)?.isSymbolicLink()) unlinkSync(path)
    else rmSync(path, { recursive: true, force: true })
  }
  const paths: string[] = []
  for (const w of opts.wrappers) {
    const path = join(dir, w.name)
    mkdirSync(path, { recursive: true, mode: 0o700 })
    writeFileNoFollow(join(path, "plugin.json"), JSON.stringify({ name: w.name }, null, 2) + "\n", 0o600)
    if (w.skills) linkOrSkip(w.skills, join(path, "skills"), dirLinkType())
    if (w.mcpServers.length) {
      const mcpServers = Object.fromEntries(w.mcpServers.map((s) => [s.name, nativeServer(s)]))
      writeFileNoFollow(join(path, "mcp_config.json"), JSON.stringify({ mcpServers }, null, 2) + "\n", 0o600)
    }
    paths.push(path)
  }
  return paths
}
