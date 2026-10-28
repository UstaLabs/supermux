import { chmodSync, mkdirSync, writeFileSync } from "node:fs"
import { dirname, isAbsolute, join } from "node:path"
import { ENVIRONMENT_FIELDS, requireSpec, validateMcpServerNames } from "./spec.js"
import type { OpenCodeEnvironmentSpec, PreparedEnvironment } from "./types.js"

function ensureHome(home: string): void {
  mkdirSync(home, { recursive: true, mode: 0o700 })
}

/**
 * A folder, not a pattern: `*` and `?` are wildcards in an OpenCode permission pattern and cannot be
 * escaped (a name with one would match more than the folder), and a filesystem root would become `/*`.
 */
function validateReadableDirs(dirs: unknown): void {
  if (dirs === undefined) return
  if (!Array.isArray(dirs) || dirs.some(d => typeof d !== "string" || !isAbsolute(d) || dirname(d) === d || /[*?]/.test(d))) {
    throw new TypeError("readableDirs must be absolute folders (not a filesystem root) without * or ?")
  }
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
    permission.external_directory = Object.fromEntries(spec.readableDirs.map(dir => [`${dir.replace(/[\\/]+$/, "")}/*`, "allow"]))
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
