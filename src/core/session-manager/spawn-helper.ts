import { Registry } from "./registry"
import { preAcceptTrust } from "./trust"
// Type-only dialect imports: they shape the registerAdapter port and are
// erased at runtime. The dialect CODE lives in the agents/<kind>/session.ts
// modules, dispatched through the agents map.
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CursorAdapter } from "../agents/cursor/adapter"
import type { OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import type { OpenCodeAdapter } from "../agents/opencode/adapter"
import type { GrokAdapter } from "../agents/grok/adapter"
// Dispatcher-only import: the per-agent session modules import types/helpers
// back from this file, which is a benign cycle as long as neither side
// dereferences the other at module-init time (functions + types only).
import { agents } from "../agents/registry"
import { mkdirSync } from "fs"
import { randomUUID } from "crypto"
import { execSync } from "child_process"
import { scanRepos } from "../editor/repo-scanner"
import { home } from "../../shared/home"
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
  /** Reuse this session id instead of minting one (PA respawn path). */
  id?: string
  /** Personal-assistant spawn deltas. Present only on the spawnPA path: keep
   *  the exact requested name (no uniquify/reserve), register via registerPA
   *  (with is_default), or skip registration when the row already exists. */
  pa?: {
    /** The row already exists (supervisor respawn with a persisted id). */
    skipRegister: boolean
  }
}

export type SpawnResult = {
  name: string
  session_id: string
  model?: string
  /** Pid of the spawned worker (0 = no persistent process). Used by the PA
   *  dispatcher to activate a reused row. */
  pid?: number
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

// Thin PA dispatcher over the same per-agent spawn modules. The PA deltas ride
// in args.pa/args.id; the kind-independent prologue (workdir + trust) and tail
// (activate a reused row) live here.
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
  const skipRegister = opts.id ? !!registry.get(opts.id) || !!registry.resolveName(name) : false

  mkdirSync(workdir, { recursive: true })
  preAcceptTrust(workdir)

  const mod = agents[agent] as (typeof agents)[AgentKind] | undefined
  if (!mod) {
    // Unknown kind on a legacy row: mirror the old ladder's silent fall-through.
    if (skipRegister) registry.sessions.activate(id, process.pid)
    return { name, id }
  }

  const r = await mod.spawn(
    {
      registry,
      bind: opts.bind,
      tmuxSession: opts.tmuxSession,
      resolveAttachment: opts.resolveAttachment,
      registerAdapter: opts.registerAdapter,
      // PA thread-id persistence is keyed by the broker session id (the
      // supervisor writes setAgentSessionId directly); adapt codex's
      // name-keyed port to that contract.
      onThreadId: (_name, threadId) => opts.onCodexSessionId?.(id, threadId),
      onCursorSessionId: opts.onCursorSessionId,
      onOpenCodeSessionId: opts.onOpenCodeSessionId,
      onGrokSessionId: opts.onGrokSessionId,
    },
    {
      workdir,
      requestedName: name,
      agent,
      model,
      reasoningLevel,
      effort: opts.resolveEffort?.({ agent, model, reasoningLevel }),
      id,
      pa: { skipRegister },
    },
  )

  // A reused row keeps living (the fresh worker pid replaces the dead one);
  // a fresh claude PA activates with its pane pid, exactly like before.
  if (skipRegister || agent === AgentKind.Claude) {
    registry.sessions.activate(id, r.pid ?? process.pid)
  }
  return { name: r.name, id: r.session_id }
}
