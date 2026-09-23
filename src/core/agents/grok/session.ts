import { deriveName, ensureUnique } from "../../session-manager/naming"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import type { GrokAcpCommand } from "../../slash-commands/types"
import { CoreAdapter, GROK_CORE_PROFILE } from "../core-bridge/core-adapter"
import { getGrokCoreHost } from "./core-host-provider"
import type { GrokCoreHost, GrokPrepareExtra } from "./core-host"
import { join } from "path"
import { randomUUID } from "crypto"
import { STATE_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import { resolvePermissionMode } from "../permission-modes"
import { grokConfigEntries } from "../../plugins"
import type { Core, HostHandle } from "../../../../packages/supermux-core/src/index.js"

export type GrokCommandContext = {
  commands?: GrokAcpCommand[]
  skillsDirs: string[]
}

export function commandContext(ctx: CommandContextCtx): GrokCommandContext {
  return {
    commands: (ctx.adapter as { availableCommands?: GrokAcpCommand[] } | undefined)?.availableCommands,
    skillsDirs: grokConfigEntries({ sessionName: ctx.sessionName }).skillsPaths,
  }
}

export type GrokSessionAdapter = CoreAdapter

function resolveHost(explicit?: GrokCoreHost): GrokCoreHost {
  return explicit ?? getGrokCoreHost()
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

function persistNativeId(
  onGrokSessionId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onGrokSessionId?.(name, sid) }
}

function createBoundAdapter(opts: {
  handle: HostHandle
  reregister: (fields: { model?: string; permissionMode?: string }) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  model?: string
  effort?: string
  permissionMode?: string
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreAdapter {
  return new CoreAdapter(GROK_CORE_PROFILE, {
    handle: opts.handle,
    reregister: opts.reregister,
    core: opts.core,
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    effort: opts.effort,
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
}): GrokPrepareExtra {
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

/** grok's worker is an in-process CoreAdapter driving a `grok agent stdio`
 * child via a process-owned GrokCoreHost. Unlike cursor (per-turn CLI) the child
 * is persistent; unlike codex/opencode it's owned by the adapter, so there's no
 * separate handle and no pid to track — the row is registered with pid 0 and
 * adapter.stop() is the kill.
 *
 * MCP + skills live in the session-private config.toml (mux-shim). Grok does
 * not take MCP servers inline via ACP session/new. The identity preamble is
 * AGENTS.md in the workdir (git-excluded, override-safe). sessionHome is the
 * redirected HOME plus agent_home for resume. Private-home writes run in the
 * host prepare hook, after admission. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.grokHost)
  const sessionHome = join(STATE_DIR, "agents", "grok", name)
  const permissionMode = resolvePermissionMode(AgentKind.Grok, args.permissionMode)
  const handle = host.register({
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
      effort: args.effort,
      persistSessionId: persistNativeId(deps.onGrokSessionId, name),
      resolveAttachment: deps.resolveAttachment,
      permissionMode,
    })

    // Register BEFORE adapter.start(): start() completes the ACP handshake, which
    // fires persistSessionId — that callback resolves the row by name, so the row
    // must already exist or the grok session id is lost (breaking resume).
    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.Grok,
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
        agent: AgentKind.Grok,
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

/** Rebuild a grok session's adapter after a broker restart. Native history
 * lives under the session-private home; resume adopts the existing broker id
 * and native session id, then exact-resumes. A failed load does not mint a
 * new conversation. Self-heals private config, credentials and preamble. */
export async function resumeGrokSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onGrokSessionId?: (name: string, sid: string) => void
    grokHost?: GrokCoreHost
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; effort?: string; agent_session_id?: string; permissionMode?: string },
): Promise<{ adapter: CoreAdapter }> {
  const host = resolveHost(deps.grokHost)
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
      effort: session.effort,
      permissionMode: session.permissionMode ?? undefined,
      initialSessionId,
      persistSessionId: persistNativeId(deps.onGrokSessionId, session.name),
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

/** Dialect half of a model/effort change. Core Grok applies both halves through
 * setConfiguration (native configure / child reopen with the same identity).
 * `changed` narrows to what the user actually touched. Busy is typed, not a
 * successful apply. Legacy GrokAdapter remains for older tests. */
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
  if (!live) return { ok: false, error: "grok session has no live adapter" }
  const patch: { model?: string; effort?: string } = {}
  if (change.changed?.model !== false && change.model) patch.model = change.model
  if (change.changed?.effort !== false && "effort" in change) patch.effort = change.effort
  if (!("model" in patch) && !("effort" in patch)) return { ok: true }
  try {
    await live.setConfiguration(patch)
    return { ok: true }
  } catch (err) {
    if (isSessionBusy(err)) return { ok: false, busy: true }
    return { ok: false, error: asError(err).message }
  }
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreAdapter }> {
  return resumeGrokSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onGrokSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      grokHost: ctx.grokHost,
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, effort: ctx.sessionEffort(session), agent_session_id: session.agent_session_id, permissionMode: session.permissionMode ?? undefined },
  )
}
