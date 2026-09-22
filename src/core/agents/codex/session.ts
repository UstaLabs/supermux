import { deriveName, ensureUnique } from "../../session-manager/naming"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import type { CodexRpc } from "../../slash-commands/types"
import { CodexAdapter } from "./adapter"
import { CoreCodexAdapter } from "./core-adapter"
import { getCodexCoreHost } from "./core-host-provider"
import { attachCodexRuntimeAdapter, type CodexCoreHost, type CodexPrepareExtra } from "./core-host"
import { codexSpawnArgs } from "../../plugins"
import { resolveCommand } from "../../process/launcher"
import { join } from "path"
import { randomUUID } from "crypto"
import { STATE_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import type { Core, HostHandle } from "../../../../packages/supermux-core/src/index.js"

/** Slash-command discovery context: the live app-server JSON-RPC client.
 * A session uses its own adapter's client; a launcher preview (no session of
 * its own) borrows any live codex adapter's — the skills list is global. */
export function commandContext(ctx: CommandContextCtx): CodexRpc | undefined {
  if (ctx.adapter) return (ctx.adapter as { rpc?: CodexRpc }).rpc
  for (const a of ctx.kindAdapters?.() ?? []) {
    const rpc = (a as { rpc?: CodexRpc } | undefined)?.rpc
    if (rpc) return rpc
  }
  return undefined
}

export type CodexSessionAdapter = CoreCodexAdapter | CodexAdapter

function resolveHost(explicit?: CodexCoreHost): CodexCoreHost {
  return explicit ?? getCodexCoreHost()
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

function brokerCodexArgs(sessionName: string): string[] {
  return [
    "app-server",
    "-c", 'approval_policy="never"',
    "-c", 'sandbox_mode="danger-full-access"',
    ...codexSpawnArgs({ sessionName }).args,
  ]
}

function resolveCodexCommand(env: Record<string, string>): string {
  return resolveCommand(["codex"], env, process.platform) ?? "codex"
}

function persistNativeId(
  onThreadId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onThreadId?.(name, sid) }
}

function createBoundAdapter(opts: {
  handle: HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  model?: string
  effort?: string
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreCodexAdapter {
  const adapter = new CoreCodexAdapter({
    handle: opts.handle,
    core: opts.core,
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    effort: opts.effort,
    initialThreadId: opts.initialSessionId,
    persistThreadId: opts.persistSessionId,
    resolveAttachment: opts.resolveAttachment,
  })
  attachCodexRuntimeAdapter(opts.id, adapter)
  return adapter
}

function prepareExtra(opts: {
  id: string
  sessionName: string
  sessionHome: string
  workdir: string
  nativeSessionId?: string
}): CodexPrepareExtra {
  return {
    sessionHome: opts.sessionHome,
    sessionName: opts.sessionName,
    sessionId: opts.id,
    workdir: opts.workdir,
    cwd: opts.workdir,
    nativeSessionId: opts.nativeSessionId,
  }
}

/** Codex's worker is an in-process CoreCodexAdapter driving app-server via a
 * process-owned CodexCoreHost. Private-home writes (auth, config.toml, preamble)
 * run in the host prepare hook, after admission. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.codexHost)
  const sessionHome = join(STATE_DIR, "agents", "codex", name)
  const handle = host.register({
    id,
    env: {},
    command: resolveCodexCommand({}),
    args: brokerCodexArgs(name),
    extra: prepareExtra({ id, sessionName: name, sessionHome, workdir: args.workdir }),
  })
  let adapter: CoreCodexAdapter | undefined
  try {
    await deps.bind(id)

    adapter = createBoundAdapter({
      handle,
      core: host.core,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      effort: args.effort,
      persistSessionId: persistNativeId(deps.onThreadId, name),
      resolveAttachment: deps.resolveAttachment,
    })

    // Register BEFORE adapter.start(): start() completes the native handshake, which
    // fires persistThreadId — that callback resolves the row by name, so the row
    // must already exist or the thread id is lost (breaking resume).
    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.Codex,
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
        agent: AgentKind.Codex,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(args.workdir),
        internal: args.internal,
      } as any)
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

/** Rebuild a Codex session's adapter after a broker restart. Native history
 * lives under the session-private home; resume adopts the existing broker id
 * and native session id, then exact-resumes. A failed load does not mint a
 * new conversation. Self-heals private config, credentials and preamble. */
export async function resumeCodexSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onThreadId?: (name: string, sid: string) => void
    codexHost?: CodexCoreHost
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; effort?: string; agent_session_id?: string },
): Promise<{ adapter: CoreCodexAdapter }> {
  const host = resolveHost(deps.codexHost)
  const sessionHome = session.agent_home
  const initialSessionId = session.agent_session_id || undefined
  const handle = host.register({
    id: session.id,
    env: {},
    command: resolveCodexCommand({}),
    args: brokerCodexArgs(session.name),
    extra: prepareExtra({
      id: session.id,
      sessionName: session.name,
      sessionHome,
      workdir: session.workdir,
      nativeSessionId: initialSessionId,
    }),
  })
  let adapter: CoreCodexAdapter | undefined
  try {
    adapter = createBoundAdapter({
      handle,
      core: host.core,
      id: session.id,
      sessionName: session.name,
      workdir: session.workdir,
      model: session.model,
      effort: session.effort,
      initialSessionId,
      persistSessionId: persistNativeId(deps.onThreadId, session.name),
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
  const adapter = ctx.adapter
  const coreAdapter = adapter instanceof CoreCodexAdapter
    ? adapter
    : (adapter && typeof (adapter as CoreCodexAdapter).setConfiguration === "function" && !(adapter instanceof CodexAdapter)
      ? adapter as CoreCodexAdapter
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
  return { ok: false, error: "codex session has no live adapter" }
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreCodexAdapter }> {
  return resumeCodexSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onThreadId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      codexHost: ctx.codexHost,
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, effort: ctx.sessionEffort(session), agent_session_id: session.agent_session_id },
  )
}
