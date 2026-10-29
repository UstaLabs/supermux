import { chmodSync, mkdirSync, writeFileSync } from "node:fs"
import { join, posix, win32 } from "node:path"
import { ENVIRONMENT_FIELDS, requireSpec, validateMcpServerNames } from "./spec.js"
import type { OpenCodeEnvironmentSpec, PreparedEnvironment } from "./types.js"

function ensureHome(home: string): void {
  mkdirSync(home, { recursive: true, mode: 0o700 })
}

/**
 * The permission pattern for one `readableDirs` entry: `<folder>/*` (the pattern OpenCode's own
 * `external_directory` ask uses for a file in the folder). The same function validates and renders, so
 * what is checked is what is written. Trailing slashes are trimmed first; then the entry is refused when
 * - nothing is left (`/`, `//`, `///` would render `/*`, which allows everything);
 * - `resolve(folder) !== folder` (not absolute, or `..`, `.` or `//` inside);
 * - it holds `*` or `?`: wildcards in an OpenCode pattern that cannot be escaped, so the name would match more than the folder;
 * - it holds `\`: OpenCode reads it as `/`, so on POSIX, where it is an ordinary character, the pattern would
 *   name another folder. On Windows `\` is the separator and OpenCode reads it the same way: the pattern is written with `/`.
 * `platform` is a test seam.
 */
export function readableFolderPattern(dir: unknown, platform: NodeJS.Platform = process.platform): string {
  const windows = platform === "win32"
  const path = windows ? win32 : posix
  const folder = typeof dir === "string" ? dir.replace(windows ? /[\\/]+$/ : /\/+$/, "") : ""
  if (!folder || !path.isAbsolute(folder) || path.resolve(folder) !== folder || /[*?]/.test(folder) || (!windows && folder.includes("\\"))) {
    const got = typeof dir === "string" ? JSON.stringify(dir) : typeof dir
    throw new TypeError(`readableDirs entries must be absolute folder paths (not a filesystem root; no "..", "." or "//" inside; no "*", "?" or "\\"), got ${got}`)
  }
  return `${windows ? folder.replaceAll("\\", "/") : folder}/*`
}

function validateReadableDirs(dirs: unknown): void {
  if (dirs === undefined) return
  if (!Array.isArray(dirs)) throw new TypeError("readableDirs must be an array of absolute folder paths")
  for (const dir of dirs) readableFolderPattern(dir)
}

function renderOpenCodeConfig(spec: OpenCodeEnvironmentSpec): string {
  const mcp: Record<string, unknown> = {}
  for (const server of spec.mcpServers) {
    mcp[server.name] = {
      type: "local",
      command: [server.command, ...server.args],
      enabled: true,
      environment: server.env,
    }
  }
  const config: Record<string, unknown> = {
    $schema: "https://opencode.ai/config.json",
    mcp,
  }
  if (spec.provider !== null) config.provider = spec.provider
  if (spec.pluginPaths.length) config.plugin = spec.pluginPaths
  if (spec.skillsPaths.length) config.skills = { paths: spec.skillsPaths }
  const p = spec.permissions
  const permission: Record<string, unknown> = {}
  if (p.edit !== "allow" || p.bash !== "allow" || p.webfetch !== "allow") {
    permission.edit = p.edit
    permission.bash = p.bash
    permission.webfetch = p.webfetch
  }
  // OpenCode asks `external_directory` ("<parent folder>/*") before it reads outside the project;
  // an allow entry per readable folder keeps that ask from reaching the host. The rules land after
  // OpenCode's defaults (`* ask`, then its temp and skill allowances) and the last match wins, so
  // there is deliberately no `"*": "ask"` entry here: it would cancel those built-in allowances.
  if (spec.readableDirs?.length) {
    permission.external_directory = Object.fromEntries(spec.readableDirs.map(dir => [readableFolderPattern(dir), "allow"]))
  }
  if (Object.keys(permission).length) config.permission = permission
  return JSON.stringify(config, null, 2) + "\n"
}

export async function prepareOpenCodeEnvironment(spec: OpenCodeEnvironmentSpec): Promise<PreparedEnvironment> {
  requireSpec(spec, [
    ...ENVIRONMENT_FIELDS,
    "configHome",
    "provider",
    "pluginPaths",
    "permissions",
  ])
  validateMcpServerNames(spec.mcpServers)
  validateReadableDirs(spec.readableDirs)
  ensureHome(spec.home)
  mkdirSync(spec.configHome, { recursive: true, mode: 0o700 })
  const files: string[] = []

  const dir = join(spec.configHome, "opencode")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const configPath = join(dir, "opencode.json")
  writeFileSync(configPath, renderOpenCodeConfig(spec), { encoding: "utf8", mode: 0o600 })
  chmodSync(configPath, 0o600)
  files.push(configPath)

  return {
    env: { XDG_CONFIG_HOME: spec.configHome },
    files,
    credentials: "none",
  }
}
