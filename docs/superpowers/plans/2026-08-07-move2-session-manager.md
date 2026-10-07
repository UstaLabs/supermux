# Move 2: SessionManager Component — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `SessionManager` component owns the session state that `main.ts` closures own today. The session handlers move in with the state.

**Architecture:** PR 2 of the spec (revision 5, Move 2). Three stages, one commit each. Stage A merges the duplicate runtime stores. Stage B creates the component and moves the runtime/tailer lifecycle in. Stage C moves the socket handlers (`onRegister`, `onOutbound`, `onOrchestration`) and `killSession` in. The resume trio and `spawnSession` stay in `main.ts` until PR 3 (resume unification) — moving them twice would be waste.

**Tech Stack:** TypeScript on Bun. No new dependencies.

**Design rules (from Ahmet, encode in review):** no kind checks in services; no shared state in agent modules; constructor injection ONCE at boot — never per-call deps bags; collaborators passed as narrow ports, not the world.

---

### Stage A: one runtime store

`main.ts:608-609` holds the same adapter in two stores: `adapters: Map<string, AgentAdapter>` and `runtimes: RuntimeRegistry`. Delete the Map. `RuntimeRegistry` is the single store.

**Files:**
- Modify: `src/main.ts` (~12 call sites)

- [ ] **Step 1: Rewrite every `adapters.` call site**

| Site (main.ts) | Today | Becomes |
|---|---|---|
| 519 | `adapters.get(current.id)` | `runtimes.get(current.id)?.adapter` |
| 604-610 (register/delete helpers) | dual write | `runtimes.set/delete` only |
| 666, 692 | `adapters.get(s.id) as {rpc?...}` | `runtimes.get(s.id)?.adapter as {rpc?...}` |
| 860 | `adapters.get(sessionId)` | `runtimes.get(sessionId)?.adapter` |
| 1327 | `adapters.get(s.id)` | `runtimes.get(s.id)?.adapter` |
| 2472 | `!adapters.has(sessionUuid)` | `!runtimes.has(sessionUuid)` |
| 2519 | `adapters.get(fromSession)` | `runtimes.get(fromSession)?.adapter` |
| 2826 | `getAdapter: (id) => adapters.get(id)` | `getAdapter: (id) => runtimes.get(id)?.adapter` |
| 3187 | `adapters.get(session.id) as any` | `runtimes.get(session.id)?.adapter as any` |

Delete the `adapters` declaration (`main.ts:608`).

- [ ] **Step 2: Verify and commit**

Run: `bun test src/core/ 2>&1 | tail -4` then `bunx tsc --noEmit`
Expected: green, no type errors.

```bash
git add src/main.ts
git commit -m "refactor(runtime): one runtime store — the adapters Map is gone"
```

---

### Stage B: the component owns runtimes, backend, tailers

**Files:**
- Create: `src/core/session-manager/manager.ts`
- Test: `src/core/session-manager/manager.test.ts` (create)
- Modify: `src/main.ts` (construct it; delegate the helper functions)

- [ ] **Step 1: Write the component**

```ts
// src/core/session-manager/manager.ts
import type { Registry } from "./registry"
import type { SessionRuntime } from "./runtime"
import { RuntimeRegistry } from "./runtime"
import type { SessionBackend } from "../runtime/session-backend"
import type { AgentAdapter } from "../agents/types"

/** Narrow ports into the rest of the broker. Injected ONCE at construction.
 *  Keep this to collaborators the component genuinely calls. */
export type SessionManagerPorts = {
  backend: SessionBackend
  tmuxSession: string
  /** Adapter event fan-out (activity, agent-state, errors, commands). Stays a
   *  main.ts function until its sinks move into services. */
  wireAdapterEvents: (adapter: AgentAdapter, sessionId: string) => void
  /** Claude transcript tailer lifecycle (stays main-owned until it becomes a service). */
  ensureClaudeTailer: (sessionId: string, name: string, workdir: string, seekToEnd?: boolean) => void
  stopClaudeTailer: (sessionId: string) => void
}

export class SessionManager {
  readonly registry: Registry
  readonly runtimes = new RuntimeRegistry()
  private readonly ports: SessionManagerPorts

  constructor(registry: Registry, ports: SessionManagerPorts) {
    this.registry = registry
    this.ports = ports
  }

  adapterFor(sessionId: string): AgentAdapter | undefined {
    return this.runtimes.get(sessionId)?.adapter
  }

  registerRuntime(sessionId: string, runtime: SessionRuntime): void {
    this.runtimes.set(sessionId, runtime)
    this.ports.wireAdapterEvents(runtime.adapter, sessionId)
  }

  deleteRuntime(sessionId: string): void {
    this.runtimes.delete(sessionId)
  }
}
```

Then move `registerClaudeRuntime` / `registerCodexRuntime` / `registerCursorRuntime` / `registerOpenCodeRuntime` / `registerGrokRuntime` (main.ts:613-651) onto the class as methods, preserving their exact bodies (onExit hooks included). `main.ts` keeps thin `const registerCodexRuntime = (…) => sessions.registerCodexRuntime(…)` aliases so call sites need no churn in this stage. NOTE: claude's runtime wiring uses `wireClaudeStateEvents`, not `wireAdapterEvents` — keep that distinction; the claude register method takes the wired adapter as-is and only stores it.

- [ ] **Step 2: Test the component**

```ts
// src/core/session-manager/manager.test.ts — spirit, adapt to real types:
// - registerRuntime stores the runtime and wires events exactly once
// - deleteRuntime removes it; adapterFor returns undefined after
// - double-delete is a no-op
```

- [ ] **Step 3: Construct in main.ts, delegate, verify, commit**

`const sessions = new SessionManager(registry, {...})` right after the `registry` construction. Replace the `runtimes` const with `sessions.runtimes` (or keep a `const runtimes = sessions.runtimes` alias). Run the suite. Commit: `feat(session-manager): SessionManager component owns the runtime store`.

---

### Stage C: the handlers move in

**Files:**
- Modify: `src/core/session-manager/manager.ts` (grow), `src/main.ts` (shrink)

- [ ] **Step 1: Move `killSession` + `unregisterSession`**

They become `sessions.kill(id)` and `sessions.unregister(id)`. New ports needed: `terminalManager.killAllForSession`, `displayManager.killAllForSession`, `fsWatcher.killSession`, proxy removal + broadcast, draft-attachment release, worktree reclaim, per-session store cleanup (`agentStateStore.clear`, `recentInboundIds.clear`, `pendingReapply.clear`, `bgTaskStore.clear`, `commandRegistry.remove`, `gitStatusService.sync`). Group them as a `cleanup: {...}` port object. The per-agent kill switch (main.ts:2090-2116) moves verbatim — it is the kill ladder PR 5 later dissolves into handles.

- [ ] **Step 2: Move the three socket handlers**

`onRegister` (attach-only now, ~45 lines), `onOutbound`, `onOrchestration` become methods; `startSocketServer` in main.ts passes `sessions.handleRegister.bind(sessions)` etc. Their remaining main.ts dependencies (`onAssistantMessage`, `interruptClaudePane`, spawn/kill entry points for orchestration) enter as ports. If a port would just re-export another method of the component, call the method directly.

- [ ] **Step 3: Verify, commit**

Full suite + typecheck + `.mux/verify.sh`. Commit: `feat(session-manager): socket handlers and kill move into the component`.

---

## Non-goals (PR 3+ territory)

- resume trio + `spawnSession` move and unification (PR 3)
- supervisor takes the component (PR 4)
- uniform inert handles / kill-ladder dissolution (PR 5)
- seam removal (PR 6)

## Self-review

Stage boundaries keep every commit green. No placeholder steps: Stage C step bodies name the exact functions and their new homes; the mechanical bodies move verbatim (this plan deliberately does not restate 200 lines of moved code — the invariant is "move, don't redesign", enforced by the suite staying green).
