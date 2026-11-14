import { randomBytes } from "crypto"
import { Registry } from "./session-manager/registry"
import { isPersistentRuntimeSession } from "./session-manager/types"
import type { MessageStore } from "./session-manager/messages"
import { fetchAllUsage, type UsageResponse } from "./usage/index"
import { formatUsageTelegram } from "./usage/format"
import { getUsageStore } from "./usage/store"
import { AGENT_KINDS, AgentKind, isAgentKind, spawnCommandForAgent } from "../shared/agents"
import { formatModesList, isPermissionMode, resolvePermissionMode } from "./agents/permission-modes"
import { buildProxyPublicUrl } from "../channels/web/proxy"
import { brokerLang, t, type MessageKey } from "./i18n/messages"

export type CommandCtx = {
  registry: Registry
  messageLog: MessageStore
  chat_id: string
  fromSession?: string  // for orchestration tool-calls from a session; chat-initiated leaves this undefined
  spawnSession: (workdir: string, name?: string, agent?: AgentKind, model?: string, reasoningLevel?: string, permissionMode?: string) => Promise<{ name: string; session_id: string }>
  /** Kill and archive a session, its children included (SessionManager.killAndArchive). */
  killSession: (id: string) => Promise<void>
  refreshMenu: () => Promise<void>
  listModels?: (agent: AgentKind) => { id: string; displayName: string }[]
  switchModel?: (sessionId: string, model: string) => Promise<{ ok: true } | { ok: false; error: string }>
  switchReasoningLevel?: (sessionId: string, level: string) => Promise<{ ok: true } | { ok: false; error: string }>
  switchPermissionMode?: (sessionId: string, mode: string) => Promise<{ ok: true; applied?: "now" | "next-turn" } | { ok: false; error: string }>
  listReasoningLevels?: (agent: AgentKind, model?: string) => { id: string; description?: string }[]
  resolveReasoningLevel?: (sessionId: string) => string | undefined
  switchFastMode?: (sessionId: string, fast: boolean) => Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }>
  /** Fast mode a session runs with now, and whether its model offers it. */
  fastModeState?: (sessionId: string) => { on: boolean; supported: boolean }
  proxyBaseDomain?: string
  proxyPublicUrl?: string
  resumeFromArchive?: (id: string) => Promise<{ ok: boolean; name?: string; error?: string }>
  /** Soft-interrupt a running session: stop the current turn, keep it alive. */
  interrupt?: (sessionId: string) => Promise<{ ok: boolean; reason?: string }>
  /** The one Agent create path (createAgent in session-manager/create-agent). */
  createAgent?: (args: { name: string; provider?: AgentKind; model?: string }) => Promise<{ id: string; name: string; workdir: string; provider: AgentKind }>
  /** Live provider fetch behind `/usage`. Injectable so tests need no network. */
  fetchUsage?: () => Promise<UsageResponse>
  /** #123: the sender's language tag (Telegram's language_code); replies are worded in it. Absent = English. */
  lang?: string
}

/** A reply's words in the sender's language. Names, commands, models and other systems' errors go in as they are. */
function say(ctx: CommandCtx, key: MessageKey, vars?: Record<string, string | number>): string {
  return t(brokerLang(ctx.lang), key, vars)
}

export type SlashInput = { command: string; rest: string }
export type SlashReply = { text: string; keyboard?: { text: string; callback_data: string }[][]; parse_mode?: string }

// Spawn command → agent kind, generated from AGENT_KINDS so a new kind can
// never be forgotten here. Claude is the default agent and keeps the bare
// `spawn` command; every other kind gets a `spawn_<kind>` alias.
const SPAWN_COMMAND_AGENTS: ReadonlyMap<string, AgentKind> = new Map(
  AGENT_KINDS.map(kind => [spawnCommandForAgent(kind), kind]),
)

export async function handleSlash(input: SlashInput, ctx: CommandCtx): Promise<SlashReply> {
  const spawnAgent = SPAWN_COMMAND_AGENTS.get(input.command)
  if (spawnAgent !== undefined) {
    // Bare /spawn passes rest through untouched, so an explicit --agent flag
    // keeps working. Each alias appends its own --agent — the same wire
    // format the hard-coded cases used.
    return cmdSpawn(spawnAgent === AgentKind.Claude ? input.rest : `${input.rest} --agent ${spawnAgent}`, ctx)
  }
  switch (input.command) {
    case "sessions":          return cmdSessions(ctx)
    case "active":            return cmdActive(ctx)
    case "switch":            return cmdSwitch(input.rest, ctx)
    case "stop":              return cmdStop(input.rest, ctx)
    case "kill":              return cmdKill(input.rest, ctx)
    case "rename":            return cmdRename(input.rest, ctx)
    case "mute":              return cmdMute(input.rest, ctx, true)
    case "unmute":            return cmdMute(input.rest, ctx, false)
    case "show":              return cmdShow(input.rest, ctx)
    case "grant_orchestrate": return cmdGrantOrch(input.rest, ctx)
    case "model":             return cmdModel(input.rest, ctx)
    case "effort":            return cmdEffort(input.rest, ctx)
    case "fast":              return cmdFast(input.rest, ctx)
    case "permissions":       return cmdPermissions(input.rest, ctx)
    case "usage":            return cmdUsage(ctx)
    case "proxy":             return cmdProxy(input.rest, ctx)
    case "unproxy":           return cmdUnproxy(input.rest, ctx)
    case "proxies":           return cmdProxies(ctx)
    case "archive":           return cmdArchive(ctx)
    case "resume":            return cmdResume(input.rest, ctx)
    case "spawnpa": case "agent":   return await cmdCreateAgent(input.rest, ctx)
    case "pas": case "agents":      return cmdListAgents(ctx)
    case "default":                 return cmdDefault(input.rest, ctx)
    default:                  return { text: say(ctx, "cmd.unknown_command", { command: input.command }) }
  }
}

function cmdSessions(ctx: CommandCtx): SlashReply {
  const sessions = ctx.registry.listVisible()
  if (sessions.length === 0) return { text: say(ctx, "cmd.no_sessions") }
  const activeId = ctx.registry.getActive(ctx.chat_id)
  const lines = sessions.map(s => {
    const tag = s.id === activeId ? "●" : " "
    const muted = s.mute ? " 🔇" : ""
    // `connected` mirrors the claude shim socket; for adapter-driven kinds it is
    // not a liveness signal, so only persistent-runtime (claude) sessions get
    // the reconnecting tag.
    const status = !isPersistentRuntimeSession(s) || s.connected ? "" : ` · ${say(ctx, "cmd.reconnecting")}`
    const modelSuffix = s.model ? `:${s.model}` : ""
    const agentTag = `[${s.agent}${modelSuffix}]`
    return `${tag} ${s.name} ${agentTag}${muted}${status}  ${s.workdir}`
  })
  const keyboard = sessions.map(s => [
    { text: say(ctx, "cmd.button_switch"), callback_data: `switch:${s.id}` },
    { text: say(ctx, "cmd.button_rename"), callback_data: `rename:${s.id}` },
    { text: say(ctx, s.mute ? "cmd.button_unmute" : "cmd.button_mute"), callback_data: s.mute ? `unmute:${s.id}` : `mute:${s.id}` },
    { text: say(ctx, "cmd.button_kill"), callback_data: `kill:${s.id}` },
  ])
  return { text: lines.join("\n"), keyboard, parse_mode: "HTML" }
}

function cmdActive(ctx: CommandCtx): SlashReply {
  const a = ctx.registry.getActive(ctx.chat_id)
  const name = a ? (ctx.registry.get(a)?.name ?? a) : undefined
  return { text: name ? say(ctx, "cmd.active", { name }) : say(ctx, "cmd.no_active_session") }
}

function cmdSwitch(rest: string, ctx: CommandCtx): SlashReply {
  const input = rest.trim()
  const session = ctx.registry.get(input) ?? ctx.registry.resolveName(input)
  if (!session) return { text: say(ctx, "cmd.no_such_session", { name: input }) }
  ctx.registry.setActive(ctx.chat_id, session.id)
  return { text: say(ctx, "cmd.switched_to", { name: session.name }) }
}

async function cmdSpawn(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  let agent: AgentKind = AgentKind.Claude
  let model: string | undefined
  let cleaned = rest
  const agentMatch = rest.match(/--agent\s+(\S+)/)
  if (agentMatch) {
    const requested = agentMatch[1]!
    if (!isAgentKind(requested)) {
      return { text: say(ctx, "cmd.unknown_agent", { agent: requested, kinds: AGENT_KINDS.join(", ") }) }
    }
    agent = requested
    cleaned = (rest.slice(0, agentMatch.index) + rest.slice(agentMatch.index! + agentMatch[0].length)).replace(/\s+/g, " ").trim()
  }
  const modelMatch = cleaned.match(/--model\s+(\S+)/)
  if (modelMatch) {
    model = modelMatch[1]!
    cleaned = (cleaned.slice(0, modelMatch.index) + cleaned.slice(modelMatch.index! + modelMatch[0].length)).replace(/\s+/g, " ").trim()
  }
  let reasoningLevel: string | undefined
  const effortMatch = cleaned.match(/--effort\s+(\S+)/)
  if (effortMatch) {
    reasoningLevel = effortMatch[1]!
    cleaned = (cleaned.slice(0, effortMatch.index) + cleaned.slice(effortMatch.index! + effortMatch[0].length)).replace(/\s+/g, " ").trim()
  }
  let permissionMode: string | undefined
  const permMatch = cleaned.match(/--permissions\s+(\S+)/)
  if (permMatch) {
    permissionMode = permMatch[1]!
    cleaned = (cleaned.slice(0, permMatch.index) + cleaned.slice(permMatch.index! + permMatch[0].length)).replace(/\s+/g, " ").trim()
    if (!isPermissionMode(agent, permissionMode)) {
      return { text: say(ctx, "cmd.unknown_permission_mode", { mode: permissionMode }) }
    }
  }
  const asMatch = cleaned.match(/^(\S+)\s+as\s+(\S+)$/)
  const workdir = asMatch ? asMatch[1]! : cleaned.trim()
  const name = asMatch ? asMatch[2]!.trim().slice(0, 80) : undefined
  if (!workdir) return { text: say(ctx, "cmd.spawn_usage", { kinds: AGENT_KINDS.join("|") }) }
  try {
    const result = await ctx.spawnSession(workdir, name, agent, model, reasoningLevel, permissionMode)
    await ctx.refreshMenu()
    const parts: string[] = [agent]
    if (model) parts.push(model)
    if (reasoningLevel) parts.push(`effort=${reasoningLevel}`)
    if (permissionMode) parts.push(`permissions=${permissionMode}`)
    const tag = `[${parts.join(":")}]`
    return { text: say(ctx, "cmd.spawned", { name: result.name, tag, workdir }) }
  } catch (err: any) {
    return { text: say(ctx, "cmd.spawn_failed", { error: err?.message ?? String(err) }) }
  }
}

async function cmdCreateAgent(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  if (!ctx.createAgent) return { text: say(ctx, "cmd.create_agent_unavailable") }
  const usage = say(ctx, "cmd.agent_usage", { kinds: AGENT_KINDS.join("|") })
  let cleaned = rest.trim()
  if (!cleaned) return { text: usage }

  // --provider is the new flag; --agent stays for old habits (/spawnpa x --agent codex).
  let provider: AgentKind | undefined
  const providerMatch = cleaned.match(/--(?:provider|agent)\s+(\S+)/)
  if (providerMatch) {
    const requested = providerMatch[1]!
    if (!isAgentKind(requested)) {
      return { text: say(ctx, "cmd.unknown_provider", { provider: requested, kinds: AGENT_KINDS.join(", ") }) }
    }
    provider = requested
    cleaned = (cleaned.slice(0, providerMatch.index) + cleaned.slice(providerMatch.index! + providerMatch[0].length)).replace(/\s+/g, " ").trim()
  }

  let model: string | undefined
  const modelMatch = cleaned.match(/--model\s+(\S+)/)
  if (modelMatch) {
    model = modelMatch[1]!
    cleaned = (cleaned.slice(0, modelMatch.index) + cleaned.slice(modelMatch.index! + modelMatch[0].length)).replace(/\s+/g, " ").trim()
  }

  const name = cleaned.trim()
  if (!name) return { text: usage }

  if (ctx.registry.resolveName(name)) {
    return { text: say(ctx, "cmd.name_in_use", { name }) }
  }

  try {
    const result = await ctx.createAgent({ name, provider, model })
    const parts: string[] = [result.provider]
    if (model) parts.push(model)
    return { text: say(ctx, "cmd.created_agent", { name: result.name, tag: `[${parts.join(":")}]` }) }
  } catch (err: any) {
    return { text: say(ctx, "cmd.create_failed", { error: err?.message ?? String(err) }) }
  }
}

function cmdDefault(rest: string, ctx: CommandCtx): SlashReply {
  const name = rest.trim()
  if (!name) return { text: say(ctx, "cmd.default_usage") }
  const s = ctx.registry.resolveName(name)
  if (!s) return { text: say(ctx, "cmd.no_such_session", { name }) }
  if (!ctx.registry.setDefaultAgent(s.id)) return { text: say(ctx, "cmd.not_an_agent", { name }) }
  return { text: say(ctx, "cmd.now_default", { name }) }
}

function cmdListAgents(ctx: CommandCtx): SlashReply {
  const pas = ctx.registry.listPAs()
  if (pas.length === 0) return { text: say(ctx, "cmd.no_agents") }
  const lines = pas.map(s => {
    const star = s.is_default ? "*" : " "
    const modelSuffix = s.model ? `:${s.model}` : ""
    const agentTag = `[${s.agent}${modelSuffix}]`
    // Same claude-only caveat as cmdSessions: the connected flag means nothing
    // for adapter-driven PAs, so they get no connection label.
    const status = isPersistentRuntimeSession(s) ? say(ctx, s.connected ? "cmd.connected" : "cmd.reconnecting") : ""
    return `${star} ${s.name} ${agentTag}  ${s.workdir}${status ? `  ${status}` : ""}`
  })
  return { text: lines.join("\n") }
}

async function cmdKill(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const m = rest.match(/^(\S+)(?:\s+yes)?$/)
  if (!m) return { text: say(ctx, "cmd.kill_usage") }
  const name = m[1]!
  const confirmed = /\byes\b/.test(rest)
  const target = ctx.registry.resolveName(name)
  if (!target) return { text: say(ctx, "cmd.no_such_session", { name }) }
  if (!confirmed) return { text: say(ctx, "cmd.kill_confirm", { name }) }
  await ctx.killSession(target.id)   // kills and archives it, its children included
  await ctx.refreshMenu()
  // Apply fallback rule if killed was the active for this chat
  const active = ctx.registry.getActive(ctx.chat_id)
  if (!active) {
    const fb = ctx.registry.activeFallback(ctx.chat_id)
    if (fb) ctx.registry.setActive(ctx.chat_id, fb)
  }
  return { text: say(ctx, "cmd.killed", { name }) }
}

async function cmdStop(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const input = rest.trim()
  let sessionId: string | undefined
  if (input) {
    const s = ctx.registry.resolveName(input)
    if (!s) return { text: say(ctx, "cmd.no_such_session", { name: input }) }
    sessionId = s.id
  } else {
    sessionId = ctx.registry.getActive(ctx.chat_id)
  }
  if (!sessionId) return { text: say(ctx, "cmd.no_active_session") }
  if (!ctx.interrupt) return { text: say(ctx, "cmd.stop_unavailable") }
  const r = await ctx.interrupt(sessionId)
  const s = ctx.registry.get(sessionId)
  const displayName = s?.name ?? sessionId
  return { text: r.ok ? say(ctx, "cmd.stopped", { name: displayName }) : say(ctx, "cmd.stop_failed", { name: displayName, reason: r.reason ?? say(ctx, "cmd.unknown_reason") }) }
}

async function cmdRename(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.split(/\s+/)
  const oldName = parts[0]
  const rawName = parts[1]
  if (!oldName || !rawName || parts.length !== 2) return { text: say(ctx, "cmd.rename_usage") }
  const session = ctx.registry.resolveName(oldName)
  if (!session) return { text: say(ctx, "cmd.no_such_session", { name: oldName }) }
  const newName = rawName.trim().slice(0, 80)
  if (!newName) return { text: say(ctx, "cmd.name_empty") }
  ctx.registry.rename(session.id, newName)
  await ctx.refreshMenu()
  return { text: `${oldName} → ${newName}` }
}

function cmdMute(rest: string, ctx: CommandCtx, muted: boolean): SlashReply {
  const name = rest.trim()
  const session = ctx.registry.resolveName(name)
  if (!session) return { text: say(ctx, "cmd.no_such_session", { name }) }
  ctx.registry.setMuted(session.id, muted)
  return { text: say(ctx, muted ? "cmd.muted" : "cmd.unmuted", { name }) }
}

function cmdGrantOrch(rest: string, ctx: CommandCtx): SlashReply {
  const name = rest.trim()
  const session = ctx.registry.resolveName(name)
  if (!session) return { text: say(ctx, "cmd.no_such_session", { name }) }
  ctx.registry.grantOrchestrate(session.id, true)
  return { text: `${name}: can_orchestrate = true` }
}

async function cmdUsage(ctx: CommandCtx): Promise<SlashReply> {
  const data = await (ctx.fetchUsage ?? fetchAllUsage)()
  getUsageStore().applyResponse(data)
  return { text: formatUsageTelegram(data) }
}

async function cmdModel(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/).filter(Boolean)

  // /model (no args) — show current model for active session
  if (parts.length === 0) {
    const activeId = ctx.registry.getActive(ctx.chat_id)
    if (!activeId) return { text: say(ctx, "cmd.no_active_session") }
    const session = ctx.registry.get(activeId)
    if (!session) return { text: say(ctx, "cmd.no_active_session") }
    const current = session.model ?? say(ctx, "cmd.default_model")
    const available = ctx.listModels?.(session.agent) ?? []
    if (available.length > 0) {
      const lines = [say(ctx, "cmd.models_for", { name: session.name, agent: session.agent })]
      for (const m of available) {
        const marker = m.id === session.model ? "●" : " "
        lines.push(`${marker} ${m.id}`)
      }
      return { text: lines.join("\n") }
    }
    return { text: say(ctx, "cmd.current_model", { name: session.name, agent: session.agent, model: current }) }
  }

  // /model <session> <name> — switch specific session (try UUID first, then name)
  const sessionByName = ctx.registry.get(parts[0]!) ?? ctx.registry.resolveName(parts[0]!)
  if (parts.length === 2 && sessionByName) {
    const modelName = parts[1]!
    if (ctx.switchModel) {
      const result = await ctx.switchModel(sessionByName.id, modelName)
      if (!result.ok) return { text: say(ctx, "cmd.model_switch_failed", { error: result.error }) }
      return { text: say(ctx, "cmd.model_switched", { name: sessionByName.name, model: modelName }) }
    }
    ctx.registry.setModel(sessionByName.id, modelName)
    return { text: say(ctx, "cmd.model_set", { name: sessionByName.name, model: modelName }) }
  }

  // /model <name> — switch active session
  const activeId = ctx.registry.getActive(ctx.chat_id)
  if (!activeId) return { text: say(ctx, "cmd.no_active_session") }
  const session = ctx.registry.get(activeId)
  if (!session) return { text: say(ctx, "cmd.no_active_session") }
  const modelName = parts[0]!
  if (ctx.switchModel) {
    const result = await ctx.switchModel(activeId, modelName)
    if (!result.ok) return { text: say(ctx, "cmd.model_switch_failed", { error: result.error }) }
    return { text: say(ctx, "cmd.model_switched", { name: session.name, model: modelName }) }
  }
  ctx.registry.setModel(activeId, modelName)
  return { text: say(ctx, "cmd.model_set", { name: session.name, model: modelName }) }
}

async function cmdEffort(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/).filter(Boolean)

  if (parts.length === 0) {
    const activeId = ctx.registry.getActive(ctx.chat_id)
    if (!activeId) return { text: say(ctx, "cmd.no_active_session") }
    const session = ctx.registry.get(activeId)
    if (!session) return { text: say(ctx, "cmd.no_active_session") }
    if (session.agent === "cursor") {
      return { text: say(ctx, "cmd.cursor_effort", { name: session.name }) }
    }
    const current = ctx.resolveReasoningLevel?.(activeId) ?? session.reasoningLevel ?? say(ctx, "cmd.default_effort")
    const available = ctx.listReasoningLevels?.(session.agent, session.model) ?? []
    if (available.length > 1) {
      const lines = [say(ctx, "cmd.reasoning_for", { name: session.name, agent: session.agent })]
      for (const l of available) {
        const marker = l.id === current ? "●" : " "
        lines.push(`${marker} ${l.id}`)
      }
      return { text: lines.join("\n") }
    }
    return { text: say(ctx, "cmd.current_effort", { name: session.name, agent: session.agent, level: current }) }
  }

  // /effort <session> <level> — switch specific session (try UUID first, then name)
  const sessionByName = ctx.registry.get(parts[0]!) ?? ctx.registry.resolveName(parts[0]!)
  if (parts.length === 2 && sessionByName) {
    const level = parts[1]!
    if (ctx.switchReasoningLevel) {
      const result = await ctx.switchReasoningLevel(sessionByName.id, level)
      if (!result.ok) return { text: say(ctx, "cmd.effort_switch_failed", { error: result.error }) }
      return { text: say(ctx, "cmd.effort_switched", { name: sessionByName.name, level }) }
    }
    ctx.registry.setReasoningLevel(sessionByName.id, level)
    return { text: say(ctx, "cmd.effort_set", { name: sessionByName.name, level }) }
  }

  // /effort <level> — switch active session
  const activeId = ctx.registry.getActive(ctx.chat_id)
  if (!activeId) return { text: say(ctx, "cmd.no_active_session") }
  const session = ctx.registry.get(activeId)
  if (!session) return { text: say(ctx, "cmd.no_active_session") }
  const level = parts[0]!
  if (ctx.switchReasoningLevel) {
    const result = await ctx.switchReasoningLevel(activeId, level)
    if (!result.ok) return { text: say(ctx, "cmd.effort_switch_failed", { error: result.error }) }
    return { text: say(ctx, "cmd.effort_switched", { name: session.name, level }) }
  }
  ctx.registry.setReasoningLevel(activeId, level)
  return { text: say(ctx, "cmd.effort_set", { name: session.name, level }) }
}

async function cmdFast(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const activeId = ctx.registry.getActive(ctx.chat_id)
  const session = activeId ? ctx.registry.get(activeId) : undefined
  if (!session) return { text: say(ctx, "cmd.no_active_session") }
  const state = ctx.fastModeState?.(session.id) ?? { on: false, supported: false }
  const arg = rest.trim().toLowerCase()
  if (!arg) {
    if (!state.supported) return { text: say(ctx, "cmd.fast_unsupported", { name: session.name }) }
    return { text: say(ctx, state.on ? "cmd.fast_is_on" : "cmd.fast_is_off", { name: session.name }) }
  }
  if (arg !== "on" && arg !== "off") return { text: say(ctx, "cmd.fast_usage") }
  if (!ctx.switchFastMode) return { text: say(ctx, "cmd.fast_unavailable") }
  const result = await ctx.switchFastMode(session.id, arg === "on")
  if (!result.ok) return { text: say(ctx, "cmd.fast_switch_failed", { error: result.error }) }
  const when = result.status === "queued" ? say(ctx, "cmd.fast_queued") : ""
  return { text: say(ctx, arg === "on" ? "cmd.fast_on" : "cmd.fast_off", { name: session.name }) + when }
}

async function cmdPermissions(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/).filter(Boolean)

  const listFor = (session: { name: string; agent: typeof session extends never ? never : import("../shared/agents").AgentKind; permissionMode?: string | null }) => {
    const current = resolvePermissionMode(session.agent, session.permissionMode)
    return { text: `${session.name}\n${formatModesList(session.agent, current)}` }
  }

  if (parts.length === 0) {
    const activeId = ctx.registry.getActive(ctx.chat_id)
    if (!activeId) return { text: say(ctx, "cmd.no_active_session") }
    const session = ctx.registry.get(activeId)
    if (!session) return { text: say(ctx, "cmd.no_active_session") }
    return listFor(session)
  }

  if (parts.length === 2) {
    const sessionFromSecond = ctx.registry.get(parts[1]!) ?? ctx.registry.resolveName(parts[1]!)
    const sessionFromFirst = ctx.registry.get(parts[0]!) ?? ctx.registry.resolveName(parts[0]!)
    if (sessionFromSecond) return applyPermissionMode(sessionFromSecond.id, sessionFromSecond.name, sessionFromSecond.agent, parts[0]!, ctx)
    if (sessionFromFirst) return applyPermissionMode(sessionFromFirst.id, sessionFromFirst.name, sessionFromFirst.agent, parts[1]!, ctx)
    return { text: say(ctx, "cmd.permissions_usage") }
  }

  const sessionByName = ctx.registry.get(parts[0]!) ?? ctx.registry.resolveName(parts[0]!)
  if (sessionByName && !isPermissionMode(sessionByName.agent, parts[0]!)) {
    return listFor(sessionByName)
  }

  const activeId = ctx.registry.getActive(ctx.chat_id)
  if (!activeId) return { text: say(ctx, "cmd.no_active_session") }
  const session = ctx.registry.get(activeId)
  if (!session) return { text: say(ctx, "cmd.no_active_session") }
  return applyPermissionMode(activeId, session.name, session.agent, parts[0]!, ctx)
}

async function applyPermissionMode(
  sessionId: string,
  name: string,
  agent: import("../shared/agents").AgentKind,
  mode: string,
  ctx: CommandCtx,
): Promise<SlashReply> {
  if (!isPermissionMode(agent, mode)) {
    const session = ctx.registry.get(sessionId)
    const current = resolvePermissionMode(agent, session?.permissionMode)
    return { text: `${say(ctx, "cmd.unknown_mode", { mode })}\n${formatModesList(agent, current)}` }
  }
  if (ctx.switchPermissionMode) {
    const result = await ctx.switchPermissionMode(sessionId, mode)
    if (!result.ok) return { text: say(ctx, "cmd.permissions_switch_failed", { error: result.error }) }
    const when = result.applied === "next-turn" ? say(ctx, "cmd.permissions_next_turn") : ""
    return { text: say(ctx, "cmd.permissions_set", { name, mode }) + when }
  }
  ctx.registry.setPermissionMode(sessionId, mode)
  return { text: say(ctx, "cmd.permissions_set", { name, mode }) }
}

function cmdShow(rest: string, ctx: CommandCtx): SlashReply {
  const parts = rest.trim().split(/\s+/)
  const name = parts[0]
  const n = parts[1] ? Math.min(50, parseInt(parts[1], 10) || 10) : 10
  if (!name) return { text: say(ctx, "cmd.show_usage") }
  const session = ctx.registry.resolveName(name)
  if (!session) return { text: say(ctx, "cmd.no_such_session", { name }) }
  // messageLog.get expects session UUID (not name)
  const entries = ctx.messageLog.get(session.id).slice(-n)
  if (entries.length === 0) return { text: say(ctx, "cmd.show_empty", { name }) }
  const lines = entries.map((e) => {
    const tag = e.direction === "inbound" ? "←" : "→"
    return `${tag} ${e.text ?? say(ctx, "cmd.no_text")}`.slice(0, 200)
  })
  return { text: `${name}:\n${lines.join("\n")}` }
}

function cmdProxies(ctx: CommandCtx): SlashReply {
  const proxies = ctx.registry.listProxies()
  if (proxies.length === 0) return { text: say(ctx, "cmd.no_proxies") }
  const lines = proxies.map(p =>
    `${buildProxyPublicUrl(p.domain, { baseDomain: ctx.proxyBaseDomain, publicUrl: ctx.proxyPublicUrl })} → localhost:${p.port}  [${p.sessionName}]`
  )
  return { text: lines.join("\n") }
}

async function cmdProxy(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/)
  if (parts.length < 2) return { text: say(ctx, "cmd.proxy_usage") }
  const [sessionName, portStr, domain] = parts as [string, string, string?]
  const session = ctx.registry.resolveName(sessionName)
  if (!session) return { text: say(ctx, "cmd.no_such_session", { name: sessionName }) }
  const port = parseInt(portStr, 10)
  if (!port || port < 1 || port > 65535) return { text: say(ctx, "cmd.port_range") }
  let finalDomain = domain
  if (!finalDomain) {
    finalDomain = "px-" + randomBytes(4).toString("hex")
  }
  try {
    const entry = ctx.registry.addProxy({ domain: finalDomain, sessionId: session.id, port })
    return { text: say(ctx, "cmd.proxy_created", { url: buildProxyPublicUrl(entry.domain, { baseDomain: ctx.proxyBaseDomain, publicUrl: ctx.proxyPublicUrl }), port }) }
  } catch (err: any) {
    return { text: say(ctx, "cmd.proxy_failed", { error: err?.message ?? String(err) }) }
  }
}

function cmdUnproxy(rest: string, ctx: CommandCtx): SlashReply {
  const domain = rest.trim()
  if (!domain) return { text: say(ctx, "cmd.unproxy_usage") }
  const existing = ctx.registry.getProxy(domain)
  if (!existing) return { text: say(ctx, "cmd.no_proxy_for", { domain }) }
  ctx.registry.removeProxy(domain)
  return { text: say(ctx, "cmd.proxy_removed", { domain }) }
}

function cmdArchive(ctx: CommandCtx): SlashReply {
  const archived = ctx.registry.sessions.listArchived().filter(s => !s.parent_session_id)
  if (archived.length === 0) return { text: say(ctx, "cmd.no_archived") }
  const locale = brokerLang(ctx.lang) === "tr" ? "tr-TR" : undefined
  const lines = archived.map(s => {
    const killed = s.killed_at ? ` ${say(ctx, "cmd.killed_on", { date: new Date(s.killed_at).toLocaleDateString(locale) })}` : ""
    return `• ${s.name} [${s.agent}] ${s.workdir}${killed}`
  })
  return { text: `${say(ctx, "cmd.archived_header")}\n${lines.join("\n")}` }
}

async function cmdResume(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const name = rest.trim()
  if (!name) return { text: say(ctx, "cmd.resume_usage") }
  if (!ctx.resumeFromArchive) return { text: say(ctx, "cmd.resume_unavailable") }
  const archived = ctx.registry.sessions.listArchived().filter(s => s.name === name && !s.parent_session_id)
  if (archived.length === 0) return { text: say(ctx, "cmd.no_archived_named", { name }) }
  const target = archived[0]! // listArchived sorted by killed_at DESC
  const result = await ctx.resumeFromArchive(target.id)
  if (!result.ok) return { text: say(ctx, "cmd.resume_failed", { error: result.error ?? "" }) }
  return { text: say(ctx, "cmd.resumed", { name: result.name ?? "" }) }
}
