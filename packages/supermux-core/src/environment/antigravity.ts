import { existsSync, mkdirSync, readdirSync, readFileSync, readlinkSync, realpathSync, rmdirSync, rmSync, statSync, symlinkSync, unlinkSync } from "node:fs"
import { dirname, join, resolve, sep } from "node:path"
import { requireSpec } from "./spec.js"
import type { AntigravityEnvironmentSpec, McpServerSpec, PreparedEnvironment } from "./types.js"
import { lstatSafe, writeFileNoFollow } from "./write.js"

/**
 * agy finds its config only through $HOME and its shell tool inherits $HOME, so each session runs
 * in a "shadow HOME": every top-level entry of the real HOME is symlinked in, except `.gemini`,
 * which is a real dir whose `config/mcp_config.json` and `config/plugins/supermux-*` are generated.
 * INVARIANT: nothing here ever writes, renames, chmods, mkdirs or deletes under the user's real HOME.
 * Every entry point verifies (realpath-based) that its target is a shadow home / account dir that is
 * neither the real HOME, an ancestor of it, nor inside the real ~/.gemini; every shadow path
 * component that is written through is checked to be a non-symlink first.
 *
 * Residual TOCTOU: the checks and the writes are separate syscalls, so an agent running inside the
 * shadow that swaps a checked dir for a symlink in that window could still redirect one write. The
 * shadow is only (re)built while its session's agent is not running, which keeps that window closed
 * in practice; a fully race-free version would need openat/O_NOFOLLOW-style dirfd walking.
 */
export const ANTIGRAVITY_RESERVED_PREFIX = "supermux-"

const DEFAULT_SHARED_HISTORY = ["conversations", "brain"]

function dirLinkType(platform: NodeJS.Platform): "junction" | "dir" {
  return platform === "win32" ? "junction" : "dir"
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

/** realpath when the path exists, else the realpath of its nearest existing ancestor + the rest. */
function realpathLoose(path: string): string {
  const abs = resolve(path)
  try {
    return realpathSync(abs)
  } catch {
    const parent = dirname(abs)
    if (parent === abs) return abs
    return join(realpathLoose(parent), abs.slice(parent.length).replace(/^[\\/]+/, ""))
  }
}

function norm(path: string, platform: NodeJS.Platform): string {
  return platform === "win32" ? path.toLowerCase() : path
}

/** `child` equals `parent` or is inside it. */
function within(child: string, parent: string, platform: NodeJS.Platform): boolean {
  const c = norm(child, platform)
  const p = norm(parent, platform)
  return c === p || c.startsWith(p.endsWith(sep) ? p : p + sep)
}

/** The real ~/.gemini locations (literal and resolved — it may itself be a dotfiles symlink). */
function realGeminiDirs(userHome: string): string[] {
  const literal = join(realpathLoose(userHome), ".gemini")
  return [...new Set([literal, realpathLoose(literal)])]
}

/**
 * Throws unless `dir` (which must exist and not be a symlink) resolves to a location that is not the
 * real HOME, not an ancestor of it and not inside the real ~/.gemini.
 */
function refuseRealLocation(real: string, dir: string, userHome: string, platform: NodeJS.Platform): void {
  if (within(realpathLoose(userHome), real, platform) || realGeminiDirs(userHome).some((g) => within(real, g, platform))) {
    throw new TypeError(`antigravity shadow home must not be the user's home, an ancestor of it, or inside ~/.gemini: ${dir}`)
  }
}

/** Guard shared by every writer: `shadowHome` is an existing, non-symlink, safe shadow home. */
export function assertShadowHome(shadowHome: string, userHome: string, platform: NodeJS.Platform = process.platform): void {
  const st = lstatSafe(shadowHome)
  if (!st) throw new Error(`antigravity shadow home ${shadowHome} does not exist`)
  if (st.isSymbolicLink()) throw new TypeError(`antigravity shadow home must not be a symlink: ${shadowHome}`)
  refuseRealLocation(realpathSync(shadowHome), shadowHome, userHome, platform)
}

function linkOrSkip(target: string, path: string, type: "junction" | "dir" | "file", platform: NodeJS.Platform): void {
  try {
    symlinkSync(target, path, type)
  } catch (err) {
    if (platform === "win32" && (err as NodeJS.ErrnoException).code === "EPERM") return
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
function syncLinks(
  dir: string,
  sourceDir: string,
  skip: (name: string) => boolean,
  keep: (name: string) => boolean,
  platform: NodeJS.Platform,
): void {
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
    linkOrSkip(target, path, isDirSafe(target) ? dirLinkType(platform) : "file", platform)
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
  const platform = spec.platform
  const home = resolve(spec.home)
  const userHome = resolve(spec.userHome)
  // Pre-check before mkdir so a bad target is never created (mkdir could land in the real ~/.gemini).
  refuseRealLocation(realpathLoose(home), home, userHome, platform)
  if (lstatSafe(home)?.isSymbolicLink()) throw new TypeError(`antigravity shadow home must not be a symlink: ${home}`)
  mkdirSync(home, { recursive: true, mode: 0o700 })
  assertShadowHome(home, userHome, platform)

  syncLinks(home, userHome, (n) => n === ".gemini", () => false, platform)

  const gemini = join(home, ".gemini")
  ensureRealDir(gemini)
  const userGemini = join(userHome, ".gemini")
  const userCli = join(userGemini, "antigravity-cli")
  linkAntigravityCli(home, userCli, userHome, platform)
  syncLinks(gemini, userGemini, (n) => n === "antigravity-cli" || n === "config", () => false, platform)

  const config = join(gemini, "config")
  const userConfig = join(userGemini, "config")
  ensureRealDir(config)
  syncLinks(config, userConfig, (n) => n === "mcp_config.json" || n === "plugins", () => false, platform)

  const plugins = join(config, "plugins")
  ensureRealDir(plugins)
  const reserved = (n: string) => n.startsWith(ANTIGRAVITY_RESERVED_PREFIX)
  syncLinks(plugins, join(userConfig, "plugins"), reserved, reserved, platform)

  const { path } = writeAntigravityMcpConfig({ shadowHome: home, userHome, servers: [] })
  return {
    env: { HOME: home, ...(platform === "win32" ? { USERPROFILE: home } : {}) },
    files: [path],
    credentials: existsSync(join(userCli, "antigravity-oauth-token")) ? "canonical" : "none",
  }
}

/** Points <shadow>/.gemini/antigravity-cli at cliDir (replaces a different symlink; refuses a real dir). */
export function linkAntigravityCli(
  shadowHome: string,
  cliDir: string,
  userHome: string,
  platform: NodeJS.Platform = process.platform,
): void {
  assertShadowHome(shadowHome, userHome, platform)
  assertNoLinkedParents(shadowHome, [".gemini"])
  const gemini = join(shadowHome, ".gemini")
  mkdirSync(gemini, { recursive: true, mode: 0o700 })
  const path = join(gemini, "antigravity-cli")
  const current = lstatSafe(path)
  if (current?.isSymbolicLink()) {
    if (readlinkSync(path) === cliDir) return
    unlinkSync(path)
  } else if (current) {
    throw new Error(`refusing to replace non-symlink ${path}`)
  }
  linkOrSkip(cliDir, path, dirLinkType(platform), platform)
}

/**
 * Account cli dirs share history: <accountCli>/<name> → <userCli>/<name> for names whose real dir
 * exists and whose account entry is missing or an empty dir. Never creates anything under userCli.
 * `accountCliDir` must be a real (non-symlink) dir outside the real ~/.gemini (the parent of userCliDir).
 */
export function shareAntigravityHistory(
  accountCliDir: string,
  userCliDir: string,
  names: string[] = DEFAULT_SHARED_HISTORY,
  platform: NodeJS.Platform = process.platform,
): void {
  const st = lstatSafe(accountCliDir)
  if (!st) throw new Error(`antigravity account cli dir ${accountCliDir} does not exist`)
  if (st.isSymbolicLink()) throw new TypeError(`antigravity account cli dir must not be a symlink: ${accountCliDir}`)
  const realAccount = realpathSync(accountCliDir)
  const userGemini = dirname(resolve(userCliDir))
  const forbidden = [realpathLoose(userCliDir), userGemini, realpathLoose(userGemini)]
  if (forbidden.some((f) => within(realAccount, f, platform))) {
    throw new TypeError(`antigravity account cli dir must not be inside the real ~/.gemini: ${accountCliDir}`)
  }
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
    linkOrSkip(target, path, dirLinkType(platform), platform)
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
  assertShadowHome(opts.shadowHome, opts.userHome)
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

const WRAPPER_NAME_CHARS = /^[A-Za-z0-9._-]+$/

function validateWrappers(wrappers: AntigravityPluginWrapper[]): void {
  const seen = new Set<string>()
  for (const w of wrappers) {
    if (!w.name.startsWith(ANTIGRAVITY_RESERVED_PREFIX)) {
      throw new TypeError(`antigravity plugin wrapper name must start with "${ANTIGRAVITY_RESERVED_PREFIX}": ${w.name}`)
    }
    if (!WRAPPER_NAME_CHARS.test(w.name) || w.name.includes("..")) {
      throw new TypeError(`antigravity plugin wrapper name may only contain [A-Za-z0-9._-] and no "..": ${w.name}`)
    }
    if (seen.has(w.name)) throw new TypeError(`duplicate antigravity plugin wrapper name: ${w.name}`)
    seen.add(w.name)
  }
}

/**
 * Removes every <shadow>/.gemini/config/plugins/supermux-* entry (a symlinked entry only loses the
 * link), then writes the wrappers (plugin.json, skills symlink, mcp_config.json). Returns their paths.
 */
export function writeAntigravityPlugins(opts: {
  shadowHome: string
  userHome: string
  wrappers: AntigravityPluginWrapper[]
}): string[] {
  validateWrappers(opts.wrappers)
  assertShadowHome(opts.shadowHome, opts.userHome)
  assertNoLinkedParents(opts.shadowHome, [".gemini", "config", "plugins"])
  const dir = join(opts.shadowHome, ".gemini", "config", "plugins")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  for (const name of readdirSafe(dir)) {
    if (!name.startsWith(ANTIGRAVITY_RESERVED_PREFIX)) continue
    const path = join(dir, name)
    // rmSync never follows symlinks inside the tree; a symlinked entry only loses the link.
    if (lstatSafe(path)?.isSymbolicLink()) unlinkSync(path)
    else rmSync(path, { recursive: true, force: true })
  }
  const paths: string[] = []
  for (const w of opts.wrappers) {
    const path = join(dir, w.name)
    mkdirSync(path, { recursive: true, mode: 0o700 })
    writeFileNoFollow(join(path, "plugin.json"), JSON.stringify({ name: w.name }, null, 2) + "\n", 0o600)
    if (w.skills) linkOrSkip(w.skills, join(path, "skills"), dirLinkType(process.platform), process.platform)
    if (w.mcpServers.length) {
      const mcpServers = Object.fromEntries(w.mcpServers.map((s) => [s.name, nativeServer(s)]))
      writeFileNoFollow(join(path, "mcp_config.json"), JSON.stringify({ mcpServers }, null, 2) + "\n", 0o600)
    }
    paths.push(path)
  }
  return paths
}
