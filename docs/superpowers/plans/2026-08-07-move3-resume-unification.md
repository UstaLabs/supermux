# Move 3: Resume Unification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Executed inline by the session that wrote it (code context is fresh).

**Goal:** One resume flow in `SessionManager` serves all five kinds and all three sources. The two known resume bugs die, plus a third found during planning.

**Architecture:** PR 3 of the spec (revision 5). The three ladders (`resumeSuspendedSession` main.ts:2096, `resumeFromArchive` main.ts:2190, `resumeNonClaudeAdapters` main.ts:3198) move into `SessionManager` as three thin per-source frames over ONE set of per-kind arms. The arms are shared; the frames keep their source-specific behavior (logging, activate-vs-resume, broadcast) verbatim.

**Bugs fixed here (flag in the commit):**
1. Suspended resume gains opencode/grok arms (today: `resume_suspended_no_path`).
2. Codex/cursor preambles are rewritten on EVERY resume (today: never on any resume path; opencode/grok already rewrite via their spawn-helper helpers).
3. The boot cursor arm gains `pluginArgs` (today it omits `cursorSpawnArgs(...)` that the suspended/archive arms pass — a boot-resumed cursor session silently loses plugin dirs).

## Tasks

- [ ] **1. Ports:** add a `resume` group to `SessionManagerPorts`: `bind`, `ensureSessionWorktree`, `sessionEffort`, `resolveAttachment`, `wireAdapterEvents`, `sessionBackend`, `tmuxSession`. Module-level functions (spawn specs, auth resolvers, writers, adapters, the opencode/grok resume helpers, `resumedSessionPid`, `ensureUnique`) are imported directly — they are modules, not main.ts closures.
- [ ] **2. Arms** (private methods, shared by all sources): `resumeCodexArm(session, name)` (+preamble rewrite), `resumeCursorArm(session, name)` (+preamble rewrite, +pluginArgs everywhere), `resumeOpenCodeArm(session, name)`, `resumeGrokArm(session, name)`. Claude stays per-source inside the frames (suspended: kill old window + unique window name + waitForConnected(25s) + activate; archive: plain create; boot: none — reconcile handles claude).
- [ ] **3. Frames:** `resumeSuspended(session): boolean`, `resumeFromArchive(sessionId): {ok,name?,error?}`, `resumeAtBoot(): void` — bodies moved from main.ts, arms deduplicated, log lines preserved per source.
- [ ] **4. main.ts:** the three functions become aliases/one boot call; `waitForSessionConnected` moves into the component if main has no other caller.
- [ ] **5. Tests:** extend `manager.test.ts`: suspended-resume for opencode/grok arms exists (fake helpers via module mock is overkill — assert via a codex-free path: cursor arm with fake auth seams is not injectable here, so instead assert the FRAME behavior: unknown agent → false; archived-only guard). Real coverage: the existing `opencode-resume.test.ts` + `resume-pid.test.ts` + full suite stay green. Add one regression test: `resumeSuspended` with agent "opencode" and no `agent_home` returns false (not `resume_suspended_no_path` for a kind that now HAS a path but lacks its home).
- [ ] **6. Verify:** `bunx tsc --noEmit` clean; full `bun test` = baseline (2 known failures); commit.
