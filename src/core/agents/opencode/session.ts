import { deriveName, ensureUnique } from "../../session-manager/naming"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import type { OpenCodeCommandClient } from "../../slash-commands/types"
import { CoreAdapter, OPENCODE_CORE_PROFILE } from "../core-bridge/core-adapter"
import { getOpenCodeCoreHost } from "./core-host-provider"
import type { OpenCodeCoreHost, OpenCodePrepareExtra } from "./core-host"
import { opencodeConfigEntries } from "../../plugins"
import { join } from "path"
import { randomUUID } from "crypto"
import { STATE_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import { resolvePermissionMode } from "../permission-modes"
import type { Core, HostHandle } from "../../../../packages/supermux-core/src/index.js"

/** Slash-command discovery context for the opencode provider. */
export type OpenCodeCommandContext = {
  /** Live ACP adapter does not expose the HTTP SDK client; disk scan is used. */
  client?: OpenCodeCommandClient
  /** Enabled plugin roots for the disk-scan preview / client fallback. */
  pluginDirs: string[]
}

export function commandContext(ctx: CommandContextCtx): OpenCodeCommandContext {
  return {
    pluginDirs: opencodeConfigEntries({ sessionName: ctx.sessionName }).pluginPaths,
  }
}

function resolveHost(explicit?: OpenCodeCoreHost): OpenCodeCoreHost {
  return explicit ?? getOpenCodeCoreHost()
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

function persistNativeId(
  onOpenCodeSessionId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onOpenCodeSessionId?.(name, sid) }
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
  return new CoreAdapter(OPENCODE_CORE_PROFILE, {
    handle: opts.handle,
    reregister: opts.reregister,
    permissionMode: opts.permissionMode,
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
  permissionMode?: string
}): OpenCodePrepareExtra {
  return {
    sessionHome: opts.sessionHome,
    sessionName: opts.sessionName,
    sessionId: opts.id,
    workdir: opts.workdir,
    cwd: opts.workdir,
    nativeSessionId: opts.nativeSessionId,
    model: opts.model,
    permissionMode: opts.permissionMode,
  }
}

/** opencode's worker is an in-process CoreAdapter driving `opencode acp`
 * via a process-owned OpenCodeCoreHost. The row is registered with pid 0 and
 * adapter.stop() is the kill. Config/preamble writes run in the host prepare hook. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.opencodeHost)
  const sessionHome = join(STATE_DIR, "agents", "opencode", name)
  const permissionMode = resolvePermissionMode(AgentKind.OpenCode, args.permissionMode)
  const handle = host.register({
    id,
    env: {},
    extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir, model: args.model, permissionMode }),
  })
  let adapter: CoreAdapter | undefined
  try {
    await deps.bind(id)

    adapter = createBoundAdapter({
      handle,
      reregister: (fields) => host.register({
        id,
        env: {},
        extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir, model: fields.model, permissionMode: fields.permissionMode }),
      }),
      core: host.core,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      persistSessionId: persistNativeId(deps.onOpenCodeSessionId, name),
      resolveAttachment: deps.resolveAttachment,
      permissionMode,
    })

    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.OpenCode,
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
        agent: AgentKind.OpenCode,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(args.workdir),
        internal: args.internal,
        permissionMode,
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

export async function resumeOpenCodeSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onOpenCodeSessionId?: (name: string, sid: string) => void
    opencodeHost?: OpenCodeCoreHost
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; agent_session_id?: string; permissionMode?: string },
): Promise<{ adapter: CoreAdapter }> {
  const host = resolveHost(deps.opencodeHost)
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
          model: fields.model,
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
      persistSessionId: persistNativeId(deps.onOpenCodeSessionId, session.name),
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
    return { ok: false, error: "opencode does not support reasoning effort" }
  }
  const adapter = ctx.adapter
  const coreAdapter = adapter instanceof CoreAdapter
    ? adapter
    : (adapter && typeof (adapter as CoreAdapter).setConfiguration === "function"
      ? adapter as CoreAdapter
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

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreAdapter }> {
  return resumeOpenCodeSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onOpenCodeSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      opencodeHost: ctx.opencodeHost,
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, agent_session_id: session.agent_session_id, permissionMode: session.permissionMode ?? undefined },
  )
}
