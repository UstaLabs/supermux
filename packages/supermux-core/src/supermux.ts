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

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object") {
    for (const child of Object.values(value)) deepFreeze(child)
    Object.freeze(value)
  }
  return value
}

/**
 * Every value supermux() fills in when you leave it out, and the only defaults in this package.
 * Timeouts and limits are what the supermux app runs in production; permissions are each CLI's own
 * asking mode, so nothing risky runs unless you ask for it.
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

/** Keeper limits, merged one level over the agent's defaults (the keeper lives in the core's state directory). */
type Keeper = { keeper?: Partial<KeeperLimits> }
/** The ACP permission spec, given flat. */
type AcpInput = { policy?: AcpPermissions["policy"]; askKinds?: ToolKind[] }

export type ClaudeInput = Partial<Omit<ClaudeOptions, "id" | "keeper" | "permissions">> & Keeper & {
  permissionMode?: Extract<PermissionsSpec, { kind: "claude" }>["permissionMode"]
}
/** `sandbox` and `approvalPolicy` also make the permission spec, so the two cannot disagree. */
export type CodexInput = Partial<Omit<CodexOptions, "id" | "keeper" | "permissions">> & Keeper
export type GrokInput = Partial<Omit<GrokOptions, "id" | "keeper" | "permissions">> & Keeper & AcpInput & { nativeMode?: string | null }
export type OpenCodeInput = Partial<Omit<OpenCodeOptions, "id" | "keeper" | "permissions">> & Keeper & AcpInput & { nativeMode?: string | null }
/** `mode` is also the permission spec's native mode. */
export type CursorInput = Partial<Omit<CursorOptions, "id" | "keeper" | "permissions">> & Keeper & AcpInput
export type AntigravityInput = Partial<Omit<AntigravityOptions, "id" | "keeper" | "permissions" | "home">> & Keeper & {
  mode?: Extract<PermissionsSpec, { kind: "antigravity" }>["mode"]
  /** A prepared shadow HOME (see prepareAntigravityEnvironment). Required. */
  home: string
  /** The real HOME the shadow mirrors. Absent: this user's home directory. */
  userHome?: string
}
/** The agents to run, by name. `{}` means "all defaults"; a left-out agent is not available. */
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
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new TypeError(`supermux ${label} must be an object`)
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) if (value !== undefined) out[key] = value
  return out as Partial<T>
}

function keeperOf(stateDirectory: string, base: Readonly<KeeperLimits>, input: Partial<KeeperLimits> | undefined, label: string) {
  return { stateDirectory, limits: { ...base, ...defined(input, label) } }
}

function acpPermissions(policy: AcpPermissions["policy"], nativeMode: string | null, askKinds: ToolKind[] | undefined): AcpPermissions {
  // An array is copied; anything else is passed on for the factory's validator to refuse.
  return { kind: "acp", policy, nativeMode, ...(askKinds === undefined ? {} : { askKinds: Array.isArray(askKinds) ? [...askKinds] : askKinds }) }
}

// Each builder merges the input over its agent's defaults (arrays copied out of the frozen
// defaults), then maps the flat permission fields to the factory's permission spec.

function buildClaude(dir: string, input: ClaudeInput | undefined): AgentDriver {
  const { keeper, ...given } = defined(input, "agents.claude")
  const { keeper: limits, permissionMode, ...options } = { ...defaults.claude, args: [...defaults.claude.args], ...given }
  return claude({
    ...options, id: "claude",
    permissions: { kind: "claude", permissionMode },
    keeper: keeperOf(dir, limits, keeper, "agents.claude.keeper"),
  })
}

function buildCodex(dir: string, input: CodexInput | undefined): AgentDriver {
  const { keeper, ...given } = defined(input, "agents.codex")
  const { keeper: limits, ...options } = { ...defaults.codex, args: [...defaults.codex.args], ...given }
  return codex({
    ...options, id: "codex",
    permissions: { kind: "codex", sandbox: options.sandbox, approvalPolicy: options.approvalPolicy },
    keeper: keeperOf(dir, limits, keeper, "agents.codex.keeper"),
  })
}

function buildGrok(dir: string, input: GrokInput | undefined): AgentDriver {
  const { keeper, ...given } = defined(input, "agents.grok")
  const { keeper: limits, policy, nativeMode, askKinds, ...options } = {
    ...defaults.grok, commandArgs: [...defaults.grok.commandArgs], mcpServers: [...defaults.grok.mcpServers], ...given,
  }
  return grok({
    ...options, id: "grok",
    permissions: acpPermissions(policy, nativeMode, askKinds),
    keeper: keeperOf(dir, limits, keeper, "agents.grok.keeper"),
  })
}

function buildOpenCode(dir: string, input: OpenCodeInput | undefined): AgentDriver {
  const { keeper, ...given } = defined(input, "agents.opencode")
  const { keeper: limits, policy, nativeMode, askKinds, ...options } = { ...defaults.opencode, mcpServers: [...defaults.opencode.mcpServers], ...given }
  return opencode({
    ...options, id: "opencode",
    permissions: acpPermissions(policy, nativeMode, askKinds),
    keeper: keeperOf(dir, limits, keeper, "agents.opencode.keeper"),
  })
}

function buildCursor(dir: string, input: CursorInput | undefined): AgentDriver {
  const { keeper, ...given } = defined(input, "agents.cursor")
  const { keeper: limits, policy, askKinds, ...options } = {
    ...defaults.cursor, commandArgs: [...defaults.cursor.commandArgs], mcpServers: [...defaults.cursor.mcpServers], ...given,
  }
  return cursor({
    ...options, id: "cursor",
    permissions: acpPermissions(policy, options.mode, askKinds),
    keeper: keeperOf(dir, limits, keeper, "agents.cursor.keeper"),
  })
}

function buildAntigravity(dir: string, input: AntigravityInput | undefined): AgentDriver {
  const { keeper, home, userHome, ...given } = defined(input, "agents.antigravity")
  if (typeof home !== "string" || !home) {
    throw new TypeError("supermux: antigravity home is required (a prepared shadow HOME, see prepareAntigravityEnvironment)")
  }
  const { keeper: limits, mode, ...options } = { ...defaults.antigravity, args: [...defaults.antigravity.args], ...given }
  return antigravity({
    ...options, id: "antigravity",
    permissions: { kind: "antigravity", mode },
    home: { shadow: home, user: userHome === undefined ? homedir() : userHome },
    keeper: keeperOf(dir, limits, keeper, "agents.antigravity.keeper"),
  })
}

const builders: Record<keyof SupermuxAgents, (dir: string, input: never) => AgentDriver> = {
  claude: buildClaude, codex: buildCodex, grok: buildGrok, opencode: buildOpenCode, cursor: buildCursor, antigravity: buildAntigravity,
}

/**
 * createCore() with every missing field filled from `defaults`. Agents are named by key; `{}` means
 * "all defaults". The state directory also holds each session's keeper. For a custom driver, use
 * createCore() directly.
 */
export function supermux(options: SupermuxOptions): Core {
  if (!options || typeof options !== "object") throw new TypeError("supermux options are required")
  const { agents, limits, stateDirectory, ...rest } = options
  if (typeof stateDirectory !== "string" || !stateDirectory) throw new TypeError("supermux stateDirectory is required")
  if (!agents || typeof agents !== "object" || Array.isArray(agents)) throw new TypeError("supermux agents is required")
  const dir = resolve(stateDirectory)
  const drivers: AgentDriver[] = []
  for (const [name, input] of Object.entries(agents)) {
    if (input === undefined) continue
    if (!Object.hasOwn(builders, name)) throw new TypeError(`supermux: unknown agent ${name} (expected one of ${Object.keys(builders).join(", ")})`)
    drivers.push(builders[name as keyof SupermuxAgents](dir, input as never))
  }
  return createCore({ ...rest, stateDirectory: dir, agents: drivers, limits: { ...defaults.core.limits, ...defined(limits, "limits") } })
}
