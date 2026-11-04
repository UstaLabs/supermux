import type { AgentKind } from "../agents/types"
import { makeLogger } from "../../shared/log"
import { controlCommands } from "./control"
import type { CommandStore } from "./store"
import type { AgentCommandList, AgentCommandProvider, SlashCommand } from "./types"

const log = makeLogger("slash/registry")

export interface RegistrySession {
  name: string
  /** The session's id: a live list belongs to it, not to a later session that reuses the name. */
  id?: string
  kind: AgentKind
  workdir: string
  /** The repository's own folder when `workdir` is one of its worktrees: lists are stored there too. */
  repoRoot?: string
  muted: boolean
  /** Opaque per-kind discovery context (see ProviderCtx.agentContext). */
  agentContext?: unknown
}

export interface CommandRegistryDeps {
  providers: Partial<Record<AgentKind, AgentCommandProvider>>
  /** Look up the current session view, or undefined if gone. */
  resolveSession: (name: string) => RegistrySession | undefined
  /** The lists agents reported before, per folder (survives a broker restart). */
  store: CommandStore
  /** Optional: called whenever a session's merged list changes. */
  onChange?: (name: string, commands: SlashCommand[]) => void
}

export interface CommandPreviewRequest {
  kind: AgentKind
  workdir: string
  /** Opaque per-kind discovery context (see ProviderCtx.agentContext). */
  agentContext?: unknown
}

export type PreviewAgentCommandsCtx = CommandPreviewRequest

/**
 * Another folder's list without what exists only in that folder. Undefined when the agent does not mark
 * those entries: its list can't stand in for a folder it was not reported in.
 */
function withoutProjectCommands(list: AgentCommandList | undefined): AgentCommandList | undefined {
  if (!list?.projectIds) return undefined
  const own = new Set(list.projectIds)
  return { commands: list.commands.filter((c) => !own.has(c.id)), projectIds: [] }
}

/**
 * A session's slash commands: control commands plus what its agent reported. Nothing here asks an agent
 * to do anything: the providers read what each live session already reported (card #13).
 */
export class CommandRegistry {
  private live = new Map<string, { id?: string; list: AgentCommandList }>()
  private inflight = new Map<string, Promise<void>>()
  private rerun = new Set<string>()
  private resolved = new Set<string>()
  /** Bumped by remove(): a refresh that started before it does not bring the session back. */
  private epochs = new Map<string, number>()
  /** The merged list last broadcast per session, as JSON, so an unchanged one is not sent again. */
  private broadcast = new Map<string, string>()
  constructor(private deps: CommandRegistryDeps) {}

  /** True once there is something to show from the agent, or its provider has answered (even with nothing). */
  isResolved(name: string): boolean {
    if (this.resolved.has(name)) return true
    const session = this.deps.resolveSession(name)
    return !!session && this.agentList(session) !== undefined
  }

  /** Control commands (always fresh from session state), then the agent's: live, else stored for the folder, else the fallback. */
  get(name: string): SlashCommand[] {
    const session = this.deps.resolveSession(name)
    if (!session) return []
    return [...controlCommands({ muted: session.muted }), ...(this.agentList(session)?.commands ?? [])]
  }

  /** Re-read what the session's agent reported. A call while one runs makes it run once more afterwards. */
  refresh(name: string): Promise<void> {
    const running = this.inflight.get(name)
    if (running) {
      this.rerun.add(name)
      return running
    }
    const p: Promise<void> = (async () => {
      do {
        this.rerun.delete(name)
        await this.run(name)
      } while (this.rerun.has(name))
    })().finally(() => { if (this.inflight.get(name) === p) this.inflight.delete(name) })
    this.inflight.set(name, p)
    return p
  }

  /**
   * New Session's list, before any session exists: an exact answer when the agent can give one without a
   * session of its own (Codex: any live app-server; Antigravity: its skills dirs), else the list stored for
   * the folder, else the agent's latest list from another folder minus that folder's own commands.
   */
  async preview(req: CommandPreviewRequest): Promise<{ commands: SlashCommand[]; resolved: boolean }> {
    const provider = this.deps.providers[req.kind]
    const exact = provider
      ? await provider.list({ sessionName: `preview:${req.kind}`, workdir: req.workdir, agentContext: req.agentContext }).catch(() => undefined)
      : undefined
    const list = exact ?? this.stored(req.kind, req.workdir)
    return { commands: list?.commands ?? [], resolved: true }
  }

  /** Drop the live list so the stored one shows until the next refresh. */
  invalidate(name: string): void {
    this.live.delete(name)
  }

  remove(name: string): void {
    this.live.delete(name)
    this.inflight.delete(name)
    this.rerun.delete(name)
    this.resolved.delete(name)
    this.broadcast.delete(name)
    this.epochs.set(name, (this.epochs.get(name) ?? 0) + 1)
  }

  private agentList(session: RegistrySession): AgentCommandList | undefined {
    const live = this.live.get(session.name)
    return (live && live.id === session.id ? live.list : undefined) ?? this.stored(session.kind, session.workdir)
  }

  private stored(kind: AgentKind, workdir: string): AgentCommandList | undefined {
    return this.deps.store.load(kind, workdir) ?? withoutProjectCommands(this.deps.store.latest(kind))
  }

  private async run(name: string): Promise<void> {
    const epoch = this.epochs.get(name) ?? 0
    const session = this.deps.resolveSession(name)
    if (!session) return
    const provider = this.deps.providers[session.kind]
    const list = provider
      ? await provider.list({ sessionName: session.name, workdir: session.workdir, agentContext: session.agentContext }).catch(() => undefined)
      : undefined
    if ((this.epochs.get(name) ?? 0) !== epoch) return // removed while the provider answered
    const wasResolved = this.resolved.has(name)
    if (list) {
      this.live.set(name, { id: session.id, list })
      const folders = session.repoRoot && session.repoRoot !== session.workdir ? [session.workdir, session.repoRoot] : [session.workdir]
      try { this.deps.store.save(session.kind, folders, list) }
      catch (err) { log.warn("agent_commands_store_failed", { session: name, err: err instanceof Error ? err.message : String(err) }) }
    }
    this.resolved.add(name)
    log.debug("agent_commands_resolved", { session: name, kind: session.kind, count: list?.commands.length ?? null })
    const merged = this.get(name)
    const json = JSON.stringify(merged)
    if (wasResolved && this.broadcast.get(name) === json) return // nothing new (Codex re-lists after every turn)
    this.broadcast.set(name, json)
    this.deps.onChange?.(name, merged)
  }
}
