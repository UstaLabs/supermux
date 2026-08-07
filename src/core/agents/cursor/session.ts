import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME, smokeCursorAgent } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
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

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)
  try {
    const sessionHome = join(STATE_DIR, "agents", "cursor", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth: CursorAuthResult = await (deps.cursorResolveAuth ?? resolveCursorAuth)({
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
    await (deps.cursorSmokeAgent ?? smokeCursorAgent)({ home: sessionHome, authEnv: auth.env })

    await deps.bind(id)

    const runner = (deps.cursorRunnerFactory ?? makeRealCursorRunner)({ home: sessionHome, authEnv: auth.env })
    const adapter = (deps.cursorAdapterFactory ?? ((opts) => new CursorAdapter(opts)))({
      sessionName: name,
      workdir: args.workdir,
      runner,
      persistSessionId: async (cursorId) => { deps.onCursorSessionId?.(name, cursorId) },
      initialSessionId: undefined,
      model: args.model,
      pluginArgs: cursorSpawnArgs({ sessionName: name }).args,
      resolveAttachment: deps.resolveAttachment,
    })

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

    deps.registerAdapter?.(name, adapter, { onExit: () => {} })

    return { name, session_id: id, model: args.model }
  } catch (err) {
    throw err
  }
}
