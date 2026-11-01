import { randomUUID } from "crypto"
import { existsSync, mkdirSync, rmSync } from "fs"
import { AgentKind, isAgentKind } from "../../shared/agents"
import { agentWorkdir } from "../../shared/paths"
import { ensureAgentMemoryFiles } from "../memory/agent-memory"
import type { SessionRole } from "./policy"

/** The ONE way an Agent is created (spec §3): web, Telegram and the create_agent tool all call it.
 *  Later the workspace/announce half folds into the orchestration Plan 4 shared create function. */
export type CreateAgentArgs = { name: string; provider?: AgentKind | string; model?: string; reasoningLevel?: string }
export type CreateAgentDeps = {
  muxHome: string
  takenNames(): Set<string>
  /** True when [workdir] is the workdir of a non-archived session (a renamed Agent keeps its old folder). */
  workdirInUse(workdir: string): boolean
  /** Throws AgentNotInstalledError (status 409, code agent_not_installed). */
  assertInstalled(provider: AgentKind): void
  /** spawnPA with every dependency (resolveAttachment included); registers the row with this id. */
  spawn(args: { id: string; name: string; provider: AgentKind; workdir: string; model?: string; reasoningLevel?: string }): Promise<{ id: string; name: string }>
  /** Workspace + workspace_added + full session_added (role, isDefault) + Telegram menu. */
  announce(id: string): Promise<void>
  /** Hands the new Agent its first turn so it introduces itself (see agentGreetingPrompt). Fire-and-forget. */
  greet?(id: string, name: string): void
}

export class CreateAgentError extends Error {
  constructor(message: string, readonly status: number, readonly code: string) { super(message) }
}

const MAX_NAME = 80

export function validateAgentName(raw: string, taken: Set<string>): { ok: true; name: string } | { ok: false; error: string; status: number; code: string } {
  const name = (raw ?? "").trim()
  if (!name) return { ok: false, error: "name required", status: 400, code: "name_invalid" }
  if (name.length > MAX_NAME) return { ok: false, error: `name longer than ${MAX_NAME} characters`, status: 400, code: "name_invalid" }
  // It becomes a folder name under ~/.mux/workspace: no separators, no dot-names.
  // Control characters (U+0000–U+001F, U+007F) would break Telegram menus, logs and paths.
  if (/[\/\\\x00-\x1f\x7f]/.test(name) || name.startsWith(".")) return { ok: false, error: "name must not contain / or \\ or control characters, or start with a dot", status: 400, code: "name_invalid" }
  if (taken.has(name)) return { ok: false, error: `name already in use: ${name}`, status: 409, code: "name_taken" }
  return { ok: true, name }
}

/** The generic session rename (POST /sessions/:id/rename): an Agent's new name must pass the same
 *  rules as create/updateAgent (its own current name is not "taken"); a worker's passes untouched. */
export function checkSessionRename(
  session: { name: string; role?: string },
  newName: string,
  takenNames: () => Set<string>,
): { ok: true; name: string } | { ok: false; error: string; status: number; code: string } {
  if (session.role !== "personal_assistant") return { ok: true, name: newName }
  const taken = takenNames()
  taken.delete(session.name)
  return validateAgentName(newName, taken)
}

export async function createAgent(deps: CreateAgentDeps, args: CreateAgentArgs): Promise<{ id: string; name: string; workdir: string; provider: AgentKind }> {
  const v = validateAgentName(args.name, deps.takenNames())
  if (!v.ok) throw new CreateAgentError(v.error, v.status, v.code)
  const provider = (args.provider ?? AgentKind.Claude) as AgentKind
  if (!isAgentKind(provider)) throw new CreateAgentError(`unknown provider: ${String(args.provider)}`, 400, "provider_invalid")
  deps.assertInstalled(provider)

  const workdir = agentWorkdir(v.name, deps.muxHome)
  if (deps.workdirInUse(workdir)) {
    throw new CreateAgentError(`name already in use: the folder ${workdir} belongs to another session`, 409, "name_taken")
  }
  // An existing folder with no live session (an old, archived one) is reused as-is, never cleared.
  const madeWorkdir = !existsSync(workdir)
  mkdirSync(workdir, { recursive: true })
  const id = randomUUID()
  const mem = ensureAgentMemoryFiles(id, deps.muxHome)
  try {
    await deps.spawn({ id, name: v.name, provider, workdir, model: args.model, reasoningLevel: args.reasoningLevel })
  } catch (err) {
    rmSync(mem.history, { force: true })
    rmSync(mem.digest, { force: true })
    if (madeWorkdir) rmSync(workdir, { recursive: true, force: true })
    throw err
  }
  await deps.announce(id)
  deps.greet?.(id, v.name)
  return { id, name: v.name, workdir, provider }
}

/** The hidden first turn of a new Agent: the broker sends it, the user only sees the Agent's answer.
 *  [recentUserMessages] are the user's own latest messages (any session, newest first): settings are
 *  rarely filled in, but what they actually write shows their language. */
export function agentGreetingPrompt(name: string, recentUserMessages: string[] = []): string {
  const samples = recentUserMessages.map((t) => t.replace(/\s+/g, " ").trim().slice(0, 200)).filter(Boolean)
  const language = samples.length
    ? [`Write it in the language most of these messages are in (look at the words they are written in, not at what they talk about: "add Turkish translations" is written in English; ignore code words and product names). These are their latest messages to other sessions, newest first — use them ONLY to tell their language. They override anything else you believe about the user's language. Never mention, answer or hint at what they are about:`, ...samples.map((t) => `- ${t}`)].join("\n")
    : `You have no messages from the user yet: keep the greeting very short, and from their first answer on, talk in whatever language they write in.`
  return [
    `[supermux] You were just created as a new Agent named "${name}". The user has not written to you yet; this note comes from the broker, not from them.`,
    `Introduce yourself to the user in a short, warm first message: say you are ${name}, their new agent, and ask how you can help.`,
    `Tell them, in a sentence or two, that they can rename you, tell you how you should talk to them (tone, style, what to call them), or give you an avatar.`,
    language,
    `Match their language exactly: if they write English, write English; if they write Turkish, write Turkish. Do not default to English when they write another language, and never pick a language they do not write in. Sound like a person, not a template: no bullet lists, no "As an AI", no stiff textbook phrasing. Do not mention this note.`,
  ].join("\n")
}

/** The create_agent tool's gate: only Agents create Agents. */
export async function createAgentFromTool(caller: { role?: SessionRole } | undefined, args: Record<string, unknown>, deps: CreateAgentDeps): Promise<{ ok: true; value: unknown } | { ok: false; error: string }> {
  if (caller?.role !== "personal_assistant") return { ok: false, error: "only agents can create agents" }
  const provider = args.provider
  if (provider != null && !isAgentKind(provider)) return { ok: false, error: `unknown provider: ${String(provider)}` }
  try {
    const r = await createAgent(deps, { name: String(args.name ?? ""), provider: provider as AgentKind | undefined, model: typeof args.model === "string" ? args.model : undefined })
    return { ok: true, value: { id: r.id, name: r.name, provider: r.provider } }
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) }
  }
}

export type AgentPatch = { model?: string; reasoningLevel?: string; isDefault?: true; name?: string }
export type UpdateAgentResult = { ok: true } | { ok: false; error: string; status: number; code?: string }

export type UpdateAgentDeps = {
  /** By id first, then by name (old clients address an Agent by name). */
  find: (key: string) => { id: string; name: string; role: string; status?: string } | undefined
  takenNames: () => Set<string>
  setDefault: (id: string) => boolean
  /** Renames the row and tells every client (session_renamed + workspace name). Folder and memory stay. */
  rename: (id: string, newName: string) => Promise<void>
  switchModel: (id: string, model: string) => Promise<{ ok: boolean; error?: string }>
  switchReasoning: (id: string, level: string) => Promise<{ ok: boolean; error?: string }>
}

/** PATCH /api/pas|agents/<id-or-name>. Every check runs before any change. */
export async function updateAgent(deps: UpdateAgentDeps, key: string, patch: AgentPatch): Promise<UpdateAgentResult> {
  const agent = deps.find(key)
  if (!agent || agent.status === "archived") return { ok: false, status: 404, code: "not_found", error: `agent not found: ${key}` }
  if (agent.role !== "personal_assistant") return { ok: false, status: 400, code: "not_an_agent", error: `${agent.name} is not an agent` }

  let newName: string | undefined
  if (patch.name !== undefined && patch.name.trim() !== agent.name) {
    const taken = deps.takenNames()
    taken.delete(agent.name)
    const v = validateAgentName(patch.name, taken)
    if (!v.ok) return { ok: false, status: v.status, code: v.code, error: v.error }
    newName = v.name
  }

  if (patch.model) {
    const r = await deps.switchModel(agent.id, patch.model)
    if (!r.ok) return { ok: false, status: 400, error: r.error ?? "model switch failed" }
  }
  if (patch.reasoningLevel) {
    const r = await deps.switchReasoning(agent.id, patch.reasoningLevel)
    if (!r.ok) return { ok: false, status: 400, error: r.error ?? "reasoning switch failed" }
  }
  if (newName) await deps.rename(agent.id, newName)
  // False when the row is unknown or was archived after find (e.g. killed meanwhile).
  if (patch.isDefault && !deps.setDefault(agent.id)) return { ok: false, status: 404, code: "not_found", error: `agent not found: ${key}` }
  return { ok: true }
}
