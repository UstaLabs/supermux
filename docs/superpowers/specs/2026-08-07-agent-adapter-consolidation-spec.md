# Session subsystem consolidation — the three moves

**Date:** 2026-08-07 (revision 5, same day)
**Author:** supermux session (adapter-consolidation)
**Status:** Draft, revision 5, awaiting review
**Architecture page:** https://claude.ai/code/artifact/2aa63cdd-8f14-410f-bb40-97861c9c6b1f
**Scope:** Step 1 of 6. The later steps come as their own PRs: (2) auth
consolidation, (3) preamble unification, (4) grok plugin adapter, (5) session
mode field, (6) headless claude prototype.

## Summary

We understand the system now. A full structural pass mapped the session
subsystem: the ownership graph, the deps-bag autopsy, and the call flows. The
verdict changes the spec's shape. The deps bag is a symptom, not the disease.
Three structural moves fix the structure, in dependency order:

1. Give claude's session row a birthplace.
2. Give the session state an owner.
3. Move the test seams out of the production signatures.

The per-agent interface comes last. After moves 1 and 2, the calls that remain
per kind ARE the interface. We discover it; we do not invent it. **The behavior
does not change**, except the flagged bug fixes.

## Motivation (findings of the structural pass)

- `main.ts` is 4,090 lines of module-level code. Its module scope is the
  composition root AND the DI container. It owns ALL session state: the
  registry (:264), the adapters Map (:608), the runtimes RuntimeRegistry (:609)
  — the SAME adapter object stored twice — the socket server (:2442), the
  channels, the tailers (:420), and four `pending*` staging maps (:573-585).
- Approximately 1,300 lines of main.ts are session logic: onRegister
  (2471-2602, 132 lines), onOutbound (2603-2696), onOrchestration (2697-2915,
  219 lines), killSession (2073-2133), resumeSuspendedSession (2164-2256),
  resumeFromArchive (2258-2391), resumeNonClaudeAdapters (3772-3895),
  reapplySessionAgentConfig (3146-3246), spawnSession (2985-3144), and
  wireAdapterEvents (945-969).
- `session-manager/` is a function library: 40 files, no component, no state.
- The `SpawnDeps` autopsy (26 members, spawn-helper.ts:62-93): 16 are test
  seams (`X ?? realX` over symbols the file already imports statically;
  main.ts re-supplies 6 of them with their own defaults at 1574-1579,
  3433-3438). 6 are gratuitous callbacks — each one only calls
  `registry.sessions.setAgentSessionId`, and the callee already holds
  `deps.registry`. 1 is dead (`spawnTmux` — never dereferenced). 2 are
  irreducible (onClaudeSessionId, onRuntimeTargetId).
- **The structural fault:** the claude session row is born inside the socket
  onRegister callback (main.ts:2536), approximately one second after spawn.
  The spawn path does not create it — yet the spawn path already knows every
  id (it generates `--session-id` and MUX_SESSION_ID,
  spawn-helper.ts:460-466). All the bridge machinery is downstream of this one
  fault: the 4 staging maps, the 10-second `waitForRegisteredSession` poll
  (main.ts:3093), the by-uuid-OR-by-name drain (main.ts:2583), and both
  irreducible callbacks. Codex mints its row synchronously
  (spawn-helper.ts:528) and needs none of it.
- Duplication counts: the codex spawn sequence exists 6× (spawnPA,
  spawnCodexSession, resumeSuspended, resumeFromArchive, resumeNonClaude,
  reapply); cursor 5×; the claude tmux-spawn 5×; the registerAdapter
  instanceof-closure 3× verbatim (main.ts:1539, 3036, 3398); the
  `session_added` broadcast literal 7×.
- **New live bug (found by this pass):** main.ts:3750 fills only 7 of the
  supervisor's 20 opts. `registerAdapter` and the `on*SessionId` callbacks are
  missing. A supervisor-respawned non-claude PA starts its adapter and then
  drops it: the adapter is never registered, so every inbound returns
  `adapter_not_ready`, and the session loses its resume identity.
- Known bugs from the earlier passes: suspended-resume skips the codex/cursor
  preamble rewrite, and suspended-resume has no opencode/grok arm
  (`resume_suspended_no_path`, main.ts:2246-2248).
- Same-state-two-owners: 10 cases. The adapter has 2 stores; connectedness has
  3; liveness has 3 oracles; the window id has 3; the agent session id has 2;
  the name has 3; the session record is cached AND in sql, and name lookups
  fabricate `pid:0`/`connected:false`; model/effort have 3.
- No circular imports exist. Every dynamic import is vestigial. The bags are
  NOT cycle-breaking; they never were.

## Move 1 — give claude's row a birthplace

The spawn path creates the claude session row synchronously, exactly like
codex. It already holds every id it needs. `onRegister` shrinks to one job:
attach the adapter to an existing row. The reconnect branch already does this
today (main.ts:2479), so the shrink follows a proven path.

This move deletes the downstream machinery: the 4 staging maps, the
registration poll, the by-uuid-OR-by-name drain, and both irreducible
callbacks.

Risk note: a register frame can arrive for a row that was killed in the gap.
`onRegister` must handle "row missing" by refusal — log and close the socket.
It must never create a row again.

## Move 2 — give the state an owner

We add one component: `SessionManager`
(`src/core/session-manager/manager.ts`). It owns the state that main.ts's
module scope owns today:

- the registry,
- ONE merged runtime map — the adapters Map and the RuntimeRegistry become one
  `Map<sessionId, { kind, adapter, handle }>`, with an inert handle where no
  broker-owned process exists,
- the socket server, the sessionBackend, the tailers, recentInboundIds, and
  pendingReapply.

The session logic moves in with the state: spawnSession, resumeSession (ONE
function, all five kinds, all three sources — this fixes the preamble-skip bug
and the missing opencode/grok arms), killSession, applyConfig,
wireAdapterEvents, and the onRegister/onOutbound/onOrchestration handlers.

main.ts shrinks to a composition root: construct the SessionManager, construct
the channels and services, wire them. The supervisor takes the SessionManager
directly instead of a 20-member bag — that fixes the half-bag PA bug
structurally, because a missing member can no longer exist. The 3× duplicated
registerAdapter closures die with their call sites.

## Move 3 — test seams leave production signatures

The 16 `X ?? realX` members leave the production types. Tests use bun's module
mocking, or one shared test-context. The 5-dep shape of `inbound-delivery` is
the model for any bag that remains: few members, all real dependencies, no
defaults that shadow static imports.

## Then: the per-agent surface (discovered)

After moves 1 and 2, the SessionManager calls exactly this per kind: spawn,
resume, applyConfig, and kill (the handle covers kill). The services call the
optional leaves. That set IS the interface — we read it off the call sites:

```ts
// src/core/agents/registry.ts — the ONE new file
import * as claude from "./claude"
import * as codex from "./codex"
import * as cursor from "./cursor"
import * as opencode from "./opencode"
import * as grok from "./grok"

export const agents = { claude, codex, cursor, opencode, grok }
  satisfies Record<AgentKind, AgentModule>

type AgentModule = {
  // core — required, called by SessionManager
  spawn(ctx: SpawnCtx): Promise<SessionRuntime>
  resume(ctx: ResumeCtx): Promise<SessionRuntime>
  applyConfig(rt: SessionRuntime, opts: { model?: string; effort?: string }): Promise<"live" | "restarted">
  isAuthed(): Promise<boolean>
  // per-concern leaves — optional, called ONLY by their owning service;
  // an undefined leaf means "the agent does not support it"
  listSkills?(session: SessionRef): Promise<Skill[]>
  transcriptPath?(sessionId: string): string | null
  loginCommand?(): { cmd: string; env?: Record<string, string>; pty?: boolean; needsCode?: boolean }
  writePreamble?(opts: { sessionName: string; workdir: string; agentHome?: string }): void
}
```

`AgentModule` is a type alias, not an interface; `satisfies` checks the map.
Auth resolution, config writers, watchdogs, and keystroke workarounds stay
private inside each agent directory. The review checklist for every PR has two
items: **no kind checks in services; no shared state in agent modules.**

## Migration plan

Each PR keeps the whole test suite green.

1. **Move 1.** The claude row gets its birthplace in the spawn path;
   onRegister shrinks to attach-or-refuse. Smallest PR, highest leverage.
2. **SessionManager skeleton.** The component takes ownership of the state;
   the handlers move in mechanically.
3. **resumeSession unification.** One function replaces the three ladders.
   Fixes the preamble-skip bug and the missing opencode/grok arms (flagged).
4. **Supervisor takes SessionManager.** Fixes the half-bag PA bug (flagged).
5. **Per-agent module moves.** grok → cursor → opencode → codex → claude;
   each kind's spawn/resume/applyConfig moves into its directory, and the
   `agents` map replaces the switches.
6. **Seam removal and dead code.** The 16 test seams leave; `spawnTmux` and
   the vestigial dynamic imports go.

## Test strategy

The existing suites stay the no-behavior-change proof (all under
`src/core/session-manager/`): `spawn-helper-cursor.test.ts`,
`spawn-helper-grok.test.ts`, `opencode-resume.test.ts`, `runtime.test.ts`,
`spawn-registration.test.ts`, `resume-pid.test.ts`, `pending-reapply.test.ts`,
`interrupt.test.ts`, `supervisor-suspend-log.test.ts`,
`spawn-command.test.ts`, `shim-spawn.test.ts`.

New tests:

- One parameterized smoke test over `agents`
  (`src/core/agents/registry.test.ts`): spawn, resume, and kill per kind with
  mocked deps, plus the preamble-file assertion after spawn AND after resume
  with each source. The preamble assertion is the regression lock on the
  drift bug.
- A regression test for the supervisor PA bug: a supervisor-respawned codex PA
  receives an inbound message and answers.
- A regression test for onRegister-row-missing: a register frame for a killed
  row is refused (log + close), and no row is created.

## Deferred (knowledge kept)

- **Multi-account:** deferred to "the spawn ctx grows an optional
  `account?: string`". Mechanics for later, from the T3 Code study: claude
  needs a per-account `CLAUDE_CONFIG_DIR`, never a HOME override (that breaks
  the macOS Keychain login); codex needs a shadow-home symlink overlay
  (private auth.json as a real file, the rest linked to the shared
  `~/.codex`); resume gating needs a `continuationKey` (same key ⇒ same
  visible transcripts).
- **Headless claude:** stays a Step 6 flag-gated prototype. It is a hedge:
  Anthropic paused the June 15 2026 billing split on its announcement day, and
  `claude -p` still draws from the normal subscription limits
  (support.claude.com article 15036540). The split is paused, not canceled.

## Revision history

- **Revision 1** defined a large `AgentIntegration` interface with
  capabilities, AuthProvider, and PreambleWriter.
- **Revision 2** resolved six open questions and added multi-account types and
  a resumeCursor.
- **Revision 3** slimmed the contract to seven members after a consumer audit.
- **Revision 4** removed the interfaces: a plain module map with `satisfies`,
  single-place services, agent code only in agent files.
- **Revision 5** (this one) reorders the work around the structural pass:
  understand first, three moves in dependency order, and the per-agent
  interface is discovered from the call sites, not designed.

## References

- Architecture page —
  https://claude.ai/code/artifact/2aa63cdd-8f14-410f-bb40-97861c9c6b1f
- T3 Code — github.com/pingdotgg/t3code
- Vibe Kanban executors crate — github.com/BloopAI/vibe-kanban
- Terragon OSS daemon — github.com/terragon-labs/terragon-oss
- Happy — github.com/slopus/happy
- ACP v2 draft — agentclientprotocol.com/announcements/acp-v2-draft
- Claude billing split pause — support.claude.com article 15036540
