import { chmodSync, mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { ENVIRONMENT_FIELDS, requireSpec, validateMcpServerNames } from "./spec.js"
import type { OpenCodeEnvironmentSpec, PreparedEnvironment } from "./types.js"

function ensureHome(home: string): void {
  mkdirSync(home, { recursive: true, mode: 0o700 })
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
  if (p.edit !== "allow" || p.bash !== "allow" || p.webfetch !== "allow") {
    config.permission = { edit: p.edit, bash: p.bash, webfetch: p.webfetch }
  }
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
