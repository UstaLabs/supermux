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

/** The context `resume(id, { context })` and `fork({ context })` take: everything but instructions (fixed at creation). */
export type SessionContextUpdate = Omit<SessionContext, "instructions">

/** What a launch does with context items the agent cannot apply. */
export type ContextPolicy = "error" | "warn"

export type ContextItemKind = "instructions" | "skills" | "plugins" | "mcpServers"

/** `unverified`: implemented on a channel that was not proven live; treated as supported. */
export type ContextSupport = "supported" | "unsupported" | "unverified"

export type ContextItemCapability = { support: ContextSupport; note: string }

export type ContextCapabilities = Record<ContextItemKind, ContextItemCapability>

/** `core.capabilities(agent)`. `contextUpdate`: how `session.updateContext` reaches a running session. */
export type AgentCapabilities = { context: ContextCapabilities; contextUpdate: ContextUpdateSupport }

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
  /**
   * Digest of what this launch applies. The driver hands it to the keeper: a detached agent
   * process launched for another fingerprint is replaced by a new one, never re-attached.
   */
  fingerprint: string
}

/** A driver's context support (`AgentDriver.context`). Absent: the driver applies no context. */
export type DriverContextSupport = {
  capabilities: ContextCapabilities
  /** Parts of a context this driver cannot apply beyond the per-item table (e.g. plugin hooks it cannot map). */
  drops?(context: ResolvedContext): ContextDrop[]
  /** In-flight changes (`session.updateContext`). Absent: every change needs a reload. */
  update?: ContextUpdateSupport
}

// ---------------------------------------------------------------- in flight (C1b)

/**
 * `session.updateContext(patch)`: changes to the session's OWN skills, plugins and MCP servers (the
 * core default is untouched). Instructions are fixed when the session is created (a runtime
 * `instructions` key is `invalid_context`).
 */
export type ContextPatch = {
  skills?: { add?: string[]; remove?: string[] }
  plugins?: { add?: string[]; remove?: string[] }
  /** `remove` by server name. */
  mcpServers?: { add?: ContextMcpServer[]; remove?: string[] }
}

export type UpdateContextOptions = {
  /** "never": items that need a relaunch are reported `unsupported` instead. Default "allow". */
  reload?: "allow" | "never"
  /** Claude: `reload_plugins` refuses (and the core reports `unsupported`) a reload that would change the tool list under a cached prompt. Default false. */
  holdOnCacheImpact?: boolean
}

/**
 * - `live`: the running agent took the change, same process, conversation and prompt cache.
 * - `reload`: the core relaunched the agent on the same conversation between turns (or, for a
 *   session that is not open, the next launch applies it).
 * - `unsupported`: not applied (and not stored), with `reason`.
 */
export type ContextUpdateHow = "live" | "reload" | "unsupported"

/** The kinds a patch can change (instructions are fixed at creation). */
export type ContextUpdateKind = Exclude<ContextItemKind, "instructions">

/** One change of a patch. `item`: the path or the MCP server name. */
export type ContextChange = { kind: ContextUpdateKind; op: "add" | "remove"; item: string }

export type ContextApplied = ContextChange & { how: ContextUpdateHow; reason?: string }

export type UpdateContextResult = {
  applied: ContextApplied[]
  /** `now`: the running process already has every applied change. `next_turn`: the agent sees them from its next turn (waited for the current turn, a reload, or a session that is not open). */
  effective: "now" | "next_turn"
}

/** What a driver can do in flight, per kind and operation; the open runtime confirms each change. */
export type ContextUpdateSupport = {
  skills: { add: "live" | "reload"; remove: "live" | "reload"; note: string }
  plugins: { add: "live" | "reload"; remove: "live" | "reload"; note: string }
  mcpServers: { add: "live" | "reload"; remove: "live" | "reload"; note: string }
}

/** `AgentRuntime.context`: live changes on the running process (see ContextUpdateSupport). */
export type RuntimeContextControl = {
  /** Whether this process can take `change` live, given the full new context `next`. */
  live(change: ContextChange, next: LaunchContext): boolean
  /**
   * Applies the live `changes`; `next` is the full new context (same session folder). Returns
   * changes that were refused without effect (e.g. Claude held a plugin reload for the prompt
   * cache), each with its reason. Throws when the process may be in an unknown state.
   */
  apply(next: LaunchContext, changes: ContextChange[], options: UpdateContextOptions): Promise<Array<{ change: ContextChange; reason: string }>>
  /** Records on the keeper what the process now carries, so a later re-attach is not mistaken for an outdated launch. */
  recordFingerprint?(fingerprint: string): Promise<void>
}
