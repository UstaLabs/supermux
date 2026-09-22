import { deriveName, ensureUnique } from "../../session-manager/naming"
import { buildClaudeSpawnSpec } from "../../session-manager/spawn-command"
import { preAcceptTrust } from "../../session-manager/trust"
import { sendChannelConsentEnter } from "../../session-manager/post-spawn-keys"
import { getSessionBackend } from "../../runtime"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import { applyClaudeLiveSwitch } from "./live-switch"
import { randomUUID } from "crypto"
import { AgentKind } from "../../../shared/agents"
import { CoreClaudeAdapter } from "./core-adapter"
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
}): CoreClaudeAdapter {
  return new CoreClaudeAdapter({
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

async function spawnTmux(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const backend = getSessionBackend()
  const base = args.requestedName ?? deriveName(args.workdir)
  let name: string
  if (args.pa) {
    name = base
  } else {
    const existingWindows = (await backend.list(deps.tmuxSession)).map(target => target.name)
    name = ensureUnique(base, new Set([...deps.registry.takenNames(), ...existingWindows]))
  }
  const id = args.id ?? randomUUID()
  const claudeSessionId = randomUUID()
  if (!args.pa) deps.registry.reserveName(name)
  preAcceptTrust(args.workdir)
  try {
    await deps.bind(id)
    if (args.pa && !args.pa.skipRegister) {
      deps.registry.registerPA({
        id,
        name,
        agent: AgentKind.Claude,
        workdir: args.workdir,
        model: args.model,
        reasoningLevel: args.reasoningLevel,
        pid: process.pid,
        is_default: deps.registry.listPAs().length === 0,
      })
    }
    const spec = buildClaudeSpawnSpec({
      name, model: args.model, effort: args.effort, sessionId: id, claudeSessionId, workdir: args.workdir,
      ...(args.pa ? { sessionRole: "personal_assistant" as const } : { rpcMcpConfig: args.rpcMcpConfig }),
    })
    const target = await backend.create({
      group: deps.tmuxSession,
      name,
      cwd: args.workdir,
      ...spec,
      cols: 80,
      rows: 24,
    })
    if (args.pa) {
      deps.registry.sessions.setTmuxWindowId(id, target.id)
      deps.registry.sessions.setAgentSessionId(id, claudeSessionId)
      void sendChannelConsentEnter(target.id, { backend })
    } else {
      deps.registry.register({
        id,
        name,
        agent: AgentKind.Claude,
        workdir: args.workdir,
        tmux_target: `${deps.tmuxSession}:${name}`,
        tmux_window_id: target.id,
        pid: target.pid ?? process.pid,
        agent_session_id: claudeSessionId,
        internal: args.internal,
        connected: false,
        base_commits: captureBaseCommits(args.workdir),
      })
      await sendChannelConsentEnter(target.id, { backend })
    }
    return { name, session_id: id, model: args.model, pid: target.pid ?? process.pid }
  } catch (err) {
    if (!args.pa) {
      deps.registry.releaseName(name)
      if (deps.registry.get(id)) deps.registry.sessions.deleteById(id)
    }
    throw err
  }
}

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  if (args.pa) return spawnTmux(deps, args)

  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  deps.registry.reserveName(name)

  const host = resolveHost(deps.claudeHost)
  const sessionHome = claudeSessionHome(name)
  const extra = prepareExtra({
    id, sessionName: name, sessionHome, workdir: args.workdir,
    model: args.model, effort: args.effort, prompts: false,
  })
  const handle = host.register({
    id,
    env: {},
    extra,
  })
  let adapter: CoreClaudeAdapter | undefined
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
    await adapter.start()
  } catch (err) {
    try {
      if (adapter) await adapter.stop()
      else await handle.stop({ mode: "shutdown" })
    } catch { /* failed-cleanup */ }
    // A failed spawn must not leave a dead row or a held name behind: the
    // row was registered before start so the native-id callback could find
    // it, and the reservation is what a retry needs back.
    deps.registry.releaseName(name)
    if (deps.registry.get(id)) deps.registry.unregister(id)
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
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; effort?: string; agent_session_id?: string; prompts?: boolean },
): Promise<{ adapter: CoreClaudeAdapter }> {
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
  })
  const handle = host.register({ id: session.id, env: {}, extra })
  let adapter: CoreClaudeAdapter | undefined
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
  const coreAdapter = adapter instanceof CoreClaudeAdapter
    ? adapter
    : (adapter && typeof (adapter as CoreClaudeAdapter).setConfiguration === "function" && !ctx.windowId
      ? adapter as CoreClaudeAdapter
      : undefined)
  if (coreAdapter) {
    const adapter = coreAdapter
    const patch: { model?: string; effort?: string } = {}
    if (change.changed?.model !== false && change.model) patch.model = change.model
    if (change.changed?.effort !== false && "effort" in change) patch.effort = change.effort
    if (!("model" in patch) && !("effort" in patch)) return { ok: true }
    try {
      await adapter.setConfiguration(patch)
      return { ok: true }
    } catch (err) {
      if (isSessionBusy(err)) return { ok: false, busy: true }
      return { ok: false, error: asError(err).message }
    }
  }
  if (!ctx.windowId) return { ok: false, error: "session window not found" }
  return applyClaudeLiveSwitch(ctx.windowId, {
    model: change.changed?.model === false ? undefined : change.model,
    effort: change.changed?.effort === false ? undefined : change.effort,
  }, { backend: ctx.backend })
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreClaudeAdapter }> {
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
    },
  )
}

export function commandContext(_ctx: CommandContextCtx): undefined {
  return undefined
}
