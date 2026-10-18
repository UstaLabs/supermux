import { homedir } from "node:os"
import { resolve } from "node:path"
import { createCore, type Core } from "./core.js"
import type { AgentDriver, CoreLimits, CoreOptions, PermissionsSpec, ToolKind } from "./types.js"
import { claude, type ClaudeOptions } from "./claude/index.js"
import { codex, type CodexOptions } from "./codex/index.js"
import { cursor, grok, opencode, type CursorOptions, type GrokOptions, type OpenCodeOptions } from "./agents/index.js"
import { antigravity, type AntigravityOptions } from "./antigravity/index.js"

type KeeperLimits = { parkedDeadlineMs: number; journalMaxBytes: number; connectTimeoutMs: number }
type AcpPermissions = Extract<PermissionsSpec, { kind: "acp" }>

const MiB = 1024 * 1024
const KEEPER_LIMITS: readonly string[] = ["parkedDeadlineMs", "journalMaxBytes", "connectTimeoutMs"]
const CURSOR_MODES: readonly unknown[] = ["agent", "plan", "ask"]

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object") {
    for (const child of Object.values(value)) deepFreeze(child)
    Object.freeze(value)
  }
  return value
}

/**
 * Every value supermux() fills in when you leave it out. Timeouts and limits are what the supermux
 * app runs in production; permissions are each CLI's own asking mode, so nothing risky runs unless
 * you ask for it. (Options supermux() passes through, such as `accounts`, document their own defaults.)
 */
export const defaults = deepFreeze({
  core: { limits: { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 } },
  claude: {
    command: "claude", args: [], inheritEnv: true, tools: "default", partialMessages: true,
    permissionMode: "default", permissionPrompts: "host",
    setupTimeoutMs: 60_000, requestTimeoutMs: 30_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 * MiB,
    keeper: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
  },
  codex: {
    command: "codex", args: ["app-server"], inheritEnv: true,
    sandbox: "workspace-write", approvalPolicy: "on-request",
    setupTimeoutMs: 30_000, requestTimeoutMs: 30_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 * MiB,
    keeper: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
  },
  grok: {
    command: "grok", commandArgs: [], inheritEnv: true, noLeader: false, mcpServers: [],
    policy: "ask", nativeMode: null,
    setupTimeoutMs: 30_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 * MiB,
    maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000,
    keeper: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * MiB, connectTimeoutMs: 10_000 },
  },
  opencode: {
    command: "opencode", inheritEnv: true, mcpServers: [],
    policy: "ask", nativeMode: null,
    setupTimeoutMs: 120_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 * MiB,
    maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, openCodePollIntervalMs: 500,
    keeper: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * MiB, connectTimeoutMs: 10_000 },
  },
  cursor: {
    command: "cursor-agent", commandArgs: [], inheritEnv: true, mcpServers: [],
    policy: "ask", mode: "agent",
    setupTimeoutMs: 120_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 16 * MiB,
    maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000,
    keeper: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * MiB, connectTimeoutMs: 10_000 },
  },
  antigravity: {
    command: "agy", args: [], inheritEnv: true,
    mode: "default",
    setupTimeoutMs: 60_000, shutdownTimeoutMs: 2_000, maxFrameBytes: 16 * MiB, interruptGraceMs: 5_000,
    keeper: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * MiB, connectTimeoutMs: 10_000 },
  },
} as const)

type ReadonlyArrays<V> = V extends readonly (infer U)[] ? readonly U[] : V
/** The picked factory options, all optional; array options also take readonly arrays (supermux() copies them). */
type Input<T> = { [K in keyof T]?: ReadonlyArrays<T[K]> }
/** Keeper limits, merged one level over the agent's defaults (the keeper itself lives in `stateDirectory`). */
type Keeper = { keeper?: Partial<KeeperLimits> }
/** The ACP permission spec, given flat. */
type AcpInput = { policy?: AcpPermissions["policy"]; askKinds?: readonly ToolKind[] }
type AcpShared =
  | "command" | "env" | "inheritEnv" | "mcpServers" | "setupTimeoutMs" | "shutdownTimeoutMs" | "maxFrameBytes"
  | "maxOutstandingActivity" | "cancelRetryIntervalMs" | "cancelRetryTimeoutMs"

export type ClaudeInput = Input<Pick<ClaudeOptions,
  | "command" | "args" | "env" | "inheritEnv" | "model" | "effort" | "fastMode" | "tools" | "allowedTools" | "disallowedTools"
  | "permissionPrompts" | "partialMessages" | "setupTimeoutMs" | "requestTimeoutMs" | "shutdownTimeoutMs" | "maxFrameBytes">> & Keeper & {
  permissionMode?: Extract<PermissionsSpec, { kind: "claude" }>["permissionMode"]
}
/** `sandbox` and `approvalPolicy` also make the permission spec, so the two cannot disagree. */
export type CodexInput = Input<Pick<CodexOptions,
  | "command" | "env" | "inheritEnv" | "model" | "reasoningEffort" | "sandbox" | "approvalPolicy"
  | "setupTimeoutMs" | "requestTimeoutMs" | "shutdownTimeoutMs" | "maxFrameBytes">> & Keeper & {
  /**
   * Replaces `defaults.codex.args` (`["app-server"]`), subcommand included, so keep "app-server"
   * first for the real CLI. (The other agents' `args`/`commandArgs` default to empty and only come
   * before the arguments their driver adds.)
   */
  args?: readonly string[]
}
export type GrokInput = Input<Pick<GrokOptions, AcpShared | "commandArgs" | "model" | "reasoningEffort" | "authPath" | "noLeader">> & Keeper & AcpInput & {
  nativeMode?: string | null
}
export type OpenCodeInput = Input<Pick<OpenCodeOptions, AcpShared | "model" | "openCodePollIntervalMs">> & Keeper & AcpInput & {
  nativeMode?: string | null
}
export type CursorInput = Input<Pick<CursorOptions, AcpShared | "commandArgs" | "model">> & Keeper & AcpInput & {
  /** Cursor's mode. It becomes the permission spec's native mode, so a session's own permissions can replace it. */
  mode?: NonNullable<CursorOptions["mode"]>
}
export type AntigravityInput = Input<Pick<AntigravityOptions,
  | "command" | "args" | "env" | "inheritEnv" | "model" | "setupTimeoutMs" | "shutdownTimeoutMs" | "maxFrameBytes" | "interruptGraceMs">> & Keeper & {
  mode?: Extract<PermissionsSpec, { kind: "antigravity" }>["mode"]
  /**
   * Required: a shadow HOME prepared with prepareAntigravityEnvironment (from
   * `@supermux/core/environment`). A relative path resolves against the process's cwd.
   */
  home: string
  /** The real HOME the shadow mirrors. Absent: `os.homedir()`. A relative path resolves against the process's cwd. */
  userHome?: string
}
/**
 * The agents to run, by name; an agent left out is not available. `{}` takes every default, but
 * Antigravity always needs `home` (see prepareAntigravityEnvironment in `@supermux/core/environment`).
 */
export type SupermuxAgents = {
  claude?: ClaudeInput
  codex?: CodexInput
  grok?: GrokInput
  opencode?: OpenCodeInput
  cursor?: CursorInput
  antigravity?: AntigravityInput
}
export type SupermuxOptions = Omit<CoreOptions, "agents" | "limits"> & { limits?: Partial<CoreLimits>; agents: SupermuxAgents }

/** The own entries of `input` whose value is not undefined (null is kept: it is a value). */
function defined<T extends object>(input: T | undefined, label: string): Partial<T> {
  if (input === undefined) return {}
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new TypeError(`${label} must be an object`)
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) if (value !== undefined) out[key] = value
  return out as Partial<T>
}

type Copied<V> = V extends readonly (infer U)[] ? U[] : V
/** `options` with every array copied: a factory gets arrays of its own, never the caller's or the frozen defaults'. */
function copyArrays<T extends object>(options: T): { [K in keyof T]: Copied<T[K]> } {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(options)) out[key] = Array.isArray(value) ? [...value] : value
  return out as { [K in keyof T]: Copied<T[K]> }
}

function keeperOf(stateDirectory: string, base: Readonly<KeeperLimits>, input: Partial<KeeperLimits> | undefined) {
  const given = defined(input, "keeper")
  for (const key of Object.keys(given)) {
    if (!KEEPER_LIMITS.includes(key)) {
      throw new TypeError(`keeper.${key} is not an option here: the keeper lives in stateDirectory, and keeper takes ${KEEPER_LIMITS.join(", ")}`)
    }
  }
  return { stateDirectory, limits: { ...base, ...given } }
}

function acpPermissions(policy: AcpPermissions["policy"], nativeMode: string | null, askKinds: ToolKind[] | undefined): AcpPermissions {
  return { kind: "acp", policy, nativeMode, ...(askKinds === undefined ? {} : { askKinds }) }
}

// Each builder merges the input over its agent's defaults, copies the arrays, then maps the flat
// permission fields to the factory's permission spec.

function buildClaude(dir: string, input: ClaudeInput): AgentDriver {
  const { keeper, ...given } = input
  const { keeper: limits, permissionMode, ...options } = copyArrays({ ...defaults.claude, ...given })
  return claude({
    ...options, id: "claude",
    permissions: { kind: "claude", permissionMode },
    keeper: keeperOf(dir, limits, keeper),
  })
}

function buildCodex(dir: string, input: CodexInput): AgentDriver {
  const { keeper, ...given } = input
  const { keeper: limits, ...options } = copyArrays({ ...defaults.codex, ...given })
  return codex({
    ...options, id: "codex",
    permissions: { kind: "codex", sandbox: options.sandbox, approvalPolicy: options.approvalPolicy },
    keeper: keeperOf(dir, limits, keeper),
  })
}

function buildGrok(dir: string, input: GrokInput): AgentDriver {
  const { keeper, ...given } = input
  const { keeper: limits, policy, nativeMode, askKinds, ...options } = copyArrays({ ...defaults.grok, ...given })
  return grok({
    ...options, id: "grok",
    permissions: acpPermissions(policy, nativeMode, askKinds),
    keeper: keeperOf(dir, limits, keeper),
  })
}

function buildOpenCode(dir: string, input: OpenCodeInput): AgentDriver {
  const { keeper, ...given } = input
  const { keeper: limits, policy, nativeMode, askKinds, ...options } = copyArrays({ ...defaults.opencode, ...given })
  return opencode({
    ...options, id: "opencode",
    permissions: acpPermissions(policy, nativeMode, askKinds),
    keeper: keeperOf(dir, limits, keeper),
  })
}

function buildCursor(dir: string, input: CursorInput): AgentDriver {
  const { keeper, ...given } = input
  const { keeper: limits, policy, mode, askKinds, ...options } = copyArrays({ ...defaults.cursor, ...given })
  if (!CURSOR_MODES.includes(mode)) throw new TypeError("mode must be agent, plan, or ask")
  // The mode goes only into the permission spec: as Cursor's own `mode` option it would also be sent
  // as session config, and a session's own permissions must be able to replace it.
  return cursor({
    ...options, id: "cursor",
    permissions: acpPermissions(policy, mode, askKinds),
    keeper: keeperOf(dir, limits, keeper),
  })
}

function buildAntigravity(dir: string, input: AntigravityInput): AgentDriver {
  const { keeper, home, userHome, ...given } = input
  if (typeof home !== "string" || !home) {
    throw new TypeError("home is required: a shadow HOME prepared with prepareAntigravityEnvironment (from @supermux/core/environment)")
  }
  if (userHome !== undefined && (typeof userHome !== "string" || !userHome)) throw new TypeError("userHome must be a nonempty path")
  const { keeper: limits, mode, ...options } = copyArrays({ ...defaults.antigravity, ...given })
  return antigravity({
    ...options, id: "antigravity",
    permissions: { kind: "antigravity", mode },
    // Resolved here: agy would take a relative HOME against the session's cwd (the user's project).
    home: { shadow: resolve(home), user: resolve(userHome ?? homedir()) },
    keeper: keeperOf(dir, limits, keeper),
  })
}

const builders: Record<keyof SupermuxAgents, (dir: string, input: never) => AgentDriver> = {
  claude: buildClaude, codex: buildCodex, grok: buildGrok, opencode: buildOpenCode, cursor: buildCursor, antigravity: buildAntigravity,
}

/** Keys a builder sets itself, refused when given (they would be overwritten): key → what to use instead. */
const REFUSED: Record<keyof SupermuxAgents, Record<string, string>> = {
  claude: { permissions: "permissionMode" },
  codex: { permissions: "sandbox and approvalPolicy" },
  grok: { permissions: "policy, nativeMode and askKinds" },
  opencode: { permissions: "policy, nativeMode and askKinds" },
  cursor: { permissions: "policy, mode and askKinds", nativeMode: "mode" },
  antigravity: { permissions: "mode" },
}

function refuseOverwritten(agent: keyof SupermuxAgents, given: object) {
  if (Object.hasOwn(given, "id")) {
    throw new TypeError("id is not an option here: an agent's id is its key in agents (for another id, pass the driver to createCore() yourself)")
  }
  for (const [key, instead] of Object.entries(REFUSED[agent])) {
    if (Object.hasOwn(given, key)) throw new TypeError(`${key} is not an option here; use ${instead}`)
  }
}

/**
 * createCore() with every missing field filled from `defaults`. Agents are named by key; `{}` takes
 * every default (Antigravity still needs `home`). The state directory also holds each session's
 * keeper. For a custom driver, use createCore() directly.
 */
export function supermux(options: SupermuxOptions): Core {
  if (!options || typeof options !== "object") throw new TypeError("supermux: options are required")
  const { agents, limits, stateDirectory, ...rest } = options
  if (typeof stateDirectory !== "string" || !stateDirectory) throw new TypeError("supermux: stateDirectory is required")
  if (Array.isArray(agents)) throw new TypeError("supermux: agents must be an object keyed by agent name; pass driver instances to createCore() instead")
  if (!agents || typeof agents !== "object") throw new TypeError("supermux: agents is required")
  const dir = resolve(stateDirectory)
  const drivers: AgentDriver[] = []
  for (const [name, input] of Object.entries(agents)) {
    if (input === undefined) continue
    if (!Object.hasOwn(builders, name)) throw new TypeError(`supermux: unknown agent ${name} (expected one of ${Object.keys(builders).join(", ")})`)
    const agent = name as keyof SupermuxAgents
    const given = defined<object>(input, `supermux: agents.${name}`)
    try {
      refuseOverwritten(agent, given)
      drivers.push(builders[agent](dir, given as never))
    } catch (error) {
      throw new TypeError(`supermux: agents.${name}: ${error instanceof Error ? error.message : String(error)}`, { cause: error })
    }
  }
  return createCore({ ...rest, stateDirectory: dir, agents: drivers, limits: { ...defaults.core.limits, ...defined(limits, "supermux: limits") } })
}
