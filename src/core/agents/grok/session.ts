import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { ResumeCtx, ResumeRow } from "../session-types"
import { writeGrokPreamble } from "./preamble-writer"
import { writeGrokConfig } from "./config-writer"
import { resolveGrokAuth } from "./auth"
import { realGrokRunner, type GrokRunner } from "./runner"
import { GrokAdapter } from "./adapter"
import { join } from "path"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { STATE_DIR, SOCKETS_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"

/** grok's worker is an in-process adapter driving a `grok agent stdio` child over
 * ACP. Unlike cursor (per-turn CLI) the child is persistent; unlike codex/opencode
 * it's owned by the adapter, so there's no separate handle and no pid to track —
 * the row is registered with pid 0 and adapter.stop() is the kill.
 *
 * MCP + preamble are NOT files grok reads from a private home: the mux-shim server
 * is handed to grok inline via ACP `session/new`, and the identity preamble goes to
 * AGENTS.md in the workdir (git-excluded, override-safe). sessionHome exists only to
 * give the row an agent_home for resume. */
export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)

  const sessionHome = join(STATE_DIR, "agents", "grok", name)
  mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

  const auth = resolveGrokAuth({ userGrokDir: join(HOME, ".grok"), sessionHome })
  writeGrokConfig({
    sessionHome,
    ...shimSpawnSpec(),
    sessionName: name,
    sessionId: id,
    socketsDir: SOCKETS_DIR,
  })
  writeGrokPreamble({ workdir: args.workdir, sessionName: name })

  await deps.bind(id)

  const adapter = (deps.grokAdapterFactory ?? ((o) => new GrokAdapter(o)))({
    sessionName: name,
    workdir: args.workdir,
    runner: (deps.grokRunnerFactory ?? (() => realGrokRunner))(),
    persistSessionId: async (sid) => { deps.onGrokSessionId?.(name, sid) },
    initialSessionId: undefined,
    model: args.model,
    effort: args.effort,
    env: auth.env,
    resolveAttachment: deps.resolveAttachment,
  })

  // Register BEFORE adapter.start(): start() completes the ACP handshake, which
  // fires persistSessionId — that callback resolves the row by name, so the row
  // must already exist or the grok session id is lost (breaking resume).
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

  await adapter.start()

  deps.registerAdapter?.(name, adapter, { onExit: () => {} })

  return { name, session_id: id, model: args.model }
}

/** Rebuild a grok session's adapter + stdio child after a broker restart. The child
 * dies with the broker; the session row and grok's own session store (under the
 * session-private ~/.grok) persist, so resume respawns the child and re-binds an
 * adapter, loading the prior grok session id when one was persisted (else starting
 * fresh). Self-heals the private config, credential and preamble (all idempotent) —
 * the grok analogue of codex's codexPrepareSessionHome-on-resume. */
export async function resumeGrokSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onGrokSessionId?: (name: string, sid: string) => void
    grokRunnerFactory?: () => GrokRunner
  },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; effort?: string; agent_session_id?: string },
): Promise<{ adapter: GrokAdapter }> {
  const sessionHome = session.agent_home
  const auth = resolveGrokAuth({ userGrokDir: join(HOME, ".grok"), sessionHome })
  writeGrokConfig({
    sessionHome,
    ...shimSpawnSpec(),
    sessionName: session.name,
    sessionId: session.id,
    socketsDir: SOCKETS_DIR,
  })
  writeGrokPreamble({ workdir: session.workdir, sessionName: session.name })

  const adapter = new GrokAdapter({
    sessionName: session.name,
    workdir: session.workdir,
    runner: (deps.grokRunnerFactory ?? (() => realGrokRunner))(),
    persistSessionId: async (sid) => { deps.onGrokSessionId?.(session.name, sid) },
    initialSessionId: session.agent_session_id || undefined,
    model: session.model,
    effort: session.effort,
    env: auth.env,
    resolveAttachment: deps.resolveAttachment,
  })
  if (session.agent_session_id) await adapter.resume()
  else await adapter.start()
  return { adapter }
}

/** Dialect half of resume; the SessionManager registers + wires the result. */
export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: GrokAdapter }> {
  return resumeGrokSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onGrokSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, effort: ctx.sessionEffort(session), agent_session_id: session.agent_session_id },
  )
}
