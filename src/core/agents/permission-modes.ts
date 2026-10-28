import type { AgentKind } from "../../shared/agents"
import { AGENT_KINDS, isAgentKind } from "../../shared/agents"
import { SESSION_INBOX_DIR } from "../../shared/paths"
import type { OpenCodeToolPermissions } from "../../../packages/supermux-core/src/environment/types.js"
import type { PermissionsSpec, ToolKind } from "../../../packages/supermux-core/src/types.js"

export type PermissionModeEntry = {
  id: string
  label: string
  description: string
  default?: true
}

const ALL_ASK: OpenCodeToolPermissions = { edit: "ask", bash: "ask", webfetch: "ask" }

/** Folders an ACP agent under an "ask" policy may read without a prompt (the session inboxes). */
const INBOX_ROOTS = [SESSION_INBOX_DIR]

export type DriverSettings = {
  initial: PermissionsSpec
  permissionPrompts: "host"
  environmentPermissions?: OpenCodeToolPermissions
}

const CLAUDE: PermissionModeEntry[] = [
  { id: "bypass", label: "Bypass", description: "Skip tool-call approval (Claude bypassPermissions).", default: true },
  { id: "accept-edits", label: "Accept edits", description: "Auto-accept file edits; ask for other tools." },
  { id: "ask", label: "Ask", description: "Ask before tool calls (Claude's default asking)." },
  { id: "plan", label: "Plan", description: "Plan mode: analyze and propose, no edits." },
  { id: "auto", label: "Auto", description: "Classifier auto-approves routine calls; the rest are asked." },
  { id: "dont-ask", label: "Don't ask", description: "Refuse instead of asking (dontAsk)." },
]

const CODEX: PermissionModeEntry[] = [
  { id: "full-access", label: "Full access", description: "Codex's Full Access preset: never asks, no sandbox.", default: true },
  { id: "auto", label: "Auto", description: "Codex's Auto preset: works in the workspace without asking; asks only to leave it (network, other paths)." },
  { id: "ask", label: "Ask", description: "Approve each command before it runs (trusted read-only commands excepted); workspace-write sandbox." },
  { id: "read-only", label: "Read only", description: "Codex's Read Only preset: no edits, no commands with side effects; asks to escalate." },
]

const GROK: PermissionModeEntry[] = [
  { id: "always-approve", label: "Always approve", description: "Auto-approve tool calls.", default: true },
  { id: "ask", label: "Ask", description: "Ask before tool calls." },
]

const ANTIGRAVITY: PermissionModeEntry[] = [
  { id: "bypass", label: "Bypass", description: "Skip all permission checks (agy --dangerously-skip-permissions).", default: true },
  { id: "accept-edits", label: "Accept edits", description: "Auto-accept file edits; other gated tools are auto-denied (agy --mode accept-edits)." },
  { id: "plan", label: "Plan", description: "Plan mode: analyze and propose, no edits (agy --mode plan)." },
  { id: "default", label: "Default", description: "agy's own rules; headless agy cannot ask, so gated tools are auto-denied and reported." },
]

const OPENCODE: PermissionModeEntry[] = [
  { id: "allow", label: "Allow", description: "Allow edit, bash, and webfetch via the live policy (environment always asks).", default: true },
  { id: "ask", label: "Ask", description: "Ask for edit, bash, and webfetch." },
  { id: "ask-bash", label: "Ask bash", description: "Ask only for execute kinds; auto-approve the rest." },
  { id: "read-only", label: "Read only", description: "Read-only policy plus OpenCode native plan mode." },
]

const CURSOR: PermissionModeEntry[] = [
  { id: "force", label: "Force", description: "Auto-approve tools in agent mode.", default: true },
  { id: "auto-review", label: "Auto-review", description: "Auto-approve over ACP. Cursor's classifier is unavailable over ACP; the catalog id is kept." },
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
  antigravity: ANTIGRAVITY,
}

const ASK_IDS: Record<AgentKind, string> = {
  claude: "ask",
  grok: "ask",
  opencode: "ask",
  cursor: "ask",
  codex: "ask",
  antigravity: "default", // no ask mode: headless agy cannot prompt
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

export function permissionsFor(agent: AgentKind, id: string): PermissionsSpec {
  return driverSettingsFor(agent, id).initial
}

export function driverSettingsFor(agent: AgentKind, id: string): DriverSettings {
  const resolved = resolvePermissionMode(agent, id)
  if (resolved !== id) throw new Error(`unknown permission mode ${id} for ${agent}`)
  if (agent === "claude") {
    const permissionMode: Extract<PermissionsSpec, { kind: "claude" }>["permissionMode"] =
      id === "bypass" ? "bypassPermissions"
      : id === "accept-edits" ? "acceptEdits"
      : id === "ask" ? "default"
      : id === "plan" ? "plan"
      : id === "auto" ? "auto"
      : "dontAsk"
    return { initial: { kind: "claude", permissionMode }, permissionPrompts: "host" }
  }
  if (agent === "codex") {
    if (id === "full-access") {
      return { initial: { kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" }, permissionPrompts: "host" }
    }
    if (id === "auto") {
      return { initial: { kind: "codex", approvalPolicy: "on-request", sandbox: "workspace-write" }, permissionPrompts: "host" }
    }
    if (id === "ask") {
      return { initial: { kind: "codex", approvalPolicy: "untrusted", sandbox: "workspace-write" }, permissionPrompts: "host" }
    }
    return { initial: { kind: "codex", approvalPolicy: "on-request", sandbox: "read-only" }, permissionPrompts: "host" }
  }
  if (agent === "grok") {
    return {
      initial: id === "always-approve"
        ? { kind: "acp", policy: "auto-approve", nativeMode: null }
        : { kind: "acp", policy: "ask", nativeMode: null, readableRoots: INBOX_ROOTS },
      permissionPrompts: "host",
    }
  }
  if (agent === "antigravity") {
    const mode = id === "bypass" ? "bypass" : id === "accept-edits" ? "acceptEdits" : id === "plan" ? "plan" : "default"
    return { initial: { kind: "antigravity", mode }, permissionPrompts: "host" }
  }
  if (agent === "opencode") {
    const askKinds: ToolKind[] = ["execute"]
    const initial: PermissionsSpec =
      id === "allow" ? { kind: "acp", policy: "auto-approve", nativeMode: null }
      : id === "ask" ? { kind: "acp", policy: "ask", nativeMode: null, readableRoots: INBOX_ROOTS }
      : id === "ask-bash" ? { kind: "acp", policy: "ask", nativeMode: null, askKinds, readableRoots: INBOX_ROOTS }
      : { kind: "acp", policy: "read-only", nativeMode: "plan" }
    return { initial, permissionPrompts: "host", environmentPermissions: ALL_ASK }
  }
  const initial: PermissionsSpec =
    id === "force" ? { kind: "acp", policy: "auto-approve", nativeMode: "agent" }
    : id === "auto-review" ? { kind: "acp", policy: "auto-approve", nativeMode: null }
    : id === "ask" ? { kind: "acp", policy: "ask", nativeMode: "agent", readableRoots: INBOX_ROOTS }
    : id === "plan" ? { kind: "acp", policy: "ask", nativeMode: "plan", readableRoots: INBOX_ROOTS }
    : { kind: "acp", policy: "ask", nativeMode: "ask", readableRoots: INBOX_ROOTS }
  return { initial, permissionPrompts: "host" }
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
