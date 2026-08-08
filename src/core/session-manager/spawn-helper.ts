import { Registry } from "./registry"
import { deriveName, ensureUnique } from "./naming"
import { buildClaudeSpawnSpec } from "./spawn-command"
import { preAcceptTrust } from "./trust"
import { sendChannelConsentEnter } from "./post-spawn-keys"
import { getSessionBackend } from "../runtime"
import { resolveCodexAuth } from "../agents/codex/auth"
import { writeCodexConfig } from "../agents/codex/config-writer"
import { writeCodexPreamble } from "../agents/codex/preamble-writer"
import { spawnCodexAppServer, type CodexSpawnHandle } from "../agents/codex/spawn"
import { CodexAdapter } from "../agents/codex/adapter"
import { resolveCursorAuth } from "../agents/cursor/auth"
import { writeCursorMcpConfig } from "../agents/cursor/mcp-writer"
import { writeCursorPreamble } from "../agents/cursor/preamble-writer"
import { makeRealCursorRunner } from "../agents/cursor/runner"
import { smokeCursorAgent } from "../agents/cursor/smoke"
import { CursorAdapter } from "../agents/cursor/adapter"
import { resolveOpenCodeAuth } from "../agents/opencode/auth"
import { writeOpenCodeConfig } from "../agents/opencode/config-writer"
import { writeOpenCodePreamble } from "../agents/opencode/preamble-writer"
import { spawnOpenCodeServer, type OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import { OpenCodeAdapter } from "../agents/opencode/adapter"
import { writeGrokPreamble } from "../agents/grok/preamble-writer"
import { writeGrokConfig } from "../agents/grok/config-writer"
import { resolveGrokAuth } from "../agents/grok/auth"
import { realGrokRunner } from "../agents/grok/runner"
import { GrokAdapter } from "../agents/grok/adapter"
// Dispatcher-only import: the per-agent session modules import types/helpers
// back from this file, which is a benign cycle as long as neither side
// dereferences the other at module-init time (functions + types only).
import { agents } from "../agents/registry"
import { cursorSpawnArgs, codexSpawnArgs, codexPrepareSessionHome, opencodeConfigEntries, grokConfigEntries } from "../plugins"
import { join } from "path"
import { shimSpawnSpec } from "./shim-spawn"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { execSync } from "child_process"
import { scanRepos } from "../editor/repo-scanner"
import { home } from "../../shared/home"
import { STATE_DIR, SOCKETS_DIR } from "../../shared/paths"
import { AgentKind } from "../../shared/agents"
import type { Session } from "./types"

export function captureBaseCommits(workdir: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const repo of scanRepos(workdir)) {
    try {
      out[repo.relPath] = execSync("git rev-parse HEAD", {
        cwd: repo.absPath, encoding: "utf-8", timeout: 5000,
      }).trim()
    } catch {
      // repo with no commits yet — skip; empty-tree base used at diff time
    }
  }
  return out
}

export const HOME = home()

type CursorSpawnHandle = { onExit: (cb: (code: number | null) => void) => void }
/** grok has no separate spawn handle (the adapter owns its stdio child); this
 * mirrors cursor's inert handle so registerAdapter keeps one shape. */
type GrokSpawnHandle = { onExit: (cb: (code: number | null) => void) => void }

export type SpawnDeps = {
  registry: Registry
  bind: (session_id: string) => Promise<void>
  tmuxSession: string
  resolveAttachment?: (file_id: string) => Promise<string>
  registerAdapter?: (
    name: string,
    adapter: CodexAdapter | CursorAdapter | OpenCodeAdapter | GrokAdapter,
    handle: CodexSpawnHandle | CursorSpawnHandle | OpenCodeSpawnHandle | GrokSpawnHandle,
  ) => void
  onThreadId?: (name: string, threadId: string) => void
  onCursorSessionId?: (name: string, sessionId: string) => void
  onOpenCodeSessionId?: (name: string, sessionId: string) => void
  onGrokSessionId?: (name: string, sessionId: string) => void
}

export type SpawnArgs = {
  workdir: string
  requestedName?: string
  agent?: AgentKind
  model?: string
  /** Explicit user override stored on the session. */
  reasoningLevel?: string
  /** Resolved CLI value (highest when unset). Passed by the broker caller. */
  effort?: string
  /** Mark the session as broker-internal (e.g. an agent-rpc worker) so it's
   *  hidden from user-facing lists. For claude the row is created async via the
   *  shim's onRegister, so the broker applies this there (see main.ts). For the
   *  synchronously-registered agents it's forwarded into registry.register(). */
  internal?: boolean
  /** When set (agent-rpc worker), claude is pinned to this strict mcp config
   *  and launched in MUX_RPC_ONLY mode. */
  rpcMcpConfig?: string
}

export type SpawnResult = {
  name: string
  session_id: string
  model?: string
}

// Resolve a unique session name, reserve it, bind the unix socket, then spawn
// the tmux window with the resolved name in the env var. Order matters:
//   1. resolve+reserve  — prevents concurrent-spawn name collisions
//   2. bind             — socket exists before the shim tries to connect
//   3. spawn tmux       — only now can claude (and the shim) start
// If the tmux spawn fails the reservation is released so the name is freed.
export async function spawnSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const agent = args.agent ?? AgentKind.Claude
  return agents[agent].spawn(deps, args)
}

export async function spawnPA(opts: {
  registry: Registry
  name: string
  agent: AgentKind
  workdir: string
  model?: string
  reasoningLevel?: string
  bind: (session_id: string) => Promise<void>
  tmuxSession: string
  onCodexSessionId?: (brokerSessionId: string, sessionId: string) => void
  onCursorSessionId?: (name: string, sessionId: string) => void
  onOpenCodeSessionId?: (name: string, sessionId: string) => void
  onGrokSessionId?: (name: string, sessionId: string) => void
  resolveEffort?: (session: Pick<Session, "agent" | "model" | "reasoningLevel">) => string | undefined
  registerAdapter?: (
    name: string,
    adapter: CodexAdapter | CursorAdapter | OpenCodeAdapter | GrokAdapter,
    handle: CodexSpawnHandle | CursorSpawnHandle | OpenCodeSpawnHandle | GrokSpawnHandle,
  ) => void
  resolveAttachment?: (file_id: string) => Promise<string>
  /** When provided, spawnPA skips registerPA (session already exists) and
   * updates the existing session's PID on completion. */
  id?: string
}): Promise<{ name: string; id: string }> {
  const { registry, name, agent, workdir, model, reasoningLevel } = opts
  const id = opts.id ?? randomUUID()
  const claudeSessionId = randomUUID()
  const skipRegister = opts.id ? !!registry.get(opts.id) || !!registry.resolveName(name) : false
  let finalPid = process.pid

  mkdirSync(workdir, { recursive: true })
  preAcceptTrust(workdir)

  await opts.bind(id)

  if (agent === AgentKind.Claude) {
    if (!skipRegister) {
      registry.registerPA({
        id,
        name,
        agent,
        workdir,
        model,
        reasoningLevel,
        pid: process.pid,
        is_default: registry.listPAs().length === 0,
      })
    }
    const spec = buildClaudeSpawnSpec({
      name,
      sessionId: id,
      claudeSessionId,
      sessionRole: "personal_assistant",
      model,
      effort: opts.resolveEffort?.({ agent, model, reasoningLevel }),
      workdir,
    })
    const target = await getSessionBackend().create({
      group: opts.tmuxSession,
      name,
      cwd: workdir,
      ...spec,
      cols: 80,
      rows: 24,
    })
    registry.sessions.setTmuxWindowId(id, target.id)
    finalPid = target.pid ?? process.pid
    void sendChannelConsentEnter(target.id)
    // The row exists either way (registerPA above, or skipRegister with a live
    // row) — write the fresh claude session id directly.
    registry.sessions.setAgentSessionId(id, claudeSessionId)
  } else if (agent === AgentKind.Codex) {
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
    writeCodexPreamble({ codexHome: sessionHome, sessionName: name, workdir })

    await codexPrepareSessionHome(sessionHome)
    const handle = spawnCodexAppServer({
      codexHome: sessionHome,
      workdir,
      authEnv: auth.env,
      model,
      reasoningLevel: opts.resolveEffort?.({ agent, model, reasoningLevel }),
      pluginConfigArgs: codexSpawnArgs({ sessionName: name }).args,
    })

    if (!skipRegister) {
      registry.registerPA({
        id,
        name,
        agent,
        workdir,
        model,
        reasoningLevel,
        pid: handle.pid,
        is_default: registry.listPAs().length === 0,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(workdir),
      })
    }
    finalPid = handle.pid

    const adapter = new CodexAdapter({
      sessionName: name,
      workdir,
      client: handle.client,
      persistThreadId: async (threadId) => {
        opts.onCodexSessionId?.(id, threadId)
      },
      initialThreadId: undefined,
      resolveAttachment: opts.resolveAttachment,
    })

    await adapter.start()
    opts.registerAdapter?.(name, adapter, handle)
  } else if (agent === AgentKind.Cursor) {
    const sessionHome = join(STATE_DIR, "agents", "cursor", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth = await resolveCursorAuth({
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
    writeCursorPreamble({ workdir, sessionName: name })

    await smokeCursorAgent({ home: sessionHome, authEnv: auth.env })

    const runner = makeRealCursorRunner({ home: sessionHome, authEnv: auth.env })
    const adapter = new CursorAdapter({
      sessionName: name,
      workdir,
      runner,
      persistSessionId: async (cursorId) => { opts.onCursorSessionId?.(name, cursorId) },
      initialSessionId: undefined,
      model,
      pluginArgs: cursorSpawnArgs({ sessionName: name }).args,
      resolveAttachment: opts.resolveAttachment,
    })

    await adapter.start()

    if (!skipRegister) {
      registry.registerPA({
        id,
        name,
        agent,
        workdir,
        model,
        reasoningLevel,
        pid: 0,
        is_default: registry.listPAs().length === 0,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(workdir),
      })
    }
    finalPid = 0

    opts.registerAdapter?.(name, adapter, { onExit: () => {} })
  } else if (agent === AgentKind.OpenCode) {
    const sessionHome = join(STATE_DIR, "agents", "opencode", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })
    const configHome = join(sessionHome, "config")

    const auth = resolveOpenCodeAuth({ home: HOME })
    const instructionsPath = writeOpenCodePreamble({ sessionHome, sessionName: name, workdir })
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

    const handle = await spawnOpenCodeServer({
      workdir,
      configHome,
      authEnv: auth.env,
    })

    if (!skipRegister) {
      registry.registerPA({
        id,
        name,
        agent,
        workdir,
        model,
        reasoningLevel,
        pid: handle.pid,
        is_default: registry.listPAs().length === 0,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(workdir),
      })
    }
    finalPid = handle.pid

    const adapter = new OpenCodeAdapter({
      sessionName: name,
      workdir,
      client: handle.client,
      persistSessionId: async (sid) => { opts.onOpenCodeSessionId?.(name, sid) },
      initialSessionId: undefined,
      model,
      resolveAttachment: opts.resolveAttachment,
    })

    await adapter.start()
    opts.registerAdapter?.(name, adapter, handle)
  } else if (agent === AgentKind.Grok) {
    const sessionHome = join(STATE_DIR, "agents", "grok", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    // HOME is redirected to sessionHome so grok reads a private config.toml and
    // does NOT auto-import the user's ~/.claude.json MCP servers (which would pull
    // the global mux-shim/mux-channel into this session).
    const auth = resolveGrokAuth({ userGrokDir: join(HOME, ".grok"), sessionHome })
    writeGrokConfig({
      sessionHome,
      ...shimSpawnSpec(),
      sessionName: name,
      sessionId: id,
      socketsDir: SOCKETS_DIR,
      skillsPaths: grokConfigEntries({ sessionName: name }).skillsPaths,
    })
    writeGrokPreamble({ workdir, sessionName: name })

    const adapter = new GrokAdapter({
      sessionName: name,
      workdir,
      runner: realGrokRunner,
      persistSessionId: async (sid) => { opts.onGrokSessionId?.(name, sid) },
      initialSessionId: undefined,
      model,
      effort: opts.resolveEffort?.({ agent, model, reasoningLevel }),
      env: auth.env,
      resolveAttachment: opts.resolveAttachment,
    })

    if (!skipRegister) {
      registry.registerPA({
        id,
        name,
        agent,
        workdir,
        model,
        reasoningLevel,
        pid: 0,
        is_default: registry.listPAs().length === 0,
        agent_home: sessionHome,
        base_commits: captureBaseCommits(workdir),
      })
    }
    finalPid = 0

    await adapter.start()
    opts.registerAdapter?.(name, adapter, { onExit: () => {} })
  }

  if (skipRegister || agent === AgentKind.Claude) {
    registry.sessions.activate(id, finalPid)
  }
  return { name, id }
}

