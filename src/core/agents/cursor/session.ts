import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import { smokeCursorAgent } from "./smoke"
import type { ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange } from "../session-types"
import { resolveCursorAuth } from "./auth"
import type { CursorAuthResult } from "./auth"
import { writeCursorMcpConfig } from "./mcp-writer"
import { writeCursorPreamble } from "./preamble-writer"
import { makeRealCursorRunner } from "./runner"
import { CursorAdapter } from "./adapter"
import { cursorSpawnArgs } from "../../plugins"
import { join } from "path"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { STATE_DIR, SOCKETS_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import { home } from "../../../shared/home"

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  // PA spawns keep the exact requested name (the row may already exist).
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)
  try {
    const sessionHome = join(STATE_DIR, "agents", "cursor", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth: CursorAuthResult = await resolveCursorAuth({
      apiKey: process.env.CURSOR_API_KEY,
      userCursorDir: join(HOME, ".cursor"),
      sessionHome,
    })

    writeCursorMcpConfig({
      sessionHome,
      ...shimSpawnSpec(),
      sessionName: name,
      socketsDir: SOCKETS_DIR,
      sessionId: id,
    })
    writeCursorPreamble({ workdir: args.workdir, sessionName: name })

    // Smoke-test the CLI before declaring the spawn successful. Without this
    // the spawn "fails open" — config written but no actual agent reachable —
    // and the user sees a phantom session that responds to nothing.
    await smokeCursorAgent({ home: sessionHome, authEnv: auth.env })

    await deps.bind(id)

    const runner = makeRealCursorRunner({ home: sessionHome, authEnv: auth.env })
    const adapter = new CursorAdapter({
      sessionName: name,
      workdir: args.workdir,
      runner,
      persistSessionId: async (cursorId) => { deps.onCursorSessionId?.(name, cursorId) },
      initialSessionId: undefined,
      model: args.model,
      pluginArgs: cursorSpawnArgs({ sessionName: name }).args,
      resolveAttachment: deps.resolveAttachment,
    })

    if (args.pa) {
      // Mirror of the old spawnPA order: the PA adapter starts before its row
      // is registered (cursor is per-turn; start() is a lightweight init).
      await adapter.start()
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
      })
    }

    deps.registerAdapter?.(name, adapter, { onExit: () => {} })

    return { name, session_id: id, model: args.model, pid: 0 }
  } catch (err) {
    throw err
  }
}

/** Dialect half of resume; the SessionManager registers + wires the result.
 * Cursor sessions are per-turn — no persistent process to respawn. The adapter
 * just needs agent_home (config + auth dir); the preamble is self-healed on
 * every resume. agent_session_id may be absent if the session never received a
 * first message yet; that's OK — initialSessionId=undefined means the first
 * turn starts fresh. */
export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CursorAdapter }> {
  const auth = await resolveCursorAuth({
    apiKey: process.env.CURSOR_API_KEY,
    userCursorDir: join(home(), ".cursor"),
    sessionHome: session.agent_home,
  })
  writeCursorPreamble({ workdir: session.workdir, sessionName: name })
  const runner = makeRealCursorRunner({ home: session.agent_home, authEnv: auth.env })
  const adapter = new CursorAdapter({
    sessionName: name,
    workdir: session.workdir,
    runner,
    persistSessionId: async (id) => {
      ctx.persistAgentSessionId(id)
    },
    initialSessionId: session.agent_session_id,
    pluginArgs: cursorSpawnArgs({ sessionName: name }).args,
    resolveAttachment: ctx.resolveAttachment,
  })
  return { adapter }
}

/** Dialect half of a model change: cursor spawns a fresh `cursor-agent` per
 * turn and reads the adapter's `model` field on each one, so a switch is a
 * live in-process field update — no process restart, no config reapply.
 * (Reasoning depth is part of model selection; there is no effort half.) */
export async function applyConfig(
  ctx: ApplyConfigCtx,
  _session: ApplyConfigRow,
  _name: string,
  change: ApplyConfigChange,
): Promise<{ ok: true }> {
  if (ctx.adapter instanceof CursorAdapter && change.changed?.model !== false && change.model) {
    ctx.adapter.model = change.model
  }
  return { ok: true }
}
