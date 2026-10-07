# Move 5: Per-Agent Module Moves — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Executed via reviewed subagents, kind by kind, suite green after each.

**Goal:** Agent-specific spawn/resume code lives ONLY in agent directories. One `agents` map dispatches by kind.

**Architecture:** PR 5 of the spec (revision 5). Order: grok → cursor → opencode → codex → claude. Per kind, two moves:
1. `spawn<Kind>Session` moves verbatim from `src/core/session-manager/spawn-helper.ts` to `src/core/agents/<kind>/session.ts` as `export async function spawn(deps, args)`. Its imports move with it. spawn-helper keeps the ctx assembly and dispatches through the map.
2. The resume ARM's dialect half moves from `SessionManager.resume<Kind>Arm` to the same file as `export async function resume(ctx, session, name)` returning the constructed `{ adapter, handle? }`. The COMPONENT keeps the state half: registration + event wiring. Dialect in agent files; state in the component — that is the layer rule.

`ResumeCtx` (shared type in `src/core/agents/session-types.ts`): `{ sessionEffort(s): string | undefined; resolveAttachment(id): Promise<string>; persistAgentSessionId(sid: string): void }`. The opencode/grok resume helpers already in spawn-helper move into their agent dirs and merge with the new `resume` functions.

**The map** (last step, after all five kinds):

```ts
// src/core/agents/registry.ts
import * as claude from "./claude/session"
// ... codex, cursor, opencode, grok
export const agents = { claude, codex, cursor, opencode, grok } satisfies Record<AgentKind, AgentModule>

type AgentModule = {
  spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult>
  /** Dialect half of resume; the SessionManager registers + wires the result. */
  resume?(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: AgentAdapter; handle?: unknown }>
}
```

claude has no `resume` module function in this PR (its arm is per-source inside the frames — it moves when the frames themselves need it, not before).

**Not in this PR:** `applyConfig` unification (the reapply ladder stays in main.ts — separate follow-up), auth/login/preamble reshuffling (Steps 2-3 of the six-step program), seam removal (PR 6).

**Per-kind checklist:** move spawn → suite green → commit; move resume dialect → suite green → commit. Never both kinds in one commit. Baseline: full suite = 2 known opencode-Windows order-pollution failures, nothing else.
