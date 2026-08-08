import { deriveName, ensureUnique } from "../../session-manager/naming"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { captureBaseCommits, HOME } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigChange } from "../session-types"
import type { CodexRpc } from "../../slash-commands/types"
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

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  // PA spawns keep the exact requested name (the row may already exist).
  const name = args.pa ? base : ensureUnique(base, deps.registry.takenNames())
  const id = args.id ?? randomUUID()
  if (!args.pa) deps.registry.reserveName(name)
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
    if (args.pa) {
      if (!args.pa.skipRegister) {
        deps.registry.registerPA({
          id,
          name,
          agent: AgentKind.Codex,
          workdir: args.workdir,
          model: args.model,
          reasoningLevel: args.reasoningLevel,
          pid: handle.pid,
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
        pid: handle.pid,
        agent: AgentKind.Codex,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(args.workdir),
        internal: args.internal,
      } as any)
    }

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

    return { name, session_id: id, model: args.model, pid: handle.pid }
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

/** Dialect half of a model/effort change: codex has no live setter, so this is
 * a FULL respawn of the app-server with the new flags (same flag set as
 * resume, minus the preamble rewrite). The SessionManager kills the old
 * runtime first and swaps in the returned one (state half), so callers never
 * hold a half-dead adapter. Reads the DESIRED config off the session row —
 * the component already persisted it. */
export async function applyConfig(
  ctx: ApplyConfigCtx,
  session: ResumeRow,
  name: string,
  _change: ApplyConfigChange,
): Promise<{ ok: true; runtime: { adapter: CodexAdapter; handle: CodexSpawnHandle } }> {
  const auth = await resolveCodexAuth({
    apiKey: process.env.OPENAI_API_KEY,
    userCodexHome: join(home(), ".codex"),
    sessionCodexHome: session.agent_home,
  })
  await codexPrepareSessionHome(session.agent_home)
  const handle = spawnCodexAppServer({
    codexHome: session.agent_home,
    workdir: session.workdir,
    authEnv: auth.env,
    model: session.model,
    reasoningLevel: ctx.sessionEffort(session),
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
  if (session.agent_session_id) {
    await adapter.resume()
  } else {
    await adapter.start()
  }
  return { ok: true, runtime: { adapter, handle } }
}
