import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import type { CodexRpc } from "../../slash-commands/types"
import { resolveCodexAuth } from "./auth"
import { writeCodexConfig } from "./config-writer"
import { writeCodexPreamble } from "./preamble-writer"
import { CodexAdapter } from "./adapter"
import { CoreCodexAdapter } from "./core-adapter"
import { getCodexCoreHost } from "./core-host-provider"
import type { CodexCoreHost } from "./core-host"
import { codexSpawnArgs, codexPrepareSessionHome } from "../../plugins"
import { resolveCommand } from "../../process/launcher"
import { join } from "path"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { STATE_DIR, SOCKETS_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"

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

function withCleanupError(original: unknown, cleanup: unknown): Error {
  const primary = asError(original)
  const secondary = asError(cleanup)
  return new Error(`${primary.message}; cleanup failed: ${secondary.message}`, { cause: primary })
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

async function startOrCleanup(host: CodexCoreHost, adapter: CoreCodexAdapter, mode: "start" | "resume"): Promise<void> {
  try {
    if (mode === "resume") await adapter.resume()
    else await adapter.start()
    markReady(host, adapter)
  } catch (startErr) {
    try {
      await adapter.stop()
      dropOwned(host, adapter)
    } catch (stopErr) {
      markFailedCleanup(host, adapter)
      throw withCleanupError(startErr, stopErr)
    }
    throw startErr
  }
}

function persistNativeId(
  onThreadId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onThreadId?.(name, sid) }
}

type SlotState = "admission" | "recovering" | "starting" | "ready" | "failed-cleanup"
type OwnedSlot =
  | { state: "admission"; token: symbol; adapter?: undefined }
  | { state: "recovering"; token: symbol; adapter: CoreCodexAdapter }
  | { state: "starting" | "ready" | "failed-cleanup"; adapter: CoreCodexAdapter }
const ownedByHost = new WeakMap<CodexCoreHost, Map<string, OwnedSlot>>()
const admissionTokenByHostId = new WeakMap<CodexCoreHost, Map<string, symbol>>()

function ownedMap(host: CodexCoreHost): Map<string, OwnedSlot> {
  let map = ownedByHost.get(host)
  if (!map) {
    map = new Map()
    ownedByHost.set(host, map)
  }
  return map
}

function tokenMap(host: CodexCoreHost): Map<string, symbol> {
  let map = admissionTokenByHostId.get(host)
  if (!map) {
    map = new Map()
    admissionTokenByHostId.set(host, map)
  }
  return map
}

function alreadyOwnedError(id: string, state: SlotState): Error {
  const phase = state === "ready"
    ? "live"
    : state === "failed-cleanup" || state === "recovering"
      ? "awaiting failed-start cleanup"
      : "starting"
  return new Error(`codex session ${id} is already ${phase}`)
}

function dropOwned(host: CodexCoreHost, adapter: CoreCodexAdapter): void {
  const map = ownedMap(host)
  const slot = map.get(adapter.id)
  if (slot?.adapter === adapter && slot.state !== "recovering") {
    map.delete(adapter.id)
    tokenMap(host).delete(adapter.id)
  }
}

function releaseOwnAdmission(host: CodexCoreHost, id: string, token: symbol): void {
  const map = ownedMap(host)
  const slot = map.get(id)
  if (slot?.state === "admission" && slot.token === token) {
    map.delete(id)
    tokenMap(host).delete(id)
  }
}

function markReady(host: CodexCoreHost, adapter: CoreCodexAdapter): void {
  const slot = ownedMap(host).get(adapter.id)
  if (slot?.adapter === adapter && slot.state === "starting") {
    ownedMap(host).set(adapter.id, { state: "ready", adapter })
    tokenMap(host).delete(adapter.id)
  }
}

function markFailedCleanup(host: CodexCoreHost, adapter: CoreCodexAdapter): void {
  const slot = ownedMap(host).get(adapter.id)
  if (slot?.adapter === adapter) {
    ownedMap(host).set(adapter.id, { state: "failed-cleanup", adapter })
    tokenMap(host).delete(adapter.id)
  }
}

function attachStarting(host: CodexCoreHost, adapter: CoreCodexAdapter): void {
  const map = ownedMap(host)
  const slot = map.get(adapter.id)
  const token = tokenMap(host).get(adapter.id)
  if (!slot || slot.state !== "admission" || slot.token !== token) {
    throw alreadyOwnedError(adapter.id, slot?.state ?? "ready")
  }
  map.set(adapter.id, { state: "starting", adapter })
}

function wrapStopToRelease(host: CodexCoreHost, adapter: CoreCodexAdapter): void {
  const original = adapter.stop.bind(adapter)
  adapter.stop = async () => {
    await original()
    dropOwned(host, adapter)
  }
}

async function stopAllocatedThenRelease(host: CodexCoreHost, adapter: CoreCodexAdapter, cause: unknown): Promise<never> {
  try {
    await adapter.stop()
    dropOwned(host, adapter)
  } catch (stopErr) {
    markFailedCleanup(host, adapter)
    throw withCleanupError(cause, stopErr)
  }
  throw asError(cause)
}

/** Retry a proven failed-start cleanup, then reserve admission before any
 * credential/config/home writes. Concurrent same-host/id starts reject. */
async function reserveAdmission(host: CodexCoreHost, id: string): Promise<symbol> {
  const map = ownedMap(host)
  const tokens = tokenMap(host)
  const prior = map.get(id)
  if (prior?.state === "failed-cleanup") {
    const recoverToken = Symbol("codex-recover")
    const failedOwner = prior.adapter
    map.set(id, { state: "recovering", token: recoverToken, adapter: failedOwner })
    try {
      await failedOwner.stop()
    } catch (stopErr) {
      const cur = map.get(id)
      if (cur?.state === "recovering" && cur.token === recoverToken && cur.adapter === failedOwner) {
        map.set(id, { state: "failed-cleanup", adapter: failedOwner })
      }
      throw asError(stopErr)
    }
    const cur = map.get(id)
    if (cur?.state !== "recovering" || cur.token !== recoverToken || cur.adapter !== failedOwner) {
      throw alreadyOwnedError(id, cur?.state ?? "ready")
    }
    const token = Symbol("codex-admission")
    map.set(id, { state: "admission", token })
    tokens.set(id, token)
    return token
  }
  if (prior) throw alreadyOwnedError(id, prior.state)
  const token = Symbol("codex-admission")
  map.set(id, { state: "admission", token })
  tokens.set(id, token)
  return token
}

function createBoundAdapter(opts: {
  host: CodexCoreHost
  id: string
  sessionName: string
  workdir: string
  model?: string
  effort?: string
  env: Record<string, string>
  command: string
  args: string[]
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreCodexAdapter {
  const adapter = opts.host.createAdapter({
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    effort: opts.effort,
    env: opts.env,
    command: opts.command,
    args: opts.args,
    initialThreadId: opts.initialSessionId,
    persistThreadId: opts.persistSessionId,
    resolveAttachment: opts.resolveAttachment,
  })
  attachStarting(opts.host, adapter)
  wrapStopToRelease(opts.host, adapter)
  return adapter
}

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.codexHost)
  const admissionToken = await reserveAdmission(host, id)
  const sessionHome = join(STATE_DIR, "agents", "codex", name)
  let adapter: CoreCodexAdapter | undefined
  try {
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth = await resolveCodexAuth({
      apiKey: process.env.OPENAI_API_KEY,
      userCodexHome: join(HOME, ".codex"),
      sessionCodexHome: sessionHome,
    })
    writeCodexConfig({
      codexHome: sessionHome,
      ...shimSpawnSpec(),
      sessionName: name,
      socketsDir: SOCKETS_DIR,
      sessionId: id,
    })
    writeCodexPreamble({ codexHome: sessionHome, sessionName: name, workdir: args.workdir })
    await deps.bind(id)
    await codexPrepareSessionHome(sessionHome)

    const env = { ...auth.env, CODEX_HOME: sessionHome }
    adapter = createBoundAdapter({
      host,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      effort: args.effort,
      env,
      command: resolveCodexCommand(env),
      args: brokerCodexArgs(name),
      persistSessionId: persistNativeId(deps.onThreadId, name),
      resolveAttachment: deps.resolveAttachment,
    })
  } catch (err) {
    if (adapter) await stopAllocatedThenRelease(host, adapter, err)
    releaseOwnAdmission(host, id, admissionToken)
    throw err
  }

  try {
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
  } catch (err) {
    await stopAllocatedThenRelease(host, adapter, err)
  }

  await startOrCleanup(host, adapter, "start")

  deps.registerAdapter?.(name, adapter, { onExit: () => {} })

  return { name, session_id: id, model: args.model, pid: 0 }
}

export async function resumeCodexSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onThreadId?: (name: string, sid: string) => void
    codexHost?: CodexCoreHost
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; effort?: string; agent_session_id?: string },
): Promise<{ adapter: CoreCodexAdapter }> {
  const host = resolveHost(deps.codexHost)
  const admissionToken = await reserveAdmission(host, session.id)
  const sessionHome = session.agent_home
  let adapter: CoreCodexAdapter | undefined
  try {
    const auth = await resolveCodexAuth({
      apiKey: process.env.OPENAI_API_KEY,
      userCodexHome: join(HOME, ".codex"),
      sessionCodexHome: sessionHome,
    })
    await codexPrepareSessionHome(sessionHome)
    writeCodexConfig({
      codexHome: sessionHome,
      ...shimSpawnSpec(),
      sessionName: session.name,
      socketsDir: SOCKETS_DIR,
      sessionId: session.id,
    })
    writeCodexPreamble({ codexHome: sessionHome, sessionName: session.name, workdir: session.workdir })

    const env = { ...auth.env, CODEX_HOME: sessionHome }
    const initialSessionId = session.agent_session_id || undefined
    adapter = createBoundAdapter({
      host,
      id: session.id,
      sessionName: session.name,
      workdir: session.workdir,
      model: session.model,
      effort: session.effort,
      env,
      command: resolveCodexCommand(env),
      args: brokerCodexArgs(session.name),
      initialSessionId,
      persistSessionId: persistNativeId(deps.onThreadId, session.name),
      resolveAttachment: deps.resolveAttachment,
    })
    if (initialSessionId) await startOrCleanup(host, adapter, "resume")
    else await startOrCleanup(host, adapter, "start")
    return { adapter }
  } catch (err) {
    const slot = adapter ? ownedMap(host).get(adapter.id) : undefined
    if (adapter && slot?.adapter === adapter && slot.state === "starting") {
      await stopAllocatedThenRelease(host, adapter, err)
    }
    releaseOwnAdmission(host, session.id, admissionToken)
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
