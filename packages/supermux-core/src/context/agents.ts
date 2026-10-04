/**
 * Per-agent context channels (C1). Each builder writes only into `LaunchContext.directory` (a
 * core-owned per-session folder) and returns the args / env / requests the driver adds. The
 * channels are the ones C0 proved live (docs/core-design/context-c0-report.md).
 */
import { lstatSync, mkdirSync, readFileSync, readdirSync, readlinkSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { basename, join } from "node:path"
import type { McpServer } from "@agentclientprotocol/sdk"
import { mappedPluginDrops, mappedPluginServers, readPlugin, writeSkillsPlugin } from "./plugins.js"
import type {
  ContextCapabilities, ContextChange, ContextDrop, ContextUpdateSupport, DriverContextSupport, LaunchContext, ResolvedContext, ResolvedMcpServer,
} from "./types.js"
import { reloadOnlyUpdates } from "./update.js"

const dropped = (context: LaunchContext, kind: ContextDrop["kind"], item: string) =>
  context.dropped.some(drop => drop.kind === kind && drop.item === item)
/** What a launch context applies: its items minus the ones the core dropped. */
export const appliedContext = (context: LaunchContext) => applied(context)
const applied = (context: LaunchContext) => ({
  instructions: dropped(context, "instructions", "instructions") ? undefined : context.instructions,
  skills: context.skills.filter(path => !dropped(context, "skills", path)),
  plugins: context.plugins.filter(path => !dropped(context, "plugins", path)),
  mcpServers: context.mcpServers.filter(server => !dropped(context, "mcpServers", server.name)),
})

function writePrivate(path: string, text: string): string {
  writeFileSync(path, text, { mode: 0o600 })
  return path
}

/** Plugin folders → `<dir>/plugins/NN-<name>` symlinks plus one generated wrapper per skills folder. */
function pluginFolder(context: LaunchContext, skills: string[], plugins: string[], manifests: Array<".claude-plugin" | ".cursor-plugin">): { folder: string; entries: string[] } {
  const folder = join(context.directory, "plugins")
  // Rebuilt on every launch of the process (Grok respawns on configure with the same context).
  rmSync(folder, { recursive: true, force: true })
  mkdirSync(folder, { recursive: true, mode: 0o700 })
  const entries: string[] = []
  plugins.forEach((path, index) => {
    const link = join(folder, `${String(index + 1).padStart(2, "0")}-${basename(path)}`)
    symlinkSync(path, link)
    entries.push(link)
  })
  skills.forEach((root, index) => {
    entries.push(writeSkillsPlugin(join(folder, `supermux-skills-${index + 1}`), `supermux-skills-${index + 1}`, root, manifests))
  })
  return { folder, entries }
}

/** One entry of a session plugin folder and what it points at. */
type FolderEntry = { name: string; kind: "plugins" | "skills"; path: string; index: number }

function folderEntries(folder: string): FolderEntry[] {
  let names: string[]
  try { names = readdirSync(folder) } catch { return [] }
  const out: FolderEntry[] = []
  for (const name of names) {
    const entry = join(folder, name)
    try {
      const plugin = /^(\d+)-/.exec(name)
      if (plugin && lstatSync(entry).isSymbolicLink()) { out.push({ name, kind: "plugins", path: readlinkSync(entry), index: Number(plugin[1]) }); continue }
      const skills = /^supermux-skills-(\d+)$/.exec(name)
      if (skills) out.push({ name, kind: "skills", path: readlinkSync(join(entry, "skills")), index: Number(skills[1]) })
    } catch { /* not ours */ }
  }
  return out
}

/**
 * Applies skills/plugins changes to a launched session plugin folder (the same layout as
 * pluginFolder: `NN-<name>` symlinks and `supermux-skills-N` wrappers; new entries take the next
 * free number). Returns an undo that restores the folder.
 */
export function editPluginFolder(directory: string, changes: ContextChange[], manifests: Array<".claude-plugin" | ".cursor-plugin">): () => void {
  const folder = join(directory, "plugins")
  mkdirSync(folder, { recursive: true, mode: 0o700 })
  const undo: Array<() => void> = []
  const entries = folderEntries(folder)
  let pluginIndex = Math.max(0, ...entries.filter(entry => entry.kind === "plugins").map(entry => entry.index))
  let skillsIndex = Math.max(0, ...entries.filter(entry => entry.kind === "skills").map(entry => entry.index))
  for (const change of changes) {
    if (change.kind !== "skills" && change.kind !== "plugins") continue
    if (change.op === "remove") {
      for (const entry of entries.filter(candidate => candidate.kind === change.kind && candidate.path === change.item)) {
        const path = join(folder, entry.name)
        rmSync(path, { recursive: true, force: true })
        undo.push(() => {
          if (entry.kind === "plugins") symlinkSync(entry.path, path)
          else writeSkillsPlugin(path, entry.name, entry.path, manifests)
        })
      }
    } else if (change.kind === "plugins") {
      const path = join(folder, `${String(++pluginIndex).padStart(2, "0")}-${basename(change.item)}`)
      symlinkSync(change.item, path)
      undo.push(() => rmSync(path, { force: true }))
    } else {
      const name = `supermux-skills-${++skillsIndex}`
      const path = writeSkillsPlugin(join(folder, name), name, change.item, manifests)
      undo.push(() => rmSync(path, { recursive: true, force: true }))
    }
  }
  return () => { for (const step of undo.reverse()) { try { step() } catch { /* best effort */ } } }
}

// ---------------------------------------------------------------- Claude

export const CLAUDE_CONTEXT: DriverContextSupport = {
  capabilities: {
    instructions: { support: "supported", note: "--append-system-prompt-file at session creation (a host's own appended prompt is kept: it is copied in first); fixed from then on: --resume keeps the stored prompt and ignores a new one" },
    skills: { support: "supported", note: "a generated plugin per skills folder, in the session's --plugin-dir folder" },
    plugins: { support: "supported", note: "--plugin-dir <folder of plugins> (each plugin symlinked in)" },
    mcpServers: { support: "supported", note: "--mcp-config <session>/mcp.json (no --strict-mcp-config is added)" },
  },
  // Verified on 2.1.289 (C1b): a session keeps the appended system prompt it was created with;
  // `--resume` with another (or no) --append-system-prompt[-file] still answers from the stored one.
  instructionsFixedAtCreation: true,
  update: {
    instructions: { how: "unsupported", note: "Claude fixes the appended system prompt when the session is created: --resume ignores a new --append-system-prompt-file, and apply_flag_settings {appendSystemPrompt} succeeds but changes nothing" },
    skills: { add: "live", remove: "live", note: "a skills wrapper plugin added to / removed from the session plugin folder, then reload_plugins (reload_skills does not load a new plugin); live only when the process was launched with that folder" },
    plugins: { add: "live", remove: "live", note: "a symlink added to / removed from the session plugin folder, then reload_plugins; live only when the process was launched with that folder" },
    mcpServers: { add: "live", remove: "live", note: "mcp_set_servers with the full dynamic set; a server from the launch (--mcp-config) can only go with a relaunch" },
  },
}

/** The last value of a flag in `args` (Claude keeps the last of a repeated single-value flag). */
function lastFlag(args: string[], flag: string): string | undefined {
  let value: string | undefined
  for (let i = 0; i < args.length; i++) {
    if (args[i] === flag && i + 1 < args.length) value = args[i + 1]
    else if (args[i]!.startsWith(`${flag}=`)) value = args[i]!.slice(flag.length + 1)
  }
  return value
}

/** Args appended after every other Claude arg (`hostArgs`: the args already on the command line). */
export function claudeContextArgs(context: LaunchContext, hostArgs: string[]): string[] {
  const use = applied(context)
  const args: string[] = []
  if (use.instructions !== undefined) {
    // A repeated --append-system-prompt-file keeps only the last one (verified on 2.1.289), so a
    // host's own appended prompt is carried into this file ahead of the context's instructions.
    const host: string[] = []
    const hostFile = lastFlag(hostArgs, "--append-system-prompt-file")
    if (hostFile !== undefined) host.push(readFileSync(hostFile, "utf8"))
    const hostText = lastFlag(hostArgs, "--append-system-prompt")
    if (hostText !== undefined) host.push(hostText)
    const file = writePrivate(join(context.directory, "instructions.md"), [...host, use.instructions].join("\n\n"))
    args.push("--append-system-prompt-file", file)
  }
  // Always passed (even empty) so skills and plugins added later go in live (reload_plugins).
  args.push("--plugin-dir", pluginFolder(context, use.skills, use.plugins, [".claude-plugin"]).folder)
  if (use.mcpServers.length) {
    const servers = Object.fromEntries(use.mcpServers.map(server => [server.name, { type: "stdio", command: server.command, args: server.args, env: server.env }]))
    args.push("--mcp-config", writePrivate(join(context.directory, "mcp.json"), JSON.stringify({ mcpServers: servers }, null, 2) + "\n"))
  }
  return args
}

// ---------------------------------------------------------------- Codex

export const CODEX_CONTEXT: DriverContextSupport = {
  capabilities: {
    instructions: { support: "supported", note: "thread/start developerInstructions; fixed when the thread is created (resume ignores them)" },
    skills: { support: "supported", note: "skills/extraRoots/set before thread/start or thread/resume" },
    plugins: { support: "supported", note: "mapped, not installed: the plugin's skills/ → extraRoots, its .mcp.json stdio servers → MCP servers; hooks, commands and agents are dropped" },
    mcpServers: { support: "supported", note: "app-server -c mcp_servers.<name>.* (this process only; nothing is written to CODEX_HOME), tools pre-approved per server (default_tools_approval_mode=\"approve\")" },
  },
  instructionsFixedAtCreation: true,
  drops: context => mappedPluginDrops(context, false),
  update: {
    instructions: { how: "append", note: "Codex fixes developerInstructions at thread/start (thread/resume ignores them)" },
    skills: { add: "live", remove: "live", note: "skills/extraRoots/set with the full new list" },
    plugins: { add: "live", remove: "live", note: "live when the plugin maps to skills only (extraRoots); a plugin with MCP servers needs a relaunch" },
    mcpServers: { add: "reload", remove: "reload", note: "MCP servers are app-server -c args (this process only): a running app-server takes no per-process server (thread/resume config is ignored; config/value/write only writes the shared user config.toml), so a change starts a new app-server and resumes the thread" },
  },
}

/** The extraRoots a launch context gives Codex (skills folders, then each plugin's skills/). */
export function codexExtraRoots(context: LaunchContext): string[] {
  const use = applied(context)
  return [...use.skills, ...use.plugins.map(readPlugin).flatMap(parts => parts.skills ? [parts.skills] : [])]
}

/** Whether Codex can take a plugin change live: the plugin maps to skills only (no MCP servers to start or stop). */
export function codexPluginIsLive(path: string): boolean {
  try { return readPlugin(path).mcpServers.length === 0 } catch { return false }
}

function toml(value: string): string { return JSON.stringify(value) }

function codexServerArgs(server: ResolvedMcpServer): string[] {
  const key = `mcp_servers.${server.name}`
  const env = Object.entries(server.env).map(([name, value]) => `${toml(name)} = ${toml(value)}`).join(", ")
  return [
    "-c", `${key}.command=${toml(server.command)}`,
    "-c", `${key}.args=[${server.args.map(toml).join(", ")}]`,
    ...(env ? ["-c", `${key}.env={ ${env} }`] : []),
    // Context servers are the host's own: their tools run without an approval round-trip, under
    // any approval policy ("never" otherwise refuses every MCP tool call). Other servers keep
    // the session's policy. Verified live on codex 0.159.2.
    "-c", `${key}.default_tools_approval_mode="approve"`,
  ]
}

export type CodexContextLaunch = {
  /** Appended to the app-server args. */
  args: string[]
  /** `skills/extraRoots/set` before the thread opens (absent: not sent). */
  extraRoots?: string[]
  /** `thread/start.developerInstructions` (only when the thread is created). */
  developerInstructions?: string
}

export function codexContextLaunch(context: LaunchContext): CodexContextLaunch {
  const use = applied(context)
  const extraRoots = codexExtraRoots(context)
  const servers = [...use.mcpServers, ...mappedPluginServers({ ...context, plugins: use.plugins, mcpServers: use.mcpServers })]
  return {
    args: servers.flatMap(codexServerArgs),
    ...(extraRoots.length ? { extraRoots } : {}),
    ...(context.launch === "create" && use.instructions !== undefined ? { developerInstructions: use.instructions } : {}),
  }
}

// ---------------------------------------------------------------- ACP agents

const toAcp = (server: ResolvedMcpServer): McpServer => ({
  name: server.name, command: server.command, args: [...server.args],
  env: Object.entries(server.env).map(([name, value]) => ({ name, value })),
})

/** ACP drops for context servers whose name a driver-level server already has. */
export function acpServerDrops(context: ResolvedContext, factoryServers: McpServer[]): ContextDrop[] {
  const taken = new Set(factoryServers.map(server => server.name))
  return context.mcpServers.filter(server => taken.has(server.name)).map(server => ({
    kind: "mcpServers" as const, item: server.name, reason: `The driver already passes an MCP server named ${server.name}`,
  }))
}

/** What an ACP vendor adds for a session's context. */
export type AcpContextLaunch = {
  /** Inserted before the final subcommand of the driver args (`stdio` / `acp`). */
  args: string[]
  env: Record<string, string>
  mcpServers: McpServer[]
  /** `_meta` of `session/new` (only when the session is created). */
  newSessionMeta?: Record<string, unknown>
}

export type AcpContextAdapter = {
  support: DriverContextSupport
  launch(context: LaunchContext, env: Record<string, string | undefined>): AcpContextLaunch
}

const GENERIC_ACP_CAPABILITIES: ContextCapabilities = {
  instructions: { support: "unsupported", note: "No per-session instructions channel for this ACP agent" },
  skills: { support: "unsupported", note: "No per-session skills channel for this ACP agent" },
  plugins: { support: "unsupported", note: "No per-session plugin channel for this ACP agent" },
  mcpServers: { support: "unverified", note: "ACP session/new mcpServers (standard ACP; not verified for this agent)" },
}

export function genericAcpContext(factoryServers: McpServer[]): AcpContextAdapter {
  return {
    support: { capabilities: GENERIC_ACP_CAPABILITIES, drops: context => acpServerDrops(context, factoryServers), update: reloadOnlyUpdates(GENERIC_ACP_CAPABILITIES, "ACP session/new mcpServers: a change relaunches the agent (new process + session/load)") },
    launch: context => ({ args: [], env: {}, mcpServers: applied(context).mcpServers.map(toAcp) }),
  }
}

export function grokContext(factoryServers: McpServer[]): AcpContextAdapter {
  return {
    support: {
      capabilities: {
        instructions: { support: "supported", note: "ACP session/new _meta.rules; fixed when the session is created (session/load ignores new rules)" },
        skills: { support: "supported", note: "a generated plugin per skills folder, `grok agent --plugin-dir` (this process only)" },
        plugins: { support: "supported", note: "`grok agent --plugin-dir <plugin>` (this process only)" },
        mcpServers: { support: "supported", note: "ACP session/new mcpServers" },
      },
      instructionsFixedAtCreation: true,
      drops: context => acpServerDrops(context, factoryServers),
      update: {
        instructions: { how: "unsupported", note: "Grok fixes _meta.rules at session/new (session/load ignores new rules)" },
        skills: { add: "reload", remove: "reload", note: "skills ride per-process --plugin-dir: a change relaunches grok (new process + session/load)" },
        plugins: { add: "reload", remove: "reload", note: "per-process --plugin-dir: a change relaunches grok (new process + session/load)" },
        mcpServers: { add: "reload", remove: "reload", note: "ACP mcpServers are passed at session/new|load: a change relaunches grok" },
      },
    },
    launch(context) {
      const use = applied(context)
      const args: string[] = []
      if (use.skills.length || use.plugins.length) {
        for (const entry of pluginFolder(context, use.skills, use.plugins, [".claude-plugin"]).entries) args.push("--plugin-dir", entry)
      }
      return {
        args, env: {}, mcpServers: use.mcpServers.map(toAcp),
        ...(context.launch === "create" && use.instructions !== undefined ? { newSessionMeta: { rules: use.instructions } } : {}),
      }
    },
  }
}

export function cursorContext(factoryServers: McpServer[]): AcpContextAdapter {
  return {
    support: {
      capabilities: {
        instructions: { support: "unsupported", note: "No proven per-session instructions channel for Cursor (C0 ran out of quota); never written into the repo" },
        skills: { support: "unverified", note: "a generated plugin per skills folder via --plugin-dir (not verified: Cursor does not advertise plugin skills over ACP)" },
        plugins: { support: "unverified", note: "--plugin-dir <plugin> (not verified live)" },
        mcpServers: { support: "unverified", note: "ACP session/new mcpServers (the server starts and lists; a tool call was not verified)" },
      },
      drops: context => acpServerDrops(context, factoryServers),
      update: {
        instructions: { how: "unsupported", note: "No proven per-session instructions channel for Cursor" },
        skills: { add: "reload", remove: "reload", note: "unverified for Cursor: a change relaunches the agent" },
        plugins: { add: "reload", remove: "reload", note: "unverified for Cursor: a change relaunches the agent" },
        mcpServers: { add: "reload", remove: "reload", note: "unverified for Cursor: a change relaunches the agent" },
      },
    },
    launch(context) {
      const use = applied(context)
      const args: string[] = []
      if (use.skills.length || use.plugins.length) {
        for (const entry of pluginFolder(context, use.skills, use.plugins, [".cursor-plugin", ".claude-plugin"]).entries) args.push("--plugin-dir", entry)
      }
      return { args, env: {}, mcpServers: use.mcpServers.map(toAcp) }
    },
  }
}

/** Reads a JSON / JSONC config file; undefined when absent or unreadable. */
function readConfig(path: string): Record<string, unknown> | undefined {
  let text: string
  try { text = readFileSync(path, "utf8") } catch { return undefined }
  for (const candidate of [text, stripJsonComments(text)]) {
    try {
      const value = JSON.parse(candidate)
      if (value && typeof value === "object" && !Array.isArray(value)) return value
    } catch { /* try the next form */ }
  }
  return undefined
}

/** Removes // and /* *\/ comments and trailing commas outside strings (OpenCode accepts JSONC). */
function stripJsonComments(text: string): string {
  let out = ""
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!
    if (c === '"') {
      let j = i + 1
      while (j < text.length && text[j] !== '"') j += text[j] === "\\" ? 2 : 1
      out += text.slice(i, j + 1)
      i = j
    } else if (c === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++
      out += "\n"
    } else if (c === "/" && text[i + 1] === "*") {
      i = text.indexOf("*/", i + 2)
      if (i < 0) break
      i++
    } else out += c
  }
  return out.replace(/,(\s*[}\]])/g, "$1")
}

function skillPaths(config: Record<string, unknown> | undefined): string[] {
  const paths = (config?.skills as { paths?: unknown } | undefined)?.paths
  return Array.isArray(paths) ? paths.filter((path): path is string => typeof path === "string") : []
}

export function opencodeContext(factoryServers: McpServer[]): AcpContextAdapter {
  return {
    support: {
      capabilities: {
        instructions: { support: "supported", note: "config `instructions` in a session-private OPENCODE_CONFIG file (merged with the user's config)" },
        skills: { support: "supported", note: "config `skills.paths` in the session OPENCODE_CONFIG file" },
        plugins: { support: "supported", note: "mapped: skills/ → skills.paths, .opencode/plugins/* → plugin, .mcp.json stdio servers → MCP servers; hooks, commands and agents are dropped" },
        mcpServers: { support: "supported", note: "ACP session/new mcpServers" },
      },
      drops: context => [...mappedPluginDrops(context, true), ...acpServerDrops(context, factoryServers)],
      update: {
        instructions: { how: "reload", note: "OpenCode reads config instructions at start: a change relaunches it (new process + session/load)" },
        skills: { add: "reload", remove: "reload", note: "OpenCode reads skills.paths at start: a change relaunches it" },
        plugins: { add: "reload", remove: "reload", note: "OpenCode reads the mapped plugin parts at start: a change relaunches it" },
        mcpServers: { add: "reload", remove: "reload", note: "ACP mcpServers are passed at session/new|load: a change relaunches OpenCode" },
      },
    },
    launch(context, env) {
      const use = applied(context)
      const plugins = use.plugins.map(readPlugin)
      // OPENCODE_CONFIG is merged over the global config, but `skills.paths` REPLACES the global
      // list (instructions and plugin concatenate; verified on 1.16.2), so the global paths are
      // repeated here. A host's own OPENCODE_CONFIG is carried into this file, which replaces it.
      const configHome = env.XDG_CONFIG_HOME || (env.HOME ? join(env.HOME, ".config") : undefined)
      const global = configHome ? ["opencode.json", "opencode.jsonc", "config.json"].map(name => readConfig(join(configHome, "opencode", name))).find(Boolean) : undefined
      const base = env.OPENCODE_CONFIG ? readConfig(env.OPENCODE_CONFIG) ?? {} : {}
      const config: Record<string, unknown> = structuredClone(base)
      if (use.instructions !== undefined) {
        const file = writePrivate(join(context.directory, "instructions.md"), use.instructions)
        config.instructions = [...(Array.isArray(base.instructions) ? base.instructions : []), file]
      }
      const skills = [...use.skills, ...plugins.flatMap(parts => parts.skills ? [parts.skills] : [])]
      if (skills.length) {
        config.skills = { ...(base.skills as object | undefined ?? {}), paths: [...new Set([...skillPaths(global), ...skillPaths(base), ...skills])] }
      }
      const js = plugins.flatMap(parts => parts.opencodePlugins).map(path => `file://${path}`)
      if (js.length) config.plugin = [...(Array.isArray(base.plugin) ? base.plugin : []), ...js]
      const out: Record<string, string> = {}
      if (use.instructions !== undefined || skills.length || js.length) {
        out.OPENCODE_CONFIG = writePrivate(join(context.directory, "opencode.json"), JSON.stringify(config, null, 2) + "\n")
      }
      const servers = [...use.mcpServers, ...mappedPluginServers({ ...context, plugins: use.plugins, mcpServers: use.mcpServers })]
      return { args: [], env: out, mcpServers: servers.map(toAcp) }
    },
  }
}
