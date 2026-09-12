import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "../session-types"
import type { GrokAcpCommand } from "../../slash-commands/types"
import { writeGrokPreamble } from "./preamble-writer"
import { writeGrokConfig } from "./config-writer"
import { resolveGrokAuth } from "./auth"
import { GrokAdapter } from "./adapter"
import { CoreGrokAdapter } from "./core-adapter"
import { getGrokCoreHost } from "./core-host-provider"
import type { GrokCoreHost } from "./core-host"
import { join } from "path"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { STATE_DIR, SOCKETS_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import { grokConfigEntries } from "../../plugins"

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

export type GrokSessionAdapter = CoreGrokAdapter | GrokAdapter

function resolveHost(explicit?: GrokCoreHost): GrokCoreHost {
  return explicit ?? getGrokCoreHost()
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

async function startOrCleanup(host: GrokCoreHost, adapter: CoreGrokAdapter, mode: "start" | "resume"): Promise<void> {
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
  onGrokSessionId: ((name: string, sid: string) => void) | undefined,
  name: string,
): (sid: string) => Promise<void> {
  return async (sid) => { onGrokSessionId?.(name, sid) }
}

type SlotState = "admission" | "recovering" | "starting" | "ready" | "failed-cleanup"
type OwnedSlot =
  | { state: "admission"; token: symbol; adapter?: undefined }
  | { state: "recovering"; token: symbol; adapter: CoreGrokAdapter }
  | { state: "starting" | "ready" | "failed-cleanup"; adapter: CoreGrokAdapter }
const ownedByHost = new WeakMap<GrokCoreHost, Map<string, OwnedSlot>>()
const admissionTokenByHostId = new WeakMap<GrokCoreHost, Map<string, symbol>>()

function ownedMap(host: GrokCoreHost): Map<string, OwnedSlot> {
  let map = ownedByHost.get(host)
  if (!map) {
    map = new Map()
    ownedByHost.set(host, map)
  }
  return map
}

function tokenMap(host: GrokCoreHost): Map<string, symbol> {
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
  return new Error(`grok session ${id} is already ${phase}`)
}

function dropOwned(host: GrokCoreHost, adapter: CoreGrokAdapter): void {
  const map = ownedMap(host)
  const slot = map.get(adapter.id)
  // Recovering is owned by reserveAdmission until it transitions the slot.
  // wrapStopToRelease must not delete that token on a successful cleanup stop.
  if (slot?.adapter === adapter && slot.state !== "recovering") {
    map.delete(adapter.id)
    tokenMap(host).delete(adapter.id)
  }
}

function releaseOwnAdmission(host: GrokCoreHost, id: string, token: symbol): void {
  const map = ownedMap(host)
  const slot = map.get(id)
  if (slot?.state === "admission" && slot.token === token) {
    map.delete(id)
    tokenMap(host).delete(id)
  }
}

function markReady(host: GrokCoreHost, adapter: CoreGrokAdapter): void {
  const slot = ownedMap(host).get(adapter.id)
  if (slot?.adapter === adapter && slot.state === "starting") {
    ownedMap(host).set(adapter.id, { state: "ready", adapter })
    tokenMap(host).delete(adapter.id)
  }
}

function markFailedCleanup(host: GrokCoreHost, adapter: CoreGrokAdapter): void {
  const slot = ownedMap(host).get(adapter.id)
  if (slot?.adapter === adapter) {
    ownedMap(host).set(adapter.id, { state: "failed-cleanup", adapter })
    tokenMap(host).delete(adapter.id)
  }
}

function attachStarting(host: GrokCoreHost, adapter: CoreGrokAdapter): void {
  const map = ownedMap(host)
  const slot = map.get(adapter.id)
  const token = tokenMap(host).get(adapter.id)
  if (!slot || slot.state !== "admission" || slot.token !== token) {
    throw alreadyOwnedError(adapter.id, slot?.state ?? "ready")
  }
  map.set(adapter.id, { state: "starting", adapter })
}

function wrapStopToRelease(host: GrokCoreHost, adapter: CoreGrokAdapter): void {
  const original = adapter.stop.bind(adapter)
  adapter.stop = async () => {
    await original()
    dropOwned(host, adapter)
  }
}

async function stopAllocatedThenRelease(host: GrokCoreHost, adapter: CoreGrokAdapter, cause: unknown): Promise<never> {
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
async function reserveAdmission(host: GrokCoreHost, id: string): Promise<symbol> {
  const map = ownedMap(host)
  const tokens = tokenMap(host)
  const prior = map.get(id)
  if (prior?.state === "failed-cleanup") {
    const recoverToken = Symbol("grok-recover")
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
    const token = Symbol("grok-admission")
    map.set(id, { state: "admission", token })
    tokens.set(id, token)
    return token
  }
  if (prior) throw alreadyOwnedError(id, prior.state)
  const token = Symbol("grok-admission")
  map.set(id, { state: "admission", token })
  tokens.set(id, token)
  return token
}

function createBoundAdapter(opts: {
  host: GrokCoreHost
  id: string
  sessionName: string
  workdir: string
  model?: string
  effort?: string
  env: Record<string, string>
  initialSessionId?: string
  persistSessionId: (sid: string) => Promise<void>
  resolveAttachment?: (file_id: string) => Promise<string>
}): CoreGrokAdapter {
  const adapter = opts.host.createAdapter({
    id: opts.id,
    sessionName: opts.sessionName,
    workdir: opts.workdir,
    model: opts.model,
    effort: opts.effort,
    env: opts.env,
    initialSessionId: opts.initialSessionId,
    persistSessionId: opts.persistSessionId,
    resolveAttachment: opts.resolveAttachment,
  })
  attachStarting(opts.host, adapter)
  wrapStopToRelease(opts.host, adapter)
  return adapter
}

/** grok's worker is an in-process CoreGrokAdapter driving a `grok agent stdio`
 * child via a process-owned GrokCoreHost. Unlike cursor (per-turn CLI) the child
 * is persistent; unlike codex/opencode it's owned by the adapter, so there's no
 * separate handle and no pid to track — the row is registered with pid 0 and
 * adapter.stop() is the kill.
 *
 * MCP + skills live in the session-private config.toml (mux-shim). Grok does
 * not take MCP servers inline via ACP session/new. The identity preamble is
 * AGENTS.md in the workdir (git-excluded, override-safe). sessionHome is the
 * redirected HOME plus agent_home for resume. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)

  const host = resolveHost(deps.grokHost)
  const admissionToken = await reserveAdmission(host, id)
  const sessionHome = join(STATE_DIR, "agents", "grok", name)
  let adapter: CoreGrokAdapter | undefined
  try {
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth = await resolveGrokAuth({ userGrokDir: join(HOME, ".grok"), sessionHome })
    writeGrokConfig({
      sessionHome,
      ...shimSpawnSpec(),
      sessionName: name,
      sessionId: id,
      socketsDir: SOCKETS_DIR,
      skillsPaths: grokConfigEntries({ sessionName: name }).skillsPaths,
    })
    writeGrokPreamble({ workdir: args.workdir, sessionName: name })

    await deps.bind(id)

    adapter = createBoundAdapter({
      host,
      id,
      sessionName: name,
      workdir: args.workdir,
      model: args.model,
      effort: args.effort,
      env: auth.env,
      persistSessionId: persistNativeId(deps.onGrokSessionId, name),
      resolveAttachment: deps.resolveAttachment,
    })
  } catch (err) {
    if (adapter) await stopAllocatedThenRelease(host, adapter, err)
    releaseOwnAdmission(host, id, admissionToken)
    throw err
  }

  // Register BEFORE adapter.start(): start() completes the ACP handshake, which
  // fires persistSessionId — that callback resolves the row by name, so the row
  // must already exist or the grok session id is lost (breaking resume).
  try {
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
      } as any)
    }
  } catch (err) {
    await stopAllocatedThenRelease(host, adapter, err)
  }

  await startOrCleanup(host, adapter, "start")

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
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; effort?: string; agent_session_id?: string },
): Promise<{ adapter: CoreGrokAdapter }> {
  const host = resolveHost(deps.grokHost)
  const admissionToken = await reserveAdmission(host, session.id)
  const sessionHome = session.agent_home
  let adapter: CoreGrokAdapter | undefined
  try {
    const auth = await resolveGrokAuth({ userGrokDir: join(HOME, ".grok"), sessionHome })
    writeGrokConfig({
      sessionHome,
      ...shimSpawnSpec(),
      sessionName: session.name,
      sessionId: session.id,
      socketsDir: SOCKETS_DIR,
      skillsPaths: grokConfigEntries({ sessionName: session.name }).skillsPaths,
    })
    writeGrokPreamble({ workdir: session.workdir, sessionName: session.name })

    const initialSessionId = session.agent_session_id || undefined
    adapter = createBoundAdapter({
      host,
      id: session.id,
      sessionName: session.name,
      workdir: session.workdir,
      model: session.model,
      effort: session.effort,
      env: auth.env,
      initialSessionId,
      persistSessionId: persistNativeId(deps.onGrokSessionId, session.name),
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
  const coreAdapter = adapter instanceof CoreGrokAdapter
    ? adapter
    : (adapter && typeof (adapter as CoreGrokAdapter).setConfiguration === "function" && !(adapter instanceof GrokAdapter)
      ? adapter as CoreGrokAdapter
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
  if (!(adapter instanceof GrokAdapter)) return { ok: false, error: "grok session has no live adapter" }
  if (change.changed?.model !== false && change.model) adapter.model = change.model
  if (change.changed?.effort !== false) await adapter.setEffort(change.effort)
  return { ok: true }
}

export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CoreGrokAdapter }> {
  return resumeGrokSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onGrokSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
      grokHost: ctx.grokHost,
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, effort: ctx.sessionEffort(session), agent_session_id: session.agent_session_id },
  )
}
