import { Registry } from "./registry"
import { deriveName, ensureUnique } from "./naming"
import { buildClaudeSpawnCommand } from "./spawn-command"
import { preAcceptTrust } from "./trust"
import { sendChannelConsentEnter } from "./post-spawn-keys"
import { listSessionWindows } from "./tmux"
import { resolveCodexAuth } from "../agents/codex/auth"
import { writeCodexConfig } from "../agents/codex/config-writer"
import { writeCodexPreamble } from "../agents/codex/preamble-writer"
import { spawnCodexAppServer, type CodexSpawnHandle } from "../agents/codex/spawn"
import { CodexAdapter } from "../agents/codex/adapter"
import { resolveCursorAuth } from "../agents/cursor/auth"
import type { CursorAuthResult } from "../agents/cursor/auth"
import { writeCursorMcpConfig } from "../agents/cursor/mcp-writer"
import { writeCursorPreamble } from "../agents/cursor/preamble-writer"
import { makeRealCursorRunner } from "../agents/cursor/runner"
import { CursorAdapter, type CursorRunner } from "../agents/cursor/adapter"
import { resolveOpenCodeAuth } from "../agents/opencode/auth"
import { writeOpenCodeConfig } from "../agents/opencode/config-writer"
import { writeOpenCodePreamble } from "../agents/opencode/preamble-writer"
import { spawnOpenCodeServer, type OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import { OpenCodeAdapter } from "../agents/opencode/adapter"
import { writeGrokPreamble } from "../agents/grok/preamble-writer"
import { buildGrokMcpServers } from "../agents/grok/mcp-writer"
import { realGrokRunner, type GrokRunner } from "../agents/grok/runner"
import { GrokAdapter } from "../agents/grok/adapter"
import { cursorSpawnArgs, codexSpawnArgs, codexPrepareSessionHome, opencodeConfigEntries } from "../plugins"
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

function captureBaseCommits(workdir: string): Record<string, string> {
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

const HOME = home()

type CursorSpawnHandle = { onExit: (cb: (code: number | null) => void) => void }
/** grok has no separate spawn handle (the adapter owns its stdio child); this
 * mirrors cursor's inert handle so registerAdapter keeps one shape. */
type GrokSpawnHandle = { onExit: (cb: (code: number | null) => void) => void }

export type SpawnDeps = {
  registry: Registry
  bind: (session_id: string) => Promise<void>
  spawnTmux: (opts: { session: string; window: string; workdir: string; command: string }) => Promise<{ windowId?: string } | void>
  tmuxSession: string
  /** Lists existing tmux window names; injectable for tests. Defaults to real tmux. */
  listWindows?: (session: string) => Promise<string[]>
  resolveAttachment?: (file_id: string) => Promise<string>
  postSpawnReady?: (target: string) => Promise<void>
  codexResolveAuth?: typeof resolveCodexAuth
  codexPrepareSessionHome?: typeof codexPrepareSessionHome
  codexSpawnArgs?: typeof codexSpawnArgs
  codexSpawnAppServer?: typeof spawnCodexAppServer
  codexAdapterFactory?: (opts: ConstructorParameters<typeof CodexAdapter>[0]) => CodexAdapter
  cursorResolveAuth?: typeof resolveCursorAuth
  cursorSmokeAgent?: typeof smokeCursorAgent
  cursorRunnerFactory?: (opts: { home: string; authEnv: Record<string, string> }) => CursorRunner
  cursorAdapterFactory?: (opts: ConstructorParameters<typeof CursorAdapter>[0]) => CursorAdapter
  grokRunnerFactory?: () => GrokRunner
  grokAdapterFactory?: (opts: ConstructorParameters<typeof GrokAdapter>[0]) => GrokAdapter
  registerAdapter?: (
    name: string,
    adapter: CodexAdapter | CursorAdapter | OpenCodeAdapter | GrokAdapter,
    handle: CodexSpawnHandle | CursorSpawnHandle | OpenCodeSpawnHandle | GrokSpawnHandle,
  ) => void
  onThreadId?: (name: string, threadId: string) => void
  onCodexSessionId?: (name: string, sessionId: string) => void
  onCursorSessionId?: (name: string, sessionId: string) => void
  onOpenCodeSessionId?: (name: string, sessionId: string) => void
  onGrokSessionId?: (name: string, sessionId: string) => void
  onClaudeSessionId?: (name: string, claudeSessionId: string) => void
  onTmuxWindowId?: (brokerSessionId: string, windowId: string) => void
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
  switch (agent) {
    case AgentKind.Claude:
      return spawnClaudeSession(deps, args)
    case AgentKind.Codex:
      return spawnCodexSession(deps, args)
    case AgentKind.Cursor:
      return spawnCursorSession(deps, args)
    case AgentKind.OpenCode:
      return spawnOpenCodeSession(deps, args)
    case AgentKind.Grok:
      return spawnGrokSession(deps, args)
  }
}

export async function spawnPA(opts: {
  registry: Registry
  name: string
  agent: AgentKind
  workdir: string
  model?: string
  reasoningLevel?: string
  bind: (session_id: string) => Promise<void>
  spawnTmux: (opts: { session: string; window: string; workdir: string; command: string }) => Promise<{ windowId?: string } | void>
  tmuxSession: string
  onClaudeSessionId?: (brokerSessionId: string, claudeSessionId: string) => void
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
  codexResolveAuth?: typeof resolveCodexAuth
  codexPrepareSessionHome?: typeof codexPrepareSessionHome
  codexSpawnArgs?: typeof codexSpawnArgs
  codexSpawnAppServer?: typeof spawnCodexAppServer
  codexAdapterFactory?: (opts: ConstructorParameters<typeof CodexAdapter>[0]) => CodexAdapter
  cursorResolveAuth?: typeof resolveCursorAuth
  cursorSmokeAgent?: typeof smokeCursorAgent
  cursorRunnerFactory?: (opts: { home: string; authEnv: Record<string, string> }) => CursorRunner
  cursorAdapterFactory?: (opts: ConstructorParameters<typeof CursorAdapter>[0]) => CursorAdapter
  opencodeSpawnServer?: typeof spawnOpenCodeServer
  opencodeAdapterFactory?: (opts: ConstructorParameters<typeof OpenCodeAdapter>[0]) => OpenCodeAdapter
  grokRunnerFactory?: () => GrokRunner
  grokAdapterFactory?: (opts: ConstructorParameters<typeof GrokAdapter>[0]) => GrokAdapter
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
    const tmuxWindow = await opts.spawnTmux({
      session: opts.tmuxSession,
      window: name,
      workdir,
      command: buildClaudeSpawnCommand({
        name,
        sessionId: id,
        claudeSessionId,
        sessionRole: "personal_assistant",
        model,
        effort: opts.resolveEffort?.({ agent, model, reasoningLevel }),
        workdir,
      }),
    })
    if (tmuxWindow?.windowId) {
      registry.sessions.setTmuxWindowId(id, tmuxWindow.windowId)
      void sendChannelConsentEnter(tmuxWindow.windowId)
    }
    opts.onClaudeSessionId?.(id, claudeSessionId)
  } else if (agent === AgentKind.Codex) {
    const sessionHome = join(STATE_DIR, "agents", "codex", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth = await (opts.codexResolveAuth ?? resolveCodexAuth)({
      apiKey: process.env.OPENAI_API_KEY,
      userCodexHome: `${HOME}/.codex`,
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

    await (opts.codexPrepareSessionHome ?? codexPrepareSessionHome)(sessionHome)
    const handle = (opts.codexSpawnAppServer ?? spawnCodexAppServer)({
      codexHome: sessionHome,
      workdir,
      authEnv: auth.env,
      model,
      reasoningLevel: opts.resolveEffort?.({ agent, model, reasoningLevel }),
      pluginConfigArgs: (opts.codexSpawnArgs ?? codexSpawnArgs)({ sessionName: name }).args,
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

    const adapter = (opts.codexAdapterFactory ?? ((opts) => new CodexAdapter(opts)))({
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

    const auth: CursorAuthResult = await (opts.cursorResolveAuth ?? resolveCursorAuth)({
      apiKey: process.env.CURSOR_API_KEY,
      userCursorDir: `${HOME}/.cursor`,
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

    await (opts.cursorSmokeAgent ?? smokeCursorAgent)({ home: sessionHome, authEnv: auth.env })

    const runner = (opts.cursorRunnerFactory ?? makeRealCursorRunner)({ home: sessionHome, authEnv: auth.env })
    const adapter = (opts.cursorAdapterFactory ?? ((opts) => new CursorAdapter(opts)))({
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

    const handle = await (opts.opencodeSpawnServer ?? spawnOpenCodeServer)({
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

    const adapter = (opts.opencodeAdapterFactory ?? ((opts) => new OpenCodeAdapter(opts)))({
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

    // grok reads its credentials from the real ~/.grok, so (unlike cursor/codex)
    // there's no auth to resolve or copy — the child just inherits HOME.
    writeGrokPreamble({ workdir, sessionName: name })

    const adapter = (opts.grokAdapterFactory ?? ((o) => new GrokAdapter(o)))({
      sessionName: name,
      workdir,
      runner: (opts.grokRunnerFactory ?? (() => realGrokRunner))(),
      persistSessionId: async (sid) => { opts.onGrokSessionId?.(name, sid) },
      initialSessionId: undefined,
      model,
      effort: opts.resolveEffort?.({ agent, model, reasoningLevel }),
      mcpServers: buildGrokMcpServers({
        ...shimSpawnSpec(),
        sessionId: id,
        sessionName: name,
        socketsDir: SOCKETS_DIR,
      }),
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

  if (skipRegister) {
    registry.sessions.activate(id, finalPid)
  }
  return { name, id }
}

async function spawnClaudeSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const listWindows = deps.listWindows ?? listSessionWindows
  const base = args.requestedName ?? deriveName(args.workdir)
  // Resolve a window name unique against BOTH taken display names AND existing
  // tmux window names. Worker windows are named after the repo base (e.g.
  // "supermux"); a session that later renames its DISPLAY name keeps its
  // original window name, so the base would otherwise look "free" as a display
  // name and collide with that still-live window. The old path then ran
  // `kill-window -t mux:<name>`, which killed the existing same-named (live!)
  // session — i.e. creating a new session silently killed the previously-active
  // one on the same repo. Uniquifying against live window names means we never
  // collide, so we never need to (and never do) kill a sibling's window.
  const existingWindows = await listWindows(deps.tmuxSession)
  const name = ensureUnique(base, new Set([...deps.registry.takenNames(), ...existingWindows]))
  const id = randomUUID()
  const claudeSessionId = randomUUID()
  deps.registry.reserveName(name)
  preAcceptTrust(args.workdir)
  try {
    await deps.bind(id)
    const tmuxWindow = await deps.spawnTmux({
      session: deps.tmuxSession,
      window: name,
      workdir: args.workdir,
      command: buildClaudeSpawnCommand({ name, model: args.model, effort: args.effort, sessionId: id, claudeSessionId, workdir: args.workdir, rpcMcpConfig: args.rpcMcpConfig }),
    })
    if (tmuxWindow?.windowId) deps.onTmuxWindowId?.(id, tmuxWindow.windowId)
    if (tmuxWindow?.windowId) await (deps.postSpawnReady ?? sendChannelConsentEnter)(tmuxWindow.windowId)
  } catch (err) {
    // Free the reserved name so a retry can reclaim it (see the function doc).
    deps.registry.releaseName(name)
    throw err
  }
  deps.onClaudeSessionId?.(name, claudeSessionId)
  return { name, session_id: id, model: args.model }
}

export async function spawnCodexSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)
  try {
    const sessionHome = join(STATE_DIR, "agents", "codex", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth = await (deps.codexResolveAuth ?? resolveCodexAuth)({
      apiKey: process.env.OPENAI_API_KEY,
      userCodexHome: `${HOME}/.codex`,
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
    await (deps.codexPrepareSessionHome ?? codexPrepareSessionHome)(sessionHome)
    const handle = (deps.codexSpawnAppServer ?? spawnCodexAppServer)({
      codexHome: sessionHome,
      workdir: args.workdir,
      authEnv: auth.env,
      model: args.model,
      reasoningLevel: args.effort,
      pluginConfigArgs: (deps.codexSpawnArgs ?? codexSpawnArgs)({ sessionName: name }).args,
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

    const adapter = (deps.codexAdapterFactory ?? ((opts) => new CodexAdapter(opts)))({
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

export async function spawnCursorSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)
  try {
    const sessionHome = join(STATE_DIR, "agents", "cursor", name)
    mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

    const auth: CursorAuthResult = await (deps.cursorResolveAuth ?? resolveCursorAuth)({
      apiKey: process.env.CURSOR_API_KEY,
      userCursorDir: `${HOME}/.cursor`,
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

// Quick "does cursor-agent run at all" check. Cursor is per-turn so we
// don't pre-launch a real agent; this just confirms the binary is on PATH
// and the env-isolated HOME is readable. Anything else (auth failures, model
// access) will surface on the first user message — which is the right time.
async function smokeCursorAgent(opts: { home: string; authEnv: Record<string, string> }): Promise<void> {
  const { spawn } = await import("child_process")
  await new Promise<void>((resolve, reject) => {
    const env: Record<string, string> = {
      ...(process.env as Record<string, string>),
      ...opts.authEnv,
      HOME: opts.home,
    }
    const child = spawn("cursor-agent", ["--version"], { env, stdio: ["ignore", "pipe", "pipe"] })
    let out = ""
    child.stdout!.on("data", (c: Buffer) => { out += c.toString("utf8") })
    child.stderr!.on("data", () => {})  // drain
    child.on("exit", (code) => {
      if (code === 0) resolve()
      else reject(new Error(`cursor-agent --version exit ${code}; output: ${out.slice(0, 200)}`))
    })
    child.on("error", (err) => reject(new Error(`cursor-agent not runnable: ${err.message}`)))
  })
}

export async function spawnOpenCodeSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
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
 * Mirrors spawnOpenCodeSession but skips name reservation + registry.register
 * (the row already exists). Self-heals the on-disk config/preamble (idempotent),
 * the opencode analogue of codex's codexPrepareSessionHome-on-resume.
 *
 * `opts.spawnServer` is an injection seam for tests. */
export async function resumeOpenCodeSession(
  deps: { resolveAttachment?: (file_id: string) => Promise<string>; onOpenCodeSessionId?: (name: string, sid: string) => void },
  session: { id: string; name: string; workdir: string; agent_home: string; model?: string; agent_session_id?: string },
  opts: { spawnServer?: typeof spawnOpenCodeServer } = {},
): Promise<{ adapter: OpenCodeAdapter; handle: OpenCodeSpawnHandle }> {
  const spawnServer = opts.spawnServer ?? spawnOpenCodeServer
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
  const handle = await spawnServer({ workdir: session.workdir, configHome, authEnv: auth.env })

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

/** grok's worker is an in-process adapter driving a `grok agent stdio` child over
 * ACP. Unlike cursor (per-turn CLI) the child is persistent; unlike codex/opencode
 * it's owned by the adapter, so there's no separate handle and no pid to track —
 * the row is registered with pid 0 and adapter.stop() is the kill.
 *
 * MCP + preamble are NOT files grok reads from a private home: the mux-shim server
 * is handed to grok inline via ACP `session/new`, and the identity preamble goes to
 * AGENTS.md in the workdir (git-excluded, override-safe). sessionHome exists only to
 * give the row an agent_home for resume. */
export async function spawnGrokSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const base = args.requestedName ?? deriveName(args.workdir)
  const name = ensureUnique(base, deps.registry.takenNames())
  const id = randomUUID()
  deps.registry.reserveName(name)

  const sessionHome = join(STATE_DIR, "agents", "grok", name)
  mkdirSync(sessionHome, { recursive: true, mode: 0o700 })

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
    mcpServers: buildGrokMcpServers({
      ...shimSpawnSpec(),
      sessionId: id,
      sessionName: name,
      socketsDir: SOCKETS_DIR,
    }),
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
 * dies with the broker; the session row and grok's own session store persist, so
 * resume respawns the child and re-binds an adapter, loading the prior grok session
 * id when one was persisted (else starting fresh). Self-heals the preamble. */
export async function resumeGrokSession(
  deps: {
    resolveAttachment?: (file_id: string) => Promise<string>
    onGrokSessionId?: (name: string, sid: string) => void
    grokRunnerFactory?: () => GrokRunner
  },
  session: { id: string; name: string; workdir: string; model?: string; effort?: string; agent_session_id?: string },
): Promise<{ adapter: GrokAdapter }> {
  writeGrokPreamble({ workdir: session.workdir, sessionName: session.name })

  const adapter = new GrokAdapter({
    sessionName: session.name,
    workdir: session.workdir,
    runner: (deps.grokRunnerFactory ?? (() => realGrokRunner))(),
    persistSessionId: async (sid) => { deps.onGrokSessionId?.(session.name, sid) },
    initialSessionId: session.agent_session_id || undefined,
    model: session.model,
    effort: session.effort,
    mcpServers: buildGrokMcpServers({
      ...shimSpawnSpec(),
      sessionId: session.id,
      sessionName: session.name,
      socketsDir: SOCKETS_DIR,
    }),
    resolveAttachment: deps.resolveAttachment,
  })
  if (session.agent_session_id) await adapter.resume()
  else await adapter.start()
  return { adapter }
}
