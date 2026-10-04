import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, symlinkSync, writeFileSync } from "node:fs"
import { basename, join } from "node:path"
import { MCP_SERVER_NAME } from "./index.js"
import type { ContextDrop, ResolvedContext, ResolvedMcpServer } from "./types.js"

/** What a plugin folder holds, for agents that map plugins instead of loading them natively. */
export type PluginParts = {
  path: string
  name: string
  /** `<plugin>/skills` when it exists. */
  skills?: string
  /** stdio servers of `<plugin>/.mcp.json` (`${CLAUDE_PLUGIN_ROOT}` expanded). */
  mcpServers: ResolvedMcpServer[]
  /** Parts no mapping can carry: `hooks`, `commands`, `agents`, non-stdio MCP servers. */
  unmapped: string[]
  /** `<plugin>/.opencode/plugins/*.{js,ts}` (OpenCode JS plugins). */
  opencodePlugins: string[]
}

function isDirectory(path: string): boolean {
  try { return statSync(path).isDirectory() } catch { return false }
}

function nonEmpty(path: string): boolean {
  try { return statSync(path).isDirectory() ? readdirSync(path).length > 0 : true } catch { return false }
}

function manifestName(path: string): string | undefined {
  for (const dir of [".claude-plugin", ".codex-plugin", ".cursor-plugin"]) {
    try {
      const name = JSON.parse(readFileSync(join(path, dir, "plugin.json"), "utf8"))?.name
      if (typeof name === "string" && name) return name
    } catch { /* next manifest */ }
  }
  return undefined
}

function expand(value: string, root: string): string {
  return value.replaceAll("${CLAUDE_PLUGIN_ROOT}", root).replaceAll("${PLUGIN_ROOT}", root)
}

export function readPlugin(path: string): PluginParts {
  const parts: PluginParts = { path, name: manifestName(path) ?? basename(path), mcpServers: [], unmapped: [], opencodePlugins: [] }
  if (isDirectory(join(path, "skills"))) parts.skills = join(path, "skills")
  for (const part of ["hooks", "commands", "agents"]) if (existsSync(join(path, part)) && nonEmpty(join(path, part))) parts.unmapped.push(part)
  const mcpFile = join(path, ".mcp.json")
  if (existsSync(mcpFile)) {
    let servers: Record<string, unknown> = {}
    try {
      const parsed = JSON.parse(readFileSync(mcpFile, "utf8"))
      const map = parsed && typeof parsed === "object" && parsed.mcpServers && typeof parsed.mcpServers === "object" ? parsed.mcpServers : parsed
      if (map && typeof map === "object" && !Array.isArray(map)) servers = map
    } catch { parts.unmapped.push(".mcp.json (unreadable)") }
    for (const [name, raw] of Object.entries(servers)) {
      const entry = raw as Record<string, unknown> | null
      const stdio = entry && typeof entry === "object" && typeof entry.command === "string" && entry.command
        && (entry.type === undefined || entry.type === "stdio")
      if (!stdio || !MCP_SERVER_NAME.test(name)) { parts.unmapped.push(`mcpServers.${name}`); continue }
      const args = Array.isArray(entry.args) ? entry.args.filter((arg): arg is string => typeof arg === "string").map(arg => expand(arg, path)) : []
      const env: Record<string, string> = {}
      if (entry.env && typeof entry.env === "object") {
        for (const [key, value] of Object.entries(entry.env as Record<string, unknown>)) if (typeof value === "string") env[key] = expand(value, path)
      }
      parts.mcpServers.push({ name, command: expand(entry.command as string, path), args, env })
    }
  }
  const jsDir = join(path, ".opencode", "plugins")
  if (isDirectory(jsDir)) {
    for (const file of readdirSync(jsDir).sort()) if (/\.(js|mjs|ts)$/.test(file)) parts.opencodePlugins.push(join(jsDir, file))
  }
  return parts
}

/**
 * Drops for an agent that maps plugins (Codex, OpenCode): parts it cannot carry, and plugin MCP
 * servers whose name another server already has.
 */
export function mappedPluginDrops(context: ResolvedContext, mapsOpenCodePlugins: boolean): ContextDrop[] {
  const drops: ContextDrop[] = []
  const names = new Set(context.mcpServers.map(server => server.name))
  for (const path of context.plugins) {
    const parts = readPlugin(path)
    for (const part of parts.unmapped) drops.push({ kind: "plugins", item: `${path} (${part})`, reason: `This agent maps plugins (skills and stdio MCP servers); it cannot apply ${part}` })
    if (!mapsOpenCodePlugins && parts.opencodePlugins.length) drops.push({ kind: "plugins", item: `${path} (.opencode/plugins)`, reason: "OpenCode JS plugins only run on OpenCode" })
    for (const server of parts.mcpServers) {
      if (names.has(server.name)) drops.push({ kind: "plugins", item: `${path} (mcpServers.${server.name})`, reason: `Another MCP server is already named ${server.name}` })
      names.add(server.name)
    }
  }
  return drops
}

/** Plugin MCP servers that a mapping agent applies (the first server of a name wins). */
export function mappedPluginServers(context: ResolvedContext): ResolvedMcpServer[] {
  const names = new Set(context.mcpServers.map(server => server.name))
  const out: ResolvedMcpServer[] = []
  for (const path of context.plugins) {
    for (const server of readPlugin(path).mcpServers) {
      if (names.has(server.name)) continue
      names.add(server.name)
      out.push(server)
    }
  }
  return out
}

/**
 * A generated plugin that only wraps one skills folder (`skills` → symlink to it), with the
 * manifests the given agents read. The design's skills channel for agents that load plugins.
 */
export function writeSkillsPlugin(dir: string, name: string, skillsRoot: string, manifests: Array<".claude-plugin" | ".cursor-plugin">): string {
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  for (const manifest of manifests) {
    mkdirSync(join(dir, manifest), { recursive: true, mode: 0o700 })
    writeFileSync(join(dir, manifest, "plugin.json"), JSON.stringify({
      name, version: "0.0.0", description: `Skills from ${skillsRoot} (generated by supermux)`,
      ...(manifest === ".cursor-plugin" ? { skills: "./skills/" } : {}),
    }, null, 2) + "\n", { mode: 0o600 })
  }
  symlinkSync(skillsRoot, join(dir, "skills"))
  return dir
}
