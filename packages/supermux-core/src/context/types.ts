/**
 * Session context (slice C1): extra instructions, skills, plugins and MCP servers for a session,
 * applied at launch through per-session channels that never touch the workdir or the user's homes.
 */

/** A stdio MCP server the agent starts itself. (C2 adds host servers built with `mcpServer()`.) */
export type ExternalMcpServer = {
  /** Unique within the session; `^[A-Za-z0-9_-]+$`. */
  name: string
  command: string
  args?: string[]
  env?: Record<string, string>
}

/** Every MCP server entry a context may hold. C2 widens this union with host servers. */
export type ContextMcpServer = ExternalMcpServer

export type SessionContext = {
  /** Appended to the agent's own system prompt, never replacing it. Several entries are joined in order. */
  instructions?: string | string[]
  /** Absolute folders holding `<name>/SKILL.md` skill folders. */
  skills?: string[]
  /** Absolute plugin folders. */
  plugins?: string[]
  mcpServers?: ContextMcpServer[]
}

/** What a launch does with context items the agent cannot apply. */
export type ContextPolicy = "error" | "warn"

export type ContextItemKind = "instructions" | "skills" | "plugins" | "mcpServers"

/** `unverified`: implemented on a channel that was not proven live; treated as supported. */
export type ContextSupport = "supported" | "unsupported" | "unverified"

export type ContextItemCapability = { support: ContextSupport; note: string }

export type ContextCapabilities = Record<ContextItemKind, ContextItemCapability>

/** `core.capabilities(agent)`. */
export type AgentCapabilities = { context: ContextCapabilities }

/** One context item (or part of one) that a launch could not apply. */
export type ContextDrop = {
  kind: ContextItemKind
  /** What was dropped: `instructions`, a skills/plugin path (a plugin part as `<path> (hooks)`), or an MCP server name. */
  item: string
  reason: string
}

/** An MCP server after validation: every field present. */
export type ResolvedMcpServer = { name: string; command: string; args: string[]; env: Record<string, string> }

/** The merged (core default + session), validated context. */
export type ResolvedContext = {
  /** Joined instructions; absent when there are none. */
  instructions?: string
  skills: string[]
  plugins: string[]
  mcpServers: ResolvedMcpServer[]
}

/** What a driver gets in `DriverContext.sessionContext` (absent when the session has no context at all). */
export type LaunchContext = ResolvedContext & {
  /** Core-owned folder for this session's generated files. Emptied before each launch; never the workdir. */
  directory: string
  /** `create`: a new native conversation; `resume` / `fork`: an existing one (or a copy of it). */
  launch: "create" | "resume" | "fork"
  /** Items the core already dropped for this launch (policy "warn"): the driver must not apply them. */
  dropped: ContextDrop[]
}

/** A driver's context support (`AgentDriver.context`). Absent: the driver applies no context. */
export type DriverContextSupport = {
  capabilities: ContextCapabilities
  /** Instructions are fixed when the native conversation is created; resume/fork cannot change them. */
  instructionsFixedAtCreation?: boolean
  /** Parts of a context this driver cannot apply beyond the per-item table (e.g. plugin hooks it cannot map). */
  drops?(context: ResolvedContext): ContextDrop[]
}
