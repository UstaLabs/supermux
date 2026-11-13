import { chmodSync, mkdirSync } from "node:fs"
import { join } from "node:path"
import { ENVIRONMENT_FIELDS, requireSpec, validateMcpServerNames } from "./spec.js"
import type { ClaudeEnvironmentSpec, McpServerSpec, PreparedEnvironment } from "./types.js"
import { writeFileNoFollow } from "./write.js"
import { files as platformFiles } from "@supermux/platform"

function ensureHome(home: string): void {
  platformFiles.mkdirPrivate(home)
  platformFiles.makePrivate(home)
}

function renderClaudeMcp(servers: McpServerSpec[]): string {
  const mcpServers: Record<string, { command: string; args: string[]; env: Record<string, string> }> = {}
  for (const server of servers) {
    mcpServers[server.name] = { command: server.command, args: server.args, env: server.env }
  }
  return JSON.stringify({ mcpServers })
}

export async function prepareClaudeEnvironment(spec: ClaudeEnvironmentSpec): Promise<PreparedEnvironment & { args: string[] }> {
  requireSpec(spec, [
    ...ENVIRONMENT_FIELDS,
    "pluginDirs",
    "addDirs",
    "systemPromptFiles",
    "strictMcp",
    "nativeMemory",
  ])
  validateMcpServerNames(spec.mcpServers)
  ensureHome(spec.home)
  const files: string[] = []
  const args: string[] = []

  for (const path of spec.systemPromptFiles) {
    args.push("--append-system-prompt-file", path)
  }
  for (const dir of spec.pluginDirs) {
    args.push("--plugin-dir", dir)
  }
  for (const dir of spec.addDirs) {
    args.push("--add-dir", dir)
  }

  if (spec.mcpServers.length > 0) {
    const dest = join(spec.home, "mcp.json")
    writeFileNoFollow(dest, renderClaudeMcp(spec.mcpServers))
    platformFiles.makePrivate(dest)
    files.push(dest)
    if (spec.strictMcp) args.push("--strict-mcp-config")
    args.push("--mcp-config", dest)
  }

  const env: Record<string, string> = spec.nativeMemory ? {} : { CLAUDE_CODE_DISABLE_AUTO_MEMORY: "1" }
  return { env, files, credentials: "none", args }
}
