import type { AgentKind } from "../../shared/agents"
import { AGENT_KINDS, isAgentKind } from "../../shared/agents"
import type { ClaudeOptions } from "../../../packages/supermux-core/src/claude/index.js"
import type { CodexApprovalPolicy, CodexPermissionPrompts, CodexSandbox } from "../../../packages/supermux-core/src/codex/index.js"
import type { OpenCodeToolPermissions } from "../../../packages/supermux-core/src/environment/types.js"

export type PermissionModeEntry = {
  id: string
  label: string
  description: string
  default?: true
}

export type ClaudeDriverSettings = {
  agent: "claude"
  permissionMode: ClaudeOptions["permissionMode"]
  permissionPrompts: "host"
}

export type CodexDriverSettings = {
  agent: "codex"
  approvalPolicy: CodexApprovalPolicy
  sandbox: CodexSandbox
  permissionPrompts: CodexPermissionPrompts
}

export type GrokDriverSettings = {
  agent: "grok"
  alwaysApprove: boolean
}

export type OpenCodeDriverSettings = {
  agent: "opencode"
  permissions: OpenCodeToolPermissions
}

export type CursorDriverSettings = {
  agent: "cursor"
  permissions: "force" | "auto-review" | "ask"
  mode: "agent" | "plan" | "ask"
}

export type DriverSettings =
  | ClaudeDriverSettings
  | CodexDriverSettings
  | GrokDriverSettings
  | OpenCodeDriverSettings
  | CursorDriverSettings

const CLAUDE: PermissionModeEntry[] = [
  { id: "bypass", label: "Bypass", description: "Skip tool-call approval (Claude bypassPermissions).", default: true },
  { id: "accept-edits", label: "Accept edits", description: "Auto-accept file edits; ask for other tools." },
  { id: "ask", label: "Ask", description: "Ask before tool calls (Claude's default asking)." },
  { id: "plan", label: "Plan", description: "Plan mode: analyze and propose, no edits." },
  { id: "auto", label: "Auto", description: "Classifier auto-approves routine calls; the rest are asked." },
  { id: "dont-ask", label: "Don't ask", description: "Refuse instead of asking (dontAsk)." },
]

const CODEX: PermissionModeEntry[] = [
  { id: "never+full-access", label: "Never + full access", description: "Never ask; danger-full-access sandbox.", default: true },
  { id: "on-request+workspace-write", label: "On request + workspace write", description: "Ask on request; workspace-write sandbox." },
  { id: "untrusted+read-only", label: "Untrusted + read-only", description: "Untrusted policy; read-only sandbox." },
]

const GROK: PermissionModeEntry[] = [
  { id: "always-approve", label: "Always approve", description: "Auto-approve tool calls.", default: true },
  { id: "ask", label: "Ask", description: "Ask before tool calls." },
]

const OPENCODE: PermissionModeEntry[] = [
  { id: "allow", label: "Allow", description: "Allow edit, bash, and webfetch.", default: true },
  { id: "ask", label: "Ask", description: "Ask for edit, bash, and webfetch." },
  { id: "ask-bash", label: "Ask bash", description: "Ask for bash; allow edit and webfetch." },
  { id: "read-only", label: "Read only", description: "Deny edit and bash; allow webfetch." },
]

const CURSOR: PermissionModeEntry[] = [
  { id: "force", label: "Force", description: "Force-allow tools in agent mode.", default: true },
  { id: "auto-review", label: "Auto-review", description: "Classifier auto-runs safe calls; the rest are asked." },
  { id: "ask", label: "Ask", description: "Ask before tool calls in agent mode." },
  { id: "plan", label: "Plan", description: "Plan mode: refuse edits." },
  { id: "qa", label: "Q&A", description: "Ask mode: explanations only." },
]

const CATALOG: Record<AgentKind, PermissionModeEntry[]> = {
  claude: CLAUDE,
  codex: CODEX,
  grok: GROK,
  opencode: OPENCODE,
  cursor: CURSOR,
}

const ASK_IDS: Record<AgentKind, string> = {
  claude: "ask",
  grok: "ask",
  opencode: "ask",
  cursor: "ask",
  codex: "on-request+workspace-write",
}

export function permissionCatalog(): Record<AgentKind, PermissionModeEntry[]> {
  return CATALOG
}

export function modesFor(agent: AgentKind): PermissionModeEntry[] {
  return CATALOG[agent]
}

export function defaultPermissionMode(agent: AgentKind): string {
  const found = CATALOG[agent].find((m) => m.default === true)
  if (!found) throw new Error(`permission catalog missing default for ${agent}`)
  return found.id
}

export function askPermissionMode(agent: AgentKind): string {
  return ASK_IDS[agent]
}

export function isPermissionMode(agent: AgentKind, id: string): boolean {
  return CATALOG[agent].some((m) => m.id === id)
}

export function resolvePermissionMode(agent: AgentKind, id: string | null | undefined): string {
  if (typeof id === "string" && isPermissionMode(agent, id)) return id
  return defaultPermissionMode(agent)
}

export function formatModesList(agent: AgentKind, currentId: string): string {
  const lines = [`Permissions for [${agent}] (current: ${currentId}):`]
  for (const m of CATALOG[agent]) {
    const marker = m.id === currentId ? "●" : " "
    const def = m.default ? " (default)" : ""
    lines.push(`${marker} ${m.id}${def} — ${m.description}`)
  }
  return lines.join("\n")
}

export function driverSettingsFor(agent: AgentKind, id: string): DriverSettings {
  const resolved = resolvePermissionMode(agent, id)
  if (resolved !== id) throw new Error(`unknown permission mode ${id} for ${agent}`)
  if (agent === "claude") {
    const permissionMode: ClaudeOptions["permissionMode"] =
      id === "bypass" ? "bypassPermissions"
      : id === "accept-edits" ? "acceptEdits"
      : id === "ask" ? undefined
      : id === "plan" ? "plan"
      : id === "auto" ? "auto"
      : "dontAsk"
    return { agent, permissionMode, permissionPrompts: "host" }
  }
  if (agent === "codex") {
    if (id === "never+full-access") {
      return { agent, approvalPolicy: "never", sandbox: "danger-full-access", permissionPrompts: "none" }
    }
    if (id === "on-request+workspace-write") {
      return { agent, approvalPolicy: "on-request", sandbox: "workspace-write", permissionPrompts: "host" }
    }
    return { agent, approvalPolicy: "untrusted", sandbox: "read-only", permissionPrompts: "host" }
  }
  if (agent === "grok") {
    return { agent, alwaysApprove: id === "always-approve" }
  }
  if (agent === "opencode") {
    if (id === "ask") return { agent, permissions: { edit: "ask", bash: "ask", webfetch: "ask" } }
    if (id === "ask-bash") return { agent, permissions: { edit: "allow", bash: "ask", webfetch: "allow" } }
    if (id === "read-only") return { agent, permissions: { edit: "deny", bash: "deny", webfetch: "allow" } }
    return { agent, permissions: { edit: "allow", bash: "allow", webfetch: "allow" } }
  }
  if (id === "force") return { agent: "cursor", permissions: "force", mode: "agent" }
  if (id === "auto-review") return { agent: "cursor", permissions: "auto-review", mode: "agent" }
  if (id === "ask") return { agent: "cursor", permissions: "ask", mode: "agent" }
  if (id === "plan") return { agent: "cursor", permissions: "ask", mode: "plan" }
  return { agent: "cursor", permissions: "ask", mode: "ask" }
}

export function extraPermissionMode(extra: unknown, agent: AgentKind): string {
  if (!extra || typeof extra !== "object") return defaultPermissionMode(agent)
  const raw = (extra as { permissionMode?: unknown }).permissionMode
  return resolvePermissionMode(agent, typeof raw === "string" ? raw : undefined)
}

export function catalogAgents(): readonly AgentKind[] {
  return AGENT_KINDS
}

export { isAgentKind }
