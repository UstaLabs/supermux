import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { ResumeCtx, ResumeRow } from "../session-types"
import { resolveCodexAuth } from "./auth"
import { writeCodexConfig } from "./config-writer"
import { writeCodexPreamble } from "./preamble-writer"
import { spawnCodexAppServer, type CodexSpawnHandle } from "./spawn"
import { CodexAdapter } from "./adapter"
import { codexSpawnArgs, codexPrepareSessionHome } from "../../plugins"
import { join } from "path"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { STATE_DIR, SOCKETS_DIR } from "../../../shared/paths"
import { AgentKind } from "../../../shared/agents"
import { home } from "../../../shared/home"

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)
  try {
    const sessionHome = join(STATE_DIR, "agents", "codex", name)
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

    // Install enabled plugins into this session's CODEX_HOME so skills/list +
    // native invocation see them (the `-c enabled` flag needs them installed).
    await codexPrepareSessionHome(sessionHome)
    const handle = spawnCodexAppServer({
      codexHome: sessionHome,
      workdir: args.workdir,
      authEnv: auth.env,
      model: args.model,
      reasoningLevel: args.effort,
      pluginConfigArgs: codexSpawnArgs({ sessionName: name }).args,
    })

    // Register BEFORE adapter.start() so the persistThreadId callback
    // (which does registry.resolveName(name)) can find the entry. Previously
    // register was after start, so the callback saw undefined and the
    // thread ID was silently lost — breaking resume on broker restart.
    deps.registry.register({
      id,
      name,
      workdir: args.workdir,
      pid: handle.pid,
      agent: AgentKind.Codex,
      agent_home: sessionHome,
      base_commits: captureBaseCommits(args.workdir),
      internal: args.internal,
    } as any)

    const adapter = new CodexAdapter({
      sessionName: name,
      workdir: args.workdir,
      client: handle.client,
      persistThreadId: async (threadId) => {
        deps.onThreadId?.(name, threadId)
      },
      initialThreadId: undefined,
      resolveAttachment: deps.resolveAttachment,
    })

    await adapter.start()

    deps.registerAdapter?.(name, adapter, handle)

    return { name, session_id: id, model: args.model }
  } catch (err) {
    throw err
  }
}

/** Dialect half of resume; the SessionManager registers + wires the result. */
export async function resume(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: CodexAdapter; handle: CodexSpawnHandle }> {
  const auth = await resolveCodexAuth({
    apiKey: process.env.OPENAI_API_KEY,
    userCodexHome: join(home(), ".codex"),
    sessionCodexHome: session.agent_home,
  })
  await codexPrepareSessionHome(session.agent_home)
  writeCodexPreamble({ codexHome: session.agent_home, sessionName: name, workdir: session.workdir })
  const effort = ctx.sessionEffort(session)
  const handle = spawnCodexAppServer({
    codexHome: session.agent_home,
    workdir: session.workdir,
    authEnv: auth.env,
    model: session.model,
    reasoningLevel: effort,
    pluginConfigArgs: codexSpawnArgs({ sessionName: name }).args,
  })
  const adapter = new CodexAdapter({
    sessionName: name,
    workdir: session.workdir,
    client: handle.client,
    persistThreadId: async () => {},
    initialThreadId: session.agent_session_id,
    resolveAttachment: ctx.resolveAttachment,
  })
  await adapter.resume()
  return { adapter, handle }
}
