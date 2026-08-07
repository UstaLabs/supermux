import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { ResumeCtx, ResumeRow } from "../session-types"
import { resolveOpenCodeAuth } from "./auth"
import { writeOpenCodeConfig } from "./config-writer"
import { writeOpenCodePreamble } from "./preamble-writer"
import { spawnOpenCodeServer, type OpenCodeSpawnHandle } from "./spawn"
import { OpenCodeAdapter } from "./adapter"
import { opencodeConfigEntries } from "../../plugins"
import { join } from "path"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { STATE_DIR, SOCKETS_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)
  try {
    const sessionHome = join(STATE_DIR, "agents", "opencode", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })
    // Session-private XDG_CONFIG_HOME — holds the opencode config (mux-shim MCP +
    // instructions). Auth (XDG_DATA_HOME) stays at the user's, so the session
    // reuses the credentials from `opencode auth login`.
    const configHome = join(sessionHome, "config")

    // NOT fail-closed: opencode ships a free `opencode/*` tier that runs with no
    // credentials, so a session is usable even before `opencode auth login`. We
    // still resolve auth for the env; missing creds only limits which models work
    // (opencode surfaces that at prompt time), it doesn't block the session.
    const auth = resolveOpenCodeAuth({ home: HOME })

    // Identity/reply/naming preamble, included via the config's `instructions`
    // so it never gets written into the user's workdir.
    const instructionsPath = writeOpenCodePreamble({ sessionHome, sessionName: name, workdir: args.workdir })
    const { pluginPaths, skillsPaths } = opencodeConfigEntries({ sessionName: name })

    writeOpenCodeConfig({
      configHome,
      ...shimSpawnSpec(),
      sessionName: name,
      socketsDir: SOCKETS_DIR,
      sessionId: id,
      instructionsPath,
      pluginPaths,
      skillsPaths,
    })

    await deps.bind(id)

    const handle = await spawnOpenCodeServer({
      workdir: args.workdir,
      configHome,
      authEnv: auth.env,
    })

    // Register BEFORE adapter.start() so the persistSessionId callback can find
    // the row (same ordering codex requires).
    deps.registry.register({
      id,
      name,
      workdir: args.workdir,
      pid: handle.pid,
      agent: AgentKind.OpenCode,
      agent_home: sessionHome,
      base_commits: captureBaseCommits(args.workdir),
      internal: args.internal,
    } as any)

    const adapter = new OpenCodeAdapter({
      sessionName: name,
      workdir: args.workdir,
      client: handle.client,
      persistSessionId: async (sid) => { deps.onOpenCodeSessionId?.(name, sid) },
      initialSessionId: undefined,
      model: args.model,
      resolveAttachment: deps.resolveAttachment,
    })

    await adapter.start()

    deps.registerAdapter?.(name, adapter, handle)

    return { name, session_id: id, model: args.model }
  } catch (err) {
    throw err
  }
}

/** Rebuild an opencode session's adapter + `opencode serve` child after a broker
 * restart. Unlike claude/codex, an opencode session's worker lives entirely
 * in-process (the adapter) plus a broker-child `opencode serve`; both die with
 * the broker. The session row, its private config/preamble, and opencode's own
 * session store (under the user's XDG_DATA_HOME) all persist on disk — so resume
 * only needs to respawn the server and re-bind an adapter, resuming the prior
 * opencode session id when one was persisted (else starting fresh).
 *
 * Mirrors spawn but skips name reservation + registry.register
 * (the row already exists). Self-heals the on-disk config/preamble (idempotent),
 * the opencode analogue of codex's codexPrepareSessionHome-on-resume. */
export async function resumeOpenCodeSession(
  deps: { resolveAttachment?: (file_id: string) => Promise<string>; onOpenCodeSessionId?: (name: string, sid: string) => void },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; agent_session_id?: string },
): Promise<{ adapter: OpenCodeAdapter; handle: OpenCodeSpawnHandle }> {
  const sessionHome = session.agent_home
  const configHome = join(sessionHome, "config")

  const instructionsPath = writeOpenCodePreamble({ sessionHome, sessionName: session.name, workdir: session.workdir })
  const { pluginPaths, skillsPaths } = opencodeConfigEntries({ sessionName: session.name })
  writeOpenCodeConfig({
    configHome,
    ...shimSpawnSpec(),
    sessionName: session.name,
    socketsDir: SOCKETS_DIR,
    sessionId: session.id,
    instructionsPath,
    pluginPaths,
    skillsPaths,
  })

  const auth = resolveOpenCodeAuth({ home: HOME })
  const handle = await spawnOpenCodeServer({ workdir: session.workdir, configHome, authEnv: auth.env })

  const adapter = new OpenCodeAdapter({
    sessionName: session.name,
    workdir: session.workdir,
    client: handle.client,
    persistSessionId: async (sid) => { deps.onOpenCodeSessionId?.(session.name, sid) },
    initialSessionId: session.agent_session_id || undefined,
    model: session.model,
    resolveAttachment: deps.resolveAttachment,
  })
  if (session.agent_session_id) await adapter.resume()
  else await adapter.start()
  return { adapter, handle }
}

/** Dialect half of resume; the SessionManager registers + wires the result. */
export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: OpenCodeAdapter; handle: OpenCodeSpawnHandle }> {
  return resumeOpenCodeSession(
    {
      resolveAttachment: ctx.resolveAttachment,
      onOpenCodeSessionId: (_name, sid) => { ctx.persistAgentSessionId(sid) },
    },
    { id: session.id, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, agent_session_id: session.agent_session_id },
  )
}
