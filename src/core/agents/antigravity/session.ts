import { deriveName, ensureUnique } from "../../session-manager/naming"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import { CoreAdapter, ANTIGRAVITY_CORE_PROFILE } from "../core-bridge/core-adapter"
import { getAntigravityCoreHost } from "./core-host-provider"
import type { AntigravityCoreHost, AntigravityPrepareExtra } from "./core-host"
import { join } from "path"
import { randomUUID } from "crypto"
import { STATE_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import { resolvePermissionMode } from "../permission-modes"
import { antigravitySkillsDirs } from "../../plugins"
import type { Core, HostHandle } from "../../../../packages/supermux-core/src/index.js"

export type AntigravityCommandContext = {
  skillsDirs: string[]
}

export function commandContext(ctx: CommandContextCtx): AntigravityCommandContext {
  return { skillsDirs: antigravitySkillsDirs(ctx.sessionName) }
}

export type AntigravitySessionAdapter = CoreAdapter

function resolveHost(explicit?: AntigravityCoreHost): AntigravityCoreHost {
  return explicit ?? getAntigravityCoreHost()
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

function persistNativeId(
  onAntigravitySessionId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onAntigravitySessionId?.(name, sid) }
}

function createBoundAdapter(opts: {
  handle: HostHandle
  reregister: (fields: { model?: string; permissionMode?: string }) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  model?: string
  permissionMode?: string
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreAdapter {
  return new CoreAdapter(ANTIGRAVITY_CORE_PROFILE, {
    handle: opts.handle,
    reregister: opts.reregister,
    core: opts.core,
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    permissionMode: opts.permissionMode,
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
  permissionMode?: string
}): AntigravityPrepareExtra {
  return {
    sessionHome: opts.sessionHome,
    sessionName: opts.sessionName,
    sessionId: opts.id,
    workdir: opts.workdir,
    cwd: opts.workdir,
    nativeSessionId: opts.nativeSessionId,
    permissionMode: opts.permissionMode,
  }
}

/** antigravity's worker is an in-process CoreAdapter driving one long-lived agy child
 * (stream-json over stdio, behind the keeper) via a process-owned AntigravityCoreHost.
 * The child is owned by the adapter, so there's no separate handle and no pid to
 * track — the row is registered with pid 0 and adapter.stop() is the kill.
 *
 * agy child under a shadow HOME (session home `<agent_home>/home`); instructions ride
 * the first prompt; nothing is written to the workdir or the real ~/.gemini. The
 * shadow HOME is (re)built in the host prepare hook, after admission. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.antigravityHost)
  const sessionHome = join(STATE_DIR, "agents", "antigravity", name)
  const permissionMode = resolvePermissionMode(AgentKind.Antigravity, args.permissionMode)
  const handle = host.register({
    ...(args.account ? { account: args.account } : {}),
    id,
    env: {},
    extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir, permissionMode }),
  })
  let adapter: CoreAdapter | undefined
  try {
    await deps.bind(id)

    adapter = createBoundAdapter({
      handle,
      reregister: (fields) => host.register({
        id,
        env: {},
        extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir, permissionMode: fields.permissionMode }),
      }),
      core: host.core,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      persistSessionId: persistNativeId(deps.onAntigravitySessionId, name),
      resolveAttachment: deps.resolveAttachment,
      permissionMode,
    })

    // Register BEFORE adapter.start(): start() opens the driver, which fires
    // persistSessionId — that callback resolves the row by name, so the row must
    // already exist or the agy conversation id is lost (breaking resume).
    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.Antigravity,
          workdir: args.workdir,
          model: args.model,
          reasoningLevel: args.reasoningLevel,
          pid: 0,
          is_default: deps.registry.listPAs().length === 0,
          agent_home: sessionHome,
          base_commits: captureBaseCommits(args.workdir),
          permissionMode,
        })
      }
    } else {
      deps.registry.register({
        id,
        name,
        workdir: args.workdir,
        pid: 0,
        agent: AgentKind.Antigravity,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(args.workdir),
        internal: args.internal,
        permissionMode,
        account: args.account,
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
    // A failed spawn must not leave a dead row or a held name behind: the
    // row was registered before start so the native-id callback could find
    // it, and the reservation is what a retry needs back.
    if (!args.pa) deps.registry.releaseName(name)
    if (!args.pa?.skipRegister && deps.registry.get(id)) deps.registry.unregister(id)
    throw err
  }

  deps.registerAdapter?.(name, adapter, { onExit: () => {} })

  return { name, session_id: id, model: args.model, pid: 0 }
}

/** Rebuild an antigravity session's adapter after a broker restart. Adopts the
 * existing broker id and agy conversation id; the driver respawns agy lazily
 * with `--conversation <id>`. The prepare hook refreshes the shadow HOME. */
export async function resumeAntigravitySession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onAntigravitySessionId?: (name: string, sid: string) => void
    antigravityHost?: AntigravityCoreHost
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; agent_session_id?: string; permissionMode?: string },
): Promise<{ adapter: CoreAdapter }> {
  const host = resolveHost(deps.antigravityHost)
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
      permissionMode: session.permissionMode ?? undefined,
    }),
  })
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
          permissionMode: fields.permissionMode,
        }),
      }),
      core: host.core,
      id: session.id,
      sessionName: session.name,
      workdir: session.workdir,
      model: session.model,
      permissionMode: session.permissionMode ?? undefined,
      initialSessionId,
      persistSessionId: persistNativeId(deps.onAntigravitySessionId, session.name),
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

/** Dialect half of a model change. agy bakes effort into the model id, so only
 * the model half is applied (setConfiguration → driver restart when idle); an
 * effort-only change is a no-op. Busy is typed, not a successful apply. */
export async function applyConfig(
  ctx: ApplyConfigCtx,
  _session: ApplyConfigRow,
  _name: string,
  change: ApplyConfigChange,
): Promise<ApplyConfigResult> {
  const adapter = ctx.adapter
  const live = adapter && typeof (adapter as CoreAdapter).setConfiguration === "function"
    ? adapter as CoreAdapter
    : undefined
  if (!live) return { ok: false, error: "antigravity session has no live adapter" }
  if (change.changed?.model === false || !change.model) return { ok: true }
  try {
    await live.setConfiguration({ model: change.model })
    return { ok: true }
  } catch (err) {
    if (isSessionBusy(err)) return { ok: false, busy: true }
    return { ok: false, error: asError(err).message }
  }
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreAdapter }> {
  return resumeAntigravitySession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onAntigravitySessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      antigravityHost: ctx.antigravityHost,
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, agent_session_id: session.agent_session_id, permissionMode: session.permissionMode ?? undefined },
  )
}
