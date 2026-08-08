import type { AgentKind } from "../agents/types"
import { makeLogger } from "../../shared/log"
import { controlCommands } from "./control"
import type { AgentCommandProvider, CodexRpc, GrokAcpCommand, OpenCodeCommandClient, SlashCommand } from "./types"

const log = makeLogger("slash/registry")

export interface RegistrySession {
  name: string
  kind: AgentKind
  workdir: string
  muted: boolean
  pluginSpawnArgs: string[]
  codexClient?: CodexRpc
  opencodeClient?: OpenCodeCommandClient
  opencodePluginDirs?: string[]
  grokCommands?: GrokAcpCommand[]
  grokSkillsDirs?: string[]
}

export interface CommandRegistryDeps {
  providers: Partial<Record<AgentKind, AgentCommandProvider>>
  /** Look up the current session view, or undefined if gone. */
  resolveSession: (name: string) => RegistrySession | undefined
  /** Optional: called whenever a session's merged list changes. */
  onChange?: (name: string, commands: SlashCommand[]) => void
}

export interface CommandPreviewRequest {
  kind: AgentKind
  workdir: string
  pluginSpawnArgs: string[]
  codexClient?: CodexRpc
  opencodeClient?: OpenCodeCommandClient
  opencodePluginDirs?: string[]
  grokSkillsDirs?: string[]
}

export type PreviewAgentCommandsCtx = CommandPreviewRequest

export class CommandRegistry {
  private agentCache = new Map<string, SlashCommand[]>()
  private inflight = new Map<string, Promise<void>>()
  private resolved = new Set<string>()
  private previewCache = new Map<string, SlashCommand[]>()
  private previewInflight = new Map<string, Promise<void>>()
  private previewResolved = new Set<string>()
  constructor(private deps: CommandRegistryDeps) {}

  /** True once agent-command discovery has completed for the session (even if empty). */
  isResolved(name: string): boolean {
    return this.resolved.has(name)
  }

  /** Merged list: control (always fresh from session state) + cached agent commands. */
  get(name: string): SlashCommand[] {
    const session = this.deps.resolveSession(name)
    if (!session) return []
    const control = controlCommands({ muted: session.muted })
    const agent = this.agentCache.get(name) ?? []
    return [...control, ...agent]
  }

  /** (Re)compute agent commands for a session. Dedupes concurrent calls. */
  refresh(name: string): Promise<void> {
    const existing = this.inflight.get(name)
    if (existing) return existing
    const p = this.run(name).finally(() => this.inflight.delete(name))
    this.inflight.set(name, p)
    return p
  }

  /** True once agent-command discovery has completed for a launcher preview. */
  isPreviewResolved(kind: AgentKind, workdir: string): boolean {
    return this.previewResolved.has(this.previewKey(kind, workdir))
  }

  /** Cached agent-command preview for sessions that have not been spawned yet. */
  getPreview(kind: AgentKind, workdir: string): SlashCommand[] {
    return this.previewCache.get(this.previewKey(kind, workdir)) ?? []
  }

  /** (Re)compute agent commands for a launcher preview. Dedupes concurrent calls. */
  refreshPreview(req: CommandPreviewRequest): Promise<void> {
    const key = this.previewKey(req.kind, req.workdir)
    const existing = this.previewInflight.get(key)
    if (existing) return existing
    const p = this.runPreview(key, req).finally(() => this.previewInflight.delete(key))
    this.previewInflight.set(key, p)
    return p
  }

  private previewKey(kind: AgentKind, workdir: string): string {
    return `${kind}\0${workdir}`
  }

  private async run(name: string): Promise<void> {
    const session = this.deps.resolveSession(name)
    if (!session) return
    const provider = this.deps.providers[session.kind]
    const cmds = provider
      ? await provider.list({
          sessionName: session.name,
          workdir: session.workdir,
          pluginSpawnArgs: session.pluginSpawnArgs,
          codexClient: session.codexClient,
          opencodeClient: session.opencodeClient,
          opencodePluginDirs: session.opencodePluginDirs,
          grokCommands: session.grokCommands,
          grokSkillsDirs: session.grokSkillsDirs,
        }).catch(() => [] as SlashCommand[])
      : []
    this.agentCache.set(name, cmds)
    this.resolved.add(name)
    log.debug("agent_commands_resolved", { session: name, kind: session.kind, count: cmds.length })
    this.deps.onChange?.(name, this.get(name))
  }

  private async runPreview(key: string, req: CommandPreviewRequest): Promise<void> {
    const provider = this.deps.providers[req.kind]
    const cmds = provider
      ? await provider.list({
          sessionName: `preview:${req.kind}`,
          workdir: req.workdir,
          pluginSpawnArgs: req.pluginSpawnArgs,
          codexClient: req.codexClient,
          opencodeClient: req.opencodeClient,
          opencodePluginDirs: req.opencodePluginDirs,
          grokSkillsDirs: req.grokSkillsDirs,
        }).catch(() => [] as SlashCommand[])
      : []
    this.previewCache.set(key, cmds)
    this.previewResolved.add(key)
    log.debug("agent_commands_preview_resolved", { kind: req.kind, workdir: req.workdir, count: cmds.length })
  }

  /** Drop cached agent commands so the next refresh re-runs the provider. */
  invalidate(name: string): void {
    this.agentCache.delete(name)
  }

  /** Invalidate + recompute all known sessions (e.g. plugin registry change). */
  async invalidateAll(names: string[]): Promise<void> {
    for (const n of names) this.invalidate(n)
    await Promise.all(names.map((n) => this.refresh(n)))
  }

  remove(name: string): void {
    this.agentCache.delete(name)
    this.inflight.delete(name)
    this.resolved.delete(name)
  }
}
