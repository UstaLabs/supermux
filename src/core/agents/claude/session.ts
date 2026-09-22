import { deriveName, ensureUnique } from "../../session-manager/naming"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import { randomUUID } from "crypto"
import { AgentKind } from "../../../shared/agents"
import { CoreAdapter, CLAUDE_CORE_PROFILE } from "../core-bridge/core-adapter"
import { getClaudeCoreHost } from "./core-host-provider"
import { claudeSessionHome, type ClaudeCoreHost, type ClaudePrepareExtra } from "./core-host"
import type { Core, HostHandle } from "../../../../packages/supermux-core/src/index.js"

function resolveHost(explicit?: ClaudeCoreHost): ClaudeCoreHost {
  return explicit ?? getClaudeCoreHost()
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

function persistNativeId(
  onClaudeSessionId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onClaudeSessionId?.(name, sid) }
}

function prepareExtra(opts: {
  id: string
  sessionName: string
  sessionHome: string
  workdir: string
  nativeSessionId?: string
  model?: string
  effort?: string
  prompts?: boolean
  pa?: boolean
  rpcMcpConfig?: string
}): ClaudePrepareExtra {
  return {
    sessionHome: opts.sessionHome,
    sessionName: opts.sessionName,
    sessionId: opts.id,
    workdir: opts.workdir,
    cwd: opts.workdir,
    nativeSessionId: opts.nativeSessionId,
    model: opts.model,
    effort: opts.effort,
    prompts: opts.prompts === true,
    pa: opts.pa === true,
    rpcMcpConfig: opts.rpcMcpConfig,
  }
}

function createBoundAdapter(opts: {
  handle: HostHandle
  reregister: (fields: { model?: string; prompts?: boolean }) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  model?: string
  effort?: string
  prompts?: boolean
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreAdapter {
  return new CoreAdapter(CLAUDE_CORE_PROFILE, {
    handle: opts.handle,
    reregister: opts.reregister,
    core: opts.core,
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    effort: opts.effort,
    prompts: opts.prompts,
    initialSessionId: opts.initialSessionId,
    persistSessionId: opts.persistSessionId,
    resolveAttachment: opts.resolveAttachment,
  })
}

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.claudeHost)
  const sessionHome = claudeSessionHome(name)
  const extra = prepareExtra({
    id, sessionName: name, sessionHome, workdir: args.workdir,
    model: args.model, effort: args.effort, prompts: false,
    pa: !!args.pa, rpcMcpConfig: args.rpcMcpConfig,
  })
  const handle = host.register({
    id,
    env: {},
    extra,
  })
  let adapter: CoreAdapter | undefined
  try {
    await deps.bind(id)
    adapter = createBoundAdapter({
      handle,
      reregister: (fields) => host.register({
        id,
        env: {},
        extra: prepareExtra({
          id, sessionName: name, sessionHome, workdir: args.workdir,
          model: fields.model ?? args.model, effort: args.effort, prompts: fields.prompts,
          pa: !!args.pa, rpcMcpConfig: args.rpcMcpConfig,
        }),
      }),
      core: host.core,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      effort: args.effort,
      persistSessionId: persistNativeId(deps.onClaudeSessionId, name),
      resolveAttachment: deps.resolveAttachment,
    })
    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.Claude,
          workdir: args.workdir,
          model: args.model,
          reasoningLevel: args.reasoningLevel,
          pid: 0,
          is_default: deps.registry.listPAs().length === 0,
          agent_home: sessionHome,
          core: true,
        })
      }
    } else {
      deps.registry.register({
        id,
        name,
        workdir: args.workdir,
        pid: 0,
        agent: AgentKind.Claude,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(args.workdir),
        internal: args.internal,
        core: true,
      } as any)
    }
    await adapter.start()
  } catch (err) {
    try {
      if (adapter) await adapter.stop()
      else await handle.stop({ mode: "shutdown" })
    } catch { /* failed-cleanup */ }
    if (!args.pa) deps.registry.releaseName(name)
    if (!args.pa?.skipRegister && deps.registry.get(id)) deps.registry.unregister(id)
    throw err
  }
  deps.registerAdapter?.(name, adapter, { onExit: () => {} })
  return { name, session_id: id, model: args.model, pid: 0 }
}

export async function resumeClaudeSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onClaudeSessionId?: (name: string, sid: string) => void
    claudeHost?: ClaudeCoreHost
  },
  session: {
    id: string
    name: string
    workdir: string
    agent_home: string
    model?: string
    effort?: string
    agent_session_id?: string
    prompts?: boolean
    pa?: boolean
    rpcMcpConfig?: string
  },
): Promise<{ adapter: CoreAdapter }> {
  const host = resolveHost(deps.claudeHost)
  const sessionHome = session.agent_home
  const initialSessionId = session.agent_session_id || undefined
  const extra = prepareExtra({
    id: session.id,
    sessionName: session.name,
    sessionHome,
    workdir: session.workdir,
    nativeSessionId: initialSessionId,
    model: session.model,
    effort: session.effort,
    prompts: session.prompts,
    pa: session.pa,
    rpcMcpConfig: session.rpcMcpConfig,
  })
  const handle = host.register({ id: session.id, env: {}, extra })
  let adapter: CoreAdapter | undefined
  try {
    adapter = createBoundAdapter({
      handle,
      reregister: (fields) => host.register({
        id: session.id,
        env: {},
        extra: prepareExtra({
          id: session.id,
          sessionName: session.name,
          sessionHome,
          workdir: session.workdir,
          nativeSessionId: initialSessionId,
          model: fields.model ?? session.model,
          effort: session.effort,
          prompts: fields.prompts,
          pa: session.pa,
          rpcMcpConfig: session.rpcMcpConfig,
        }),
      }),
      core: host.core,
      id: session.id,
      sessionName: session.name,
      workdir: session.workdir,
      model: session.model,
      effort: session.effort,
      prompts: session.prompts,
      initialSessionId,
      persistSessionId: persistNativeId(deps.onClaudeSessionId, session.name),
      resolveAttachment: deps.resolveAttachment,
    })
    if (initialSessionId) await adapter.resume()
    else await adapter.start()
    return { adapter }
  } catch (err) {
    try {
      if (adapter) await adapter.stop()
      else await handle.stop({ mode: "shutdown" })
    } catch { /* */ }
    throw err
  }
}

export async function applyConfig(
  ctx: ApplyConfigCtx,
  _session: ApplyConfigRow,
  _name: string,
  change: ApplyConfigChange,
): Promise<ApplyConfigResult> {
  const adapter = ctx.adapter
  const coreAdapter = adapter instanceof CoreAdapter
    ? adapter
    : (adapter && typeof (adapter as CoreAdapter).setConfiguration === "function"
      ? adapter as CoreAdapter
      : undefined)
  if (coreAdapter) {
    const patch: { model?: string; effort?: string } = {}
    if (change.changed?.model !== false && change.model) patch.model = change.model
    if (change.changed?.effort !== false && "effort" in change) patch.effort = change.effort
    if (!("model" in patch) && !("effort" in patch)) return { ok: true }
    try {
      await coreAdapter.setConfiguration(patch)
      return { ok: true }
    } catch (err) {
      if (isSessionBusy(err)) return { ok: false, busy: true }
      return { ok: false, error: asError(err).message }
    }
  }
  return { ok: false, error: "claude adapter not found" }
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreAdapter }> {
  return resumeClaudeSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onClaudeSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      claudeHost: ctx.claudeHost,
    },
    {
      id: session.id,
      name,
      workdir: session.workdir,
      agent_home: session.agent_home,
      model: session.model,
      effort: ctx.sessionEffort(session),
      agent_session_id: session.agent_session_id,
      prompts: session.prompts,
      pa: session.role === "personal_assistant",
    },
  )
}

export function commandContext(_ctx: CommandContextCtx): undefined {
  return undefined
}
