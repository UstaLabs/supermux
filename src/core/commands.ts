import { randomBytes } from "crypto"
import { Registry } from "./session-manager/registry"
import { isPersistentRuntimeSession } from "./session-manager/types"
import type { MessageStore } from "./session-manager/messages"
import { fetchAllUsage } from "./usage/index"
import { formatUsageTelegram } from "./usage/format"
import { AGENT_KINDS, AgentKind, isAgentKind, spawnCommandForAgent } from "../shared/agents"
import { buildProxyPublicUrl } from "../channels/web/proxy"

export type CommandCtx = {
  registry: Registry
  messageLog: MessageStore
  chat_id: string
  fromSession?: string  // for orchestration tool-calls from a session; chat-initiated leaves this undefined
  spawnSession: (workdir: string, name?: string, agent?: AgentKind, model?: string, reasoningLevel?: string) => Promise<{ name: string; session_id: string }>
  killSession: (id: string) => Promise<void>
  refreshMenu: () => Promise<void>
  listModels?: (agent: AgentKind) => { id: string; displayName: string }[]
  switchModel?: (sessionId: string, model: string) => Promise<{ ok: true } | { ok: false; error: string }>
  switchReasoningLevel?: (sessionId: string, level: string) => Promise<{ ok: true } | { ok: false; error: string }>
  listReasoningLevels?: (agent: AgentKind, model?: string) => { id: string; description?: string }[]
  resolveReasoningLevel?: (sessionId: string) => string | undefined
  proxyBaseDomain?: string
  proxyPublicUrl?: string
  resumeFromArchive?: (id: string) => Promise<{ ok: boolean; name?: string; error?: string }>
  /** Soft-interrupt a running session: stop the current turn, keep it alive. */
  interrupt?: (sessionId: string) => Promise<{ ok: boolean; reason?: string }>
  spawnPA?: (args: { name: string; agent?: AgentKind; model?: string; focus?: string }) => Promise<{ name: string; id?: string; workdir?: string; agent?: AgentKind; model?: string }>
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
    case "usage":            return cmdUsage()
    case "proxy":             return cmdProxy(input.rest, ctx)
    case "unproxy":           return cmdUnproxy(input.rest, ctx)
    case "proxies":           return cmdProxies(ctx)
    case "archive":           return cmdArchive(ctx)
    case "resume":            return cmdResume(input.rest, ctx)
    case "spawnpa":           return await cmdSpawnPA(input.rest, ctx)
    case "pas":               return cmdListPAs(ctx)
    default:                  return { text: `unknown command: /${input.command}` }
  }
}

function cmdSessions(ctx: CommandCtx): SlashReply {
  const sessions = ctx.registry.listVisible()
  if (sessions.length === 0) return { text: "no sessions" }
  const activeId = ctx.registry.getActive(ctx.chat_id)
  const lines = sessions.map(s => {
    const tag = s.id === activeId ? "●" : " "
    const muted = s.mute ? " 🔇" : ""
    // `connected` mirrors the claude shim socket; for adapter-driven kinds it is
    // not a liveness signal, so only persistent-runtime (claude) sessions get
    // the reconnecting tag.
    const status = !isPersistentRuntimeSession(s) || s.connected ? "" : " · reconnecting"
    const modelSuffix = s.model ? `:${s.model}` : ""
    const agentTag = `[${s.agent}${modelSuffix}]`
    return `${tag} ${s.name} ${agentTag}${muted}${status}  ${s.workdir}`
  })
  const keyboard = sessions.map(s => [
    { text: `Switch↗`, callback_data: `switch:${s.id}` },
    { text: `Rename✏`, callback_data: `rename:${s.id}` },
    { text: s.mute ? `Unmute🔊` : `Mute🔇`, callback_data: s.mute ? `unmute:${s.id}` : `mute:${s.id}` },
    { text: `Kill✕`, callback_data: `kill:${s.id}` },
  ])
  return { text: lines.join("\n"), keyboard, parse_mode: "HTML" }
}

function cmdActive(ctx: CommandCtx): SlashReply {
  const a = ctx.registry.getActive(ctx.chat_id)
  const name = a ? (ctx.registry.get(a)?.name ?? a) : undefined
  return { text: name ? `active: ${name}` : "no active session" }
}

function cmdSwitch(rest: string, ctx: CommandCtx): SlashReply {
  const input = rest.trim()
  const session = ctx.registry.get(input) ?? ctx.registry.resolveName(input)
  if (!session) return { text: `no such session: ${input}` }
  ctx.registry.setActive(ctx.chat_id, session.id)
  return { text: `switched to ${session.name}` }
}

async function cmdSpawn(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  let agent: AgentKind = AgentKind.Claude
  let model: string | undefined
  let cleaned = rest
  const agentMatch = rest.match(/--agent\s+(\S+)/)
  if (agentMatch) {
    const requested = agentMatch[1]!
    if (!isAgentKind(requested)) {
      return { text: `unknown agent: ${requested}. Use ${AGENT_KINDS.join(", ")}.` }
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
  const asMatch = cleaned.match(/^(\S+)\s+as\s+(\S+)$/)
  const workdir = asMatch ? asMatch[1]! : cleaned.trim()
  const name = asMatch ? asMatch[2]!.trim().slice(0, 80) : undefined
  if (!workdir) return { text: `usage: /spawn <workdir> [as <name>] [--agent ${AGENT_KINDS.join("|")}] [--model <model>] [--effort <level>]` }
  try {
    const result = await ctx.spawnSession(workdir, name, agent, model, reasoningLevel)
    await ctx.refreshMenu()
    const parts: string[] = [agent]
    if (model) parts.push(model)
    if (reasoningLevel) parts.push(`effort=${reasoningLevel}`)
    const tag = `[${parts.join(":")}]`
    return { text: `spawned ${result.name} ${tag} in ${workdir}` }
  } catch (err: any) {
    return { text: `spawn failed: ${err?.message ?? String(err)}` }
  }
}

async function cmdSpawnPA(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  if (!ctx.spawnPA) return { text: "spawnpa not available in this context" }
  let cleaned = rest.trim()
  if (!cleaned) return { text: `usage: /spawnpa <name> [--agent ${AGENT_KINDS.join("|")}] [--model <model>] [--focus <text>]` }

  let agent: AgentKind | undefined
  const agentMatch = cleaned.match(/--agent\s+(\S+)/)
  if (agentMatch) {
    const requested = agentMatch[1]!
    if (!isAgentKind(requested)) {
      return { text: `unknown agent: ${requested}. Use ${AGENT_KINDS.join(", ")}.` }
    }
    agent = requested
    cleaned = (cleaned.slice(0, agentMatch.index) + cleaned.slice(agentMatch.index! + agentMatch[0].length)).replace(/\s+/g, " ").trim()
  }

  let model: string | undefined
  const modelMatch = cleaned.match(/--model\s+(\S+)/)
  if (modelMatch) {
    model = modelMatch[1]!
    cleaned = (cleaned.slice(0, modelMatch.index) + cleaned.slice(modelMatch.index! + modelMatch[0].length)).replace(/\s+/g, " ").trim()
  }

  let focus: string | undefined
  const focusMatch = cleaned.match(/--focus\s+(.+)/)
  if (focusMatch) {
    focus = focusMatch[1]!.trim()
    cleaned = (cleaned.slice(0, focusMatch.index) + cleaned.slice(focusMatch.index! + focusMatch[0].length)).replace(/\s+/g, " ").trim()
  }

  const name = cleaned.trim()
  if (!name) return { text: `usage: /spawnpa <name> [--agent ${AGENT_KINDS.join("|")}] [--model <model>] [--focus <text>]` }

  if (ctx.registry.resolveName(name)) {
    return { text: `name already in use: ${name}` }
  }

  try {
    const result = await ctx.spawnPA({ name, agent, model, focus })
    const parts: string[] = [result.agent ?? agent ?? "claude"]
    if (result.model ?? model) parts.push(result.model ?? model!)
    const tag = `[${parts.join(":")}]`
    return { text: `spawned PA ${result.name} ${tag} in ${result.workdir ?? "workspace"}` }
  } catch (err: any) {
    return { text: `spawn failed: ${err?.message ?? String(err)}` }
  }
}

function cmdListPAs(ctx: CommandCtx): SlashReply {
  const pas = ctx.registry.listPAs()
  if (pas.length === 0) return { text: "no personal assistants" }
  const lines = pas.map(s => {
    const star = s.is_default ? "*" : " "
    const modelSuffix = s.model ? `:${s.model}` : ""
    const agentTag = `[${s.agent}${modelSuffix}]`
    // Same claude-only caveat as cmdSessions: the connected flag means nothing
    // for adapter-driven PAs, so they get no connection label.
    const status = isPersistentRuntimeSession(s) ? (s.connected ? "connected" : "reconnecting") : ""
    return `${star} ${s.name} ${agentTag}  ${s.workdir}${status ? `  ${status}` : ""}`
  })
  return { text: lines.join("\n") }
}

async function cmdKill(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const m = rest.match(/^(\S+)(?:\s+yes)?$/)
  if (!m) return { text: "usage: /kill <name> [yes]" }
  const name = m[1]!
  const confirmed = /\byes\b/.test(rest)
  const target = ctx.registry.resolveName(name)
  if (!target) return { text: `no such session: ${name}` }
  if (!confirmed) return { text: `confirm: /kill ${name} yes` }
  await ctx.killSession(target.id)
  ctx.registry.unregister(target.id)
  if (target.role === "personal_assistant" && target.is_default) {
    ctx.registry.reassignDefault(target.id)
  }
  await ctx.refreshMenu()
  // Apply fallback rule if killed was the active for this chat
  const active = ctx.registry.getActive(ctx.chat_id)
  if (!active) {
    const fb = ctx.registry.activeFallback(ctx.chat_id)
    if (fb) ctx.registry.setActive(ctx.chat_id, fb)
  }
  return { text: `killed ${name}` }
}

async function cmdStop(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const input = rest.trim()
  let sessionId: string | undefined
  if (input) {
    const s = ctx.registry.resolveName(input)
    if (!s) return { text: `no such session: ${input}` }
    sessionId = s.id
  } else {
    sessionId = ctx.registry.getActive(ctx.chat_id)
  }
  if (!sessionId) return { text: "no active session" }
  if (!ctx.interrupt) return { text: "stop not available in this context" }
  const r = await ctx.interrupt(sessionId)
  const s = ctx.registry.get(sessionId)
  const displayName = s?.name ?? sessionId
  return { text: r.ok ? `stopped ${displayName}` : `couldn't stop ${displayName}: ${r.reason ?? "unknown"}` }
}

async function cmdRename(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.split(/\s+/)
  const oldName = parts[0]
  const rawName = parts[1]
  if (!oldName || !rawName || parts.length !== 2) return { text: "usage: /rename <old> <new>" }
  const session = ctx.registry.resolveName(oldName)
  if (!session) return { text: `no such session: ${oldName}` }
  const newName = rawName.trim().slice(0, 80)
  if (!newName) return { text: "name must not be empty" }
  ctx.registry.rename(session.id, newName)
  await ctx.refreshMenu()
  return { text: `${oldName} → ${newName}` }
}

function cmdMute(rest: string, ctx: CommandCtx, muted: boolean): SlashReply {
  const name = rest.trim()
  const session = ctx.registry.resolveName(name)
  if (!session) return { text: `no such session: ${name}` }
  ctx.registry.setMuted(session.id, muted)
  return { text: `${name}: ${muted ? "muted" : "unmuted"}` }
}

function cmdGrantOrch(rest: string, ctx: CommandCtx): SlashReply {
  const name = rest.trim()
  const session = ctx.registry.resolveName(name)
  if (!session) return { text: `no such session: ${name}` }
  ctx.registry.grantOrchestrate(session.id, true)
  return { text: `${name}: can_orchestrate = true` }
}

async function cmdUsage(): Promise<SlashReply> {
  const data = await fetchAllUsage()
  return { text: formatUsageTelegram(data) }
}

async function cmdModel(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/).filter(Boolean)

  // /model (no args) — show current model for active session
  if (parts.length === 0) {
    const activeId = ctx.registry.getActive(ctx.chat_id)
    if (!activeId) return { text: "no active session" }
    const session = ctx.registry.get(activeId)
    if (!session) return { text: "no active session" }
    const current = session.model ?? "(default)"
    const available = ctx.listModels?.(session.agent) ?? []
    if (available.length > 0) {
      const lines = [`Models for ${session.name} [${session.agent}]:`]
      for (const m of available) {
        const marker = m.id === session.model ? "●" : " "
        lines.push(`${marker} ${m.id}`)
      }
      return { text: lines.join("\n") }
    }
    return { text: `${session.name} [${session.agent}]: current model: ${current}\nUse /model <name> to switch.` }
  }

  // /model <session> <name> — switch specific session (try UUID first, then name)
  const sessionByName = ctx.registry.get(parts[0]!) ?? ctx.registry.resolveName(parts[0]!)
  if (parts.length === 2 && sessionByName) {
    const modelName = parts[1]!
    if (ctx.switchModel) {
      const result = await ctx.switchModel(sessionByName.id, modelName)
      if (!result.ok) return { text: `model switch failed: ${result.error}` }
      return { text: `${sessionByName.name}: model switched to ${modelName}` }
    }
    ctx.registry.setModel(sessionByName.id, modelName)
    return { text: `${sessionByName.name}: model set to ${modelName}` }
  }

  // /model <name> — switch active session
  const activeId = ctx.registry.getActive(ctx.chat_id)
  if (!activeId) return { text: "no active session" }
  const session = ctx.registry.get(activeId)
  if (!session) return { text: "no active session" }
  const modelName = parts[0]!
  if (ctx.switchModel) {
    const result = await ctx.switchModel(activeId, modelName)
    if (!result.ok) return { text: `model switch failed: ${result.error}` }
    return { text: `${session.name}: model switched to ${modelName}` }
  }
  ctx.registry.setModel(activeId, modelName)
  return { text: `${session.name}: model set to ${modelName}` }
}

async function cmdEffort(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/).filter(Boolean)

  if (parts.length === 0) {
    const activeId = ctx.registry.getActive(ctx.chat_id)
    if (!activeId) return { text: "no active session" }
    const session = ctx.registry.get(activeId)
    if (!session) return { text: "no active session" }
    if (session.agent === "cursor") {
      return { text: `${session.name} [cursor]: reasoning depth is part of model selection` }
    }
    const current = ctx.resolveReasoningLevel?.(activeId) ?? session.reasoningLevel ?? "(default/max)"
    const available = ctx.listReasoningLevels?.(session.agent, session.model) ?? []
    if (available.length > 1) {
      const lines = [`Reasoning for ${session.name} [${session.agent}]:`]
      for (const l of available) {
        const marker = l.id === current ? "●" : " "
        lines.push(`${marker} ${l.id}`)
      }
      return { text: lines.join("\n") }
    }
    return { text: `${session.name} [${session.agent}]: current effort: ${current}\nUse /effort <level> to switch.` }
  }

  // /effort <session> <level> — switch specific session (try UUID first, then name)
  const sessionByName = ctx.registry.get(parts[0]!) ?? ctx.registry.resolveName(parts[0]!)
  if (parts.length === 2 && sessionByName) {
    const level = parts[1]!
    if (ctx.switchReasoningLevel) {
      const result = await ctx.switchReasoningLevel(sessionByName.id, level)
      if (!result.ok) return { text: `effort switch failed: ${result.error}` }
      return { text: `${sessionByName.name}: effort switched to ${level}` }
    }
    ctx.registry.setReasoningLevel(sessionByName.id, level)
    return { text: `${sessionByName.name}: effort set to ${level}` }
  }

  // /effort <level> — switch active session
  const activeId = ctx.registry.getActive(ctx.chat_id)
  if (!activeId) return { text: "no active session" }
  const session = ctx.registry.get(activeId)
  if (!session) return { text: "no active session" }
  const level = parts[0]!
  if (ctx.switchReasoningLevel) {
    const result = await ctx.switchReasoningLevel(activeId, level)
    if (!result.ok) return { text: `effort switch failed: ${result.error}` }
    return { text: `${session.name}: effort switched to ${level}` }
  }
  ctx.registry.setReasoningLevel(activeId, level)
  return { text: `${session.name}: effort set to ${level}` }
}

function cmdShow(rest: string, ctx: CommandCtx): SlashReply {
  const parts = rest.trim().split(/\s+/)
  const name = parts[0]
  const n = parts[1] ? Math.min(50, parseInt(parts[1], 10) || 10) : 10
  if (!name) return { text: "usage: /show <name> [N]" }
  const session = ctx.registry.resolveName(name)
  if (!session) return { text: `no such session: ${name}` }
  // messageLog.get expects session UUID (not name)
  const entries = ctx.messageLog.get(session.id).slice(-n)
  if (entries.length === 0) return { text: `(${name}: no recent messages)` }
  const lines = entries.map((e) => {
    const tag = e.direction === "inbound" ? "←" : "→"
    return `${tag} ${e.text ?? "(no text)"}`.slice(0, 200)
  })
  return { text: `${name}:\n${lines.join("\n")}` }
}

function cmdProxies(ctx: CommandCtx): SlashReply {
  const proxies = ctx.registry.listProxies()
  if (proxies.length === 0) return { text: "no active proxies" }
  const lines = proxies.map(p =>
    `${buildProxyPublicUrl(p.domain, { baseDomain: ctx.proxyBaseDomain, publicUrl: ctx.proxyPublicUrl })} → localhost:${p.port}  [${p.sessionName}]`
  )
  return { text: lines.join("\n") }
}

async function cmdProxy(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const parts = rest.trim().split(/\s+/)
  if (parts.length < 2) return { text: "usage: /proxy <session> <port> [domain]" }
  const [sessionName, portStr, domain] = parts as [string, string, string?]
  const session = ctx.registry.resolveName(sessionName)
  if (!session) return { text: `no such session: ${sessionName}` }
  const port = parseInt(portStr, 10)
  if (!port || port < 1 || port > 65535) return { text: "port must be 1-65535" }
  let finalDomain = domain
  if (!finalDomain) {
    finalDomain = "px-" + randomBytes(4).toString("hex")
  }
  try {
    const entry = ctx.registry.addProxy({ domain: finalDomain, sessionId: session.id, port })
    return { text: `proxy created: ${buildProxyPublicUrl(entry.domain, { baseDomain: ctx.proxyBaseDomain, publicUrl: ctx.proxyPublicUrl })} → localhost:${port}` }
  } catch (err: any) {
    return { text: `proxy failed: ${err?.message ?? String(err)}` }
  }
}

function cmdUnproxy(rest: string, ctx: CommandCtx): SlashReply {
  const domain = rest.trim()
  if (!domain) return { text: "usage: /unproxy <domain>" }
  const existing = ctx.registry.getProxy(domain)
  if (!existing) return { text: `no proxy registered for "${domain}"` }
  ctx.registry.removeProxy(domain)
  return { text: `removed proxy: ${domain}` }
}

function cmdArchive(ctx: CommandCtx): SlashReply {
  const archived = ctx.registry.sessions.listArchived()
  if (archived.length === 0) return { text: "No archived sessions." }
  const lines = archived.map(s => {
    const killed = s.killed_at ? ` (killed ${new Date(s.killed_at).toLocaleDateString()})` : ""
    return `• ${s.name} [${s.agent}] ${s.workdir}${killed}`
  })
  return { text: `Archived sessions:\n${lines.join("\n")}` }
}

async function cmdResume(rest: string, ctx: CommandCtx): Promise<SlashReply> {
  const name = rest.trim()
  if (!name) return { text: "Usage: /resume <session-name>" }
  if (!ctx.resumeFromArchive) return { text: "resume not available in this context" }
  const archived = ctx.registry.sessions.listArchived().filter(s => s.name === name)
  if (archived.length === 0) return { text: `No archived session named "${name}".` }
  const target = archived[0]! // listArchived sorted by killed_at DESC
  const result = await ctx.resumeFromArchive(target.id)
  if (!result.ok) return { text: `Failed to resume: ${result.error}` }
  return { text: `Resumed session "${result.name}".` }
}
