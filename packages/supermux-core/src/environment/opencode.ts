import { chmodSync, mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import type { OpenCodeEnvironmentSpec, PreparedEnvironment } from "./types.js"

function ensureHome(home: string): void {
  mkdirSync(home, { recursive: true, mode: 0o700 })
}

function renderOpenCodeConfig(spec: OpenCodeEnvironmentSpec, instructionsPath: string | undefined): string {
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
  if (instructionsPath) config.instructions = [instructionsPath]
  if (spec.pluginPaths.length) config.plugin = spec.pluginPaths
  if (spec.skillsPaths.length) config.skills = { paths: spec.skillsPaths }
  return JSON.stringify(config, null, 2) + "\n"
}

export async function prepareOpenCodeEnvironment(spec: OpenCodeEnvironmentSpec): Promise<PreparedEnvironment> {
  ensureHome(spec.home)
  mkdirSync(spec.configHome, { recursive: true, mode: 0o700 })
  const files: string[] = []

  let instructionsPath: string | undefined
  if (spec.instructions !== null) {
    const dest = join(spec.home, "AGENTS.md")
    writeFileSync(dest, spec.instructions, { encoding: "utf8", mode: 0o600 })
    chmodSync(dest, 0o600)
    files.push(dest)
    instructionsPath = dest
  }

  const dir = join(spec.configHome, "opencode")
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const configPath = join(dir, "opencode.json")
  writeFileSync(configPath, renderOpenCodeConfig(spec, instructionsPath), { encoding: "utf8", mode: 0o600 })
  chmodSync(configPath, 0o600)
  files.push(configPath)

  return {
    env: { XDG_CONFIG_HOME: spec.configHome },
    files,
    credentials: "none",
  }
}
