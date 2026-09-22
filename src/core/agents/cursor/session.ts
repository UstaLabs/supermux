import { deriveName, ensureUnique } from "../../session-manager/naming"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import { CoreCursorAdapter } from "./core-adapter"
import { getCursorCoreHost } from "./core-host-provider"
import type { CursorCoreHost, CursorPrepareExtra } from "./core-host"
import { join } from "path"
import { randomUUID } from "crypto"
import { STATE_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import type { Core, HostHandle } from "../../../../packages/supermux-core/src/index.js"

function resolveHost(explicit?: CursorCoreHost): CursorCoreHost {
  return explicit ?? getCursorCoreHost()
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

function persistNativeId(
  onCursorSessionId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onCursorSessionId?.(name, sid) }
}

function createBoundAdapter(opts: {
  handle: HostHandle
  reregister: (model?: string) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  model?: string
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreCursorAdapter {
  return new CoreCursorAdapter({
    handle: opts.handle,
    reregister: opts.reregister,
    core: opts.core,
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    initialSessionId: opts.initialSessionId,
    persistSessionId: opts.persistSessionId,
    resolveAttachment: opts.resolveAttachment,
  })
}

function prepareExtra(opts: {
  id: string
  sessionName: string
  sessionHome: string
  workdir: string
  nativeSessionId?: string
  model?: string
}): CursorPrepareExtra {
  return {
    sessionHome: opts.sessionHome,
    sessionName: opts.sessionName,
    sessionId: opts.id,
    workdir: opts.workdir,
    cwd: opts.workdir,
    nativeSessionId: opts.nativeSessionId,
    model: opts.model,
  }
}

/** cursor's worker is an in-process CoreCursorAdapter driving `cursor-agent`
 * via a process-owned CursorCoreHost. The row is registered with pid 0 and
 * adapter.stop() is the kill. Config/preamble writes run in the host prepare hook. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.cursorHost)
  const sessionHome = join(STATE_DIR, "agents", "cursor", name)
  const handle = host.register({
    id,
    env: {},
    extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir, model: args.model }),
  })
  let adapter: CoreCursorAdapter | undefined
  try {
    await deps.bind(id)

    adapter = createBoundAdapter({
      handle,
      reregister: (model) => host.register({
        id,
        env: {},
        extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir, model }),
      }),
      core: host.core,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      persistSessionId: persistNativeId(deps.onCursorSessionId, name),
      resolveAttachment: deps.resolveAttachment,
    })

    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.Cursor,
          workdir: args.workdir,
          model: args.model,
          reasoningLevel: args.reasoningLevel,
          pid: 0,
          is_default: deps.registry.listPAs().length === 0,
          agent_home: sessionHome,
          base_commits: captureBaseCommits(args.workdir),
        })
      }
    } else {
      deps.registry.register({
        id,
        name,
        workdir: args.workdir,
        pid: 0,
        agent: AgentKind.Cursor,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(args.workdir),
        internal: args.internal,
      } as never)
    }

    await adapter.start()
  } catch (err) {
    try {
      if (adapter) await adapter.stop()
      else await handle.stop({ mode: "shutdown" })
    } catch {
      // Failed stop leaves failed-cleanup on the host; the original error is the one to report.
    }
    throw err
  }

  deps.registerAdapter?.(name, adapter, { onExit: () => {} })

  return { name, session_id: id, model: args.model, pid: 0 }
}

export async function resumeCursorSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onCursorSessionId?: (name: string, sid: string) => void
    cursorHost?: CursorCoreHost
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; agent_session_id?: string },
): Promise<{ adapter: CoreCursorAdapter }> {
  const host = resolveHost(deps.cursorHost)
  const sessionHome = session.agent_home
  const initialSessionId = session.agent_session_id || undefined
  const handle = host.register({
    id: session.id,
    env: {},
    extra: prepareExtra({
      id: session.id,
      sessionName: session.name,
      sessionHome,
      workdir: session.workdir,
      nativeSessionId: initialSessionId,
      model: session.model,
    }),
  })
  let adapter: CoreCursorAdapter | undefined
  try {
    adapter = createBoundAdapter({
      handle,
      reregister: (model) => host.register({
        id: session.id,
        env: {},
        extra: prepareExtra({
          id: session.id,
          sessionName: session.name,
          sessionHome,
          workdir: session.workdir,
          nativeSessionId: initialSessionId,
          model,
        }),
      }),
      core: host.core,
      id: session.id,
      sessionName: session.name,
      workdir: session.workdir,
      model: session.model,
      initialSessionId,
      persistSessionId: persistNativeId(deps.onCursorSessionId, session.name),
      resolveAttachment: deps.resolveAttachment,
    })
    if (initialSessionId) await adapter.resume()
    else await adapter.start()
    return { adapter }
  } catch (err) {
    try {
      if (adapter) await adapter.stop()
      else await handle.stop({ mode: "shutdown" })
    } catch {
      // Failed stop leaves failed-cleanup on the host; the original error is the one to report.
    }
    throw err
  }
}

export async function applyConfig(
  ctx: ApplyConfigCtx,
  _session: ApplyConfigRow,
  _name: string,
  change: ApplyConfigChange,
): Promise<ApplyConfigResult> {
  if (change.changed?.effort !== false && "effort" in change && change.effort !== undefined) {
    return { ok: false, error: "cursor sessions use model selection for reasoning depth" }
  }
  const adapter = ctx.adapter
  const coreAdapter = adapter instanceof CoreCursorAdapter
    ? adapter
    : (adapter && typeof (adapter as CoreCursorAdapter).setConfiguration === "function"
      ? adapter as CoreCursorAdapter
      : undefined)
  if (!coreAdapter) return { ok: true }
  const patch: { model?: string } = {}
  if (change.changed?.model !== false && change.model) patch.model = change.model
  if (!("model" in patch)) return { ok: true }
  try {
    await coreAdapter.setConfiguration(patch)
    return { ok: true }
  } catch (err) {
    if (isSessionBusy(err)) return { ok: false, busy: true }
    return { ok: false, error: asError(err).message }
  }
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreCursorAdapter }> {
  return resumeCursorSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onCursorSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      cursorHost: ctx.cursorHost,
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, agent_session_id: session.agent_session_id },
  )
}
