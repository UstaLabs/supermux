# Session ⇄ Base-Branch Diff Status — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show, at a glance for every session, how its worktree diverges from the branch it integrates into — ahead/behind/dirty — as a glyph badge in the session list and a mode-aware header pill, computed cheaply and pushed live.

**Architecture:** A new broker service computes a cheap per-session `GitLiteStatus` (2 async git calls), caches it in memory, and recomputes it on three triggers — a refcounted `.git`-metadata fs-watch, the agent turn-end (`AgentStateStore` idle), and a reconcile on snapshot. Results ride the WS snapshot + a new `session_git` delta frame. The PWA renders a glyph badge; the chat-header pill becomes mode-aware. Native gets the wire field only.

**Tech Stack:** TypeScript broker (Bun runtime, `bun:test`), Vue 3 + Pinia PWA (`vue-tsc`), Kotlin Multiplatform shared DTOs (kotlinx.serialization).

**Spec:** `docs/superpowers/specs/2026-06-24-session-base-branch-diff-status-design.md`

**Comparison axis (the core rule):**
- Worktree-backed session (`repo_root && base_branch && session_branch`) → `mode:"base"`, ahead/behind vs `base_branch`.
- Plain git session → `mode:"remote"`, ahead/behind vs `@{upstream}`.
- Not a git repo → `null` (no badge).

**Commands (from repo root unless noted):**
- Single backend test file: `bun test <path>`
- Whole backend suite: `bun test`
- Backend typecheck: `bun run typecheck`
- Web typecheck: `cd src/web-app && bunx vue-tsc --noEmit` (or `npm run build`)
- Web store tests: `bun test src/web-app/src/stores/<file>.test.ts`

---

## File Structure

**New (broker):**
- `src/core/worktree/lite-status.ts` — `GitLiteStatus` type + `computeLiteStatus()` (pure, async git).
- `src/core/worktree/lite-status.test.ts` — temp-repo tests.
- `src/core/worktree/git-status-service.ts` — `GitStatusService` (cache, single-flight, debounce, refcounted watches, `sync()`).
- `src/core/worktree/git-status-service.test.ts` — injected-deps + one live test.

**Modified (broker):**
- `src/channels/web/index.ts` — `SessionSnapshot.git` field.
- `src/main.ts` — construct service, `sync()` in snapshot + spawn, idle recompute hook, `session_git` broadcast, `git` in snapshot map.

**New (web):**
- `src/web-app/src/stores/gitStatus.ts` — per-session `GitLiteStatus` store + `GitLiteStatus` type.
- `src/web-app/src/stores/gitStatus.test.ts`
- `src/web-app/src/lib/gitBadge.ts` — pure badge formatter.
- `src/web-app/src/lib/gitBadge.test.ts`

**Modified (web):**
- `src/web-app/src/api/ws.ts` — hydrate + `session_git` dispatch.
- `src/web-app/src/components/SessionRow.vue` — render glyph badge.
- `src/web-app/src/components/BranchSyncStatus.vue` — mode-aware headline.

**Modified (native wire-compat):**
- `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` — `GitLiteStatusDto`, `SessionInfo.git`, `ServerFrame.SessionGit`.
- `apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt` — exhaustive `when` + name.
- `apps/shared/src/jvmTest/resources/frames/session_git.json` — fixture.
- `apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt` — parse tests.

---

## Task 1: `computeLiteStatus` + `GitLiteStatus` type

**Files:**
- Create: `src/core/worktree/lite-status.ts`
- Test: `src/core/worktree/lite-status.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/core/worktree/lite-status.test.ts`:

```typescript
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree } from "./manager"
import { computeLiteStatus } from "./lite-status"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }
function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-lite-")); execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "config", "user.email", "t@t.t"); g(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "f.txt"), "1\n"); g(dir, "add", "."); g(dir, "commit", "-m", "init"); return dir
}
/** A working repo on branch `mux/s` whose `origin` is a local bare repo. */
function repoWithRemote(): { work: string; bare: string } {
  const bare = mkdtempSync(join(tmpdir(), "mux-lbare-")); execFileSync("git", ["init", "--bare", "-b", "main", bare])
  const work = mkdtempSync(join(tmpdir(), "mux-lwork-")); execFileSync("git", ["init", "-b", "main", work])
  g(work, "config", "user.email", "t@t.t"); g(work, "config", "user.name", "t")
  writeFileSync(join(work, "f.txt"), "1\n"); g(work, "add", "."); g(work, "commit", "-m", "init")
  g(work, "remote", "add", "origin", bare); g(work, "push", "-u", "origin", "main"); g(work, "checkout", "-b", "mux/s")
  return { work, bare }
}

test("base mode: counts commits ahead of base and dirty files", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  writeFileSync(join(h.worktreeDir, "b.txt"), "y") // untracked → dirty
  const r = await computeLiteStatus(
    { workdir: h.worktreeDir, repo_root: repo, base_branch: "main", session_branch: h.sessionBranch }, 123)
  expect(r).toEqual({ mode: "base", compareRef: "main", ahead: 1, behind: 0, dirty: 1, computedAt: 123 })
})

test("base mode: clean worktree reports zeros", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  const r = await computeLiteStatus(
    { workdir: h.worktreeDir, repo_root: repo, base_branch: "main", session_branch: h.sessionBranch }, 1)
  expect(r).toEqual({ mode: "base", compareRef: "main", ahead: 0, behind: 0, dirty: 0, computedAt: 1 })
})

test("remote mode: unpublished branch has no upstream", async () => {
  const { work } = repoWithRemote() // on mux/s, never pushed
  const r = await computeLiteStatus({ workdir: work }, 5)
  expect(r?.mode).toBe("remote")
  expect(r?.unpublished).toBe(true)
})

test("remote mode: ahead of upstream after a local commit", async () => {
  const { work } = repoWithRemote()
  g(work, "push", "-u", "origin", "mux/s")
  writeFileSync(join(work, "c.txt"), "z"); g(work, "add", "."); g(work, "commit", "-m", "local")
  const r = await computeLiteStatus({ workdir: work }, 7)
  expect(r?.mode).toBe("remote")
  expect(r?.ahead).toBe(1)
  expect(r?.behind).toBe(0)
  expect(r?.unpublished).toBeUndefined()
})

test("returns null for a non-repo directory", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-norepo-"))
  expect(await computeLiteStatus({ workdir: dir }, 1)).toBeNull()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/worktree/lite-status.test.ts`
Expected: FAIL — `Cannot find module './lite-status'` / `computeLiteStatus is not a function`.

- [ ] **Step 3: Write the implementation**

Create `src/core/worktree/lite-status.ts`:

```typescript
import { execFile } from "node:child_process"
import { promisify } from "node:util"

const pexec = promisify(execFile)

export interface GitLiteStatus {
  mode: "base" | "remote"   // worktree → base, plain repo → remote
  compareRef: string        // "main" | "origin/feature-x" — for the label
  ahead: number             // commits in HEAD not in compareRef
  behind: number            // commits in compareRef not in HEAD
  dirty: number             // uncommitted + untracked (gitignore-respected)
  unpublished?: boolean     // mode:"remote" only — no upstream yet
  computedAt: number        // epoch ms
}

export interface LiteStatusInput {
  workdir: string
  repo_root?: string | null
  base_branch?: string | null
  session_branch?: string | null
}

async function runGit(cwd: string, args: string[], timeout = 30_000): Promise<{ ok: boolean; out: string }> {
  try {
    const { stdout } = await pexec("git", args, { cwd, encoding: "utf-8", timeout, maxBuffer: 16 * 1024 * 1024 })
    return { ok: true, out: stdout.trim() }
  } catch (e: any) {
    return { ok: false, out: String(e?.stdout ?? "").trim() }
  }
}

/** Count of uncommitted + untracked files (excludes ignored — git's own rules). */
async function dirtyCount(cwd: string): Promise<number> {
  const r = await runGit(cwd, ["status", "--porcelain"])
  if (!r.ok || !r.out) return 0
  return r.out.split("\n").filter((l) => l.trim().length > 0).length
}

/**
 * Cheap, async per-session git status for the at-a-glance badge.
 * Worktree-backed sessions compare vs base_branch; plain repos vs @{upstream}.
 * Returns null for non-repos or on git failure (badge hidden).
 */
export async function computeLiteStatus(s: LiteStatusInput, now: number = Date.now()): Promise<GitLiteStatus | null> {
  const cwd = s.workdir
  const worktreeBacked = !!(s.repo_root && s.base_branch && s.session_branch)

  if (worktreeBacked) {
    const base = s.base_branch as string
    const ab = await runGit(cwd, ["rev-list", "--count", "--left-right", `${base}...HEAD`])
    if (!ab.ok) return null
    const [b, a] = ab.out.split(/\s+/)
    const dirty = await dirtyCount(cwd)
    return { mode: "base", compareRef: base, ahead: Number(a) || 0, behind: Number(b) || 0, dirty, computedAt: now }
  }

  // remote mode
  const inside = await runGit(cwd, ["rev-parse", "--is-inside-work-tree"])
  if (!(inside.ok && inside.out === "true")) return null
  const br = await runGit(cwd, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  const branch = br.ok && br.out ? br.out : null
  const dirty = await dirtyCount(cwd)
  if (!branch) return { mode: "remote", compareRef: "", ahead: 0, behind: 0, dirty, unpublished: true, computedAt: now }
  const up = await runGit(cwd, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
  const upstream = up.ok && up.out ? up.out : null
  if (!upstream) return { mode: "remote", compareRef: branch, ahead: 0, behind: 0, dirty, unpublished: true, computedAt: now }
  const counts = await runGit(cwd, ["rev-list", "--count", "--left-right", "@{upstream}...HEAD"])
  let ahead = 0, behind = 0
  if (counts.ok) { const [b, a] = counts.out.split(/\s+/); behind = Number(b) || 0; ahead = Number(a) || 0 }
  return { mode: "remote", compareRef: upstream, ahead, behind, dirty, computedAt: now }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/worktree/lite-status.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/worktree/lite-status.ts src/core/worktree/lite-status.test.ts
git commit -m "feat(worktree): computeLiteStatus — cheap async base/remote git status"
```

---

## Task 2: `GitStatusService` (cache, single-flight, debounce, refcounted watches)

**Files:**
- Create: `src/core/worktree/git-status-service.ts`
- Test: `src/core/worktree/git-status-service.test.ts`

This service is fully dependency-injected so the cache/single-flight/debounce/fan-out logic is testable without real fs or git. Real wiring is supplied in Task 3.

- [ ] **Step 1: Write the failing test**

Create `src/core/worktree/git-status-service.test.ts`:

```typescript
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree } from "./manager"
import { computeLiteStatus, type GitLiteStatus } from "./lite-status"
import { GitStatusService, type ServiceSession, type GitStatusServiceDeps } from "./git-status-service"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }

// A controllable test harness: manual scheduler, fake watcher, scriptable compute.
function harness(over: Partial<GitStatusServiceDeps> = {}) {
  const scheduled: Array<{ id: number; fn: () => void; live: boolean }> = []
  let seq = 0
  const watchers = new Map<string, () => void>()    // dir → onEvent
  const changes: Array<[string, GitLiteStatus | null]> = []
  const deps: GitStatusServiceDeps = {
    compute: async (s) => ({ mode: "base", compareRef: "main", ahead: 1, behind: 0, dirty: 0, computedAt: s.id.length }),
    resolveGitDirs: (s) => ({ gitDir: `/git/${s.id}`, commonDir: `/common` }),
    watch: (dir, onEvent) => { watchers.set(dir, onEvent); return { close: () => watchers.delete(dir) } },
    onChange: (id, git) => changes.push([id, git]),
    schedule: (fn) => { const id = ++seq; scheduled.push({ id, fn, live: true }); return id },
    cancel: (h) => { const s = scheduled.find((x) => x.id === h); if (s) s.live = false },
    debounceMs: 400,
    ...over,
  }
  const flush = () => { for (const s of scheduled) if (s.live) { s.live = false; s.fn() } }
  return { deps, scheduled, watchers, changes, flush, svc: new GitStatusService(deps) }
}

test("sync tracks a session, debounced initial recompute fires once → cached + onChange", async () => {
  const h = harness()
  h.svc.sync([{ id: "abc", workdir: "/w" }])
  expect(h.scheduled.length).toBe(1)        // debounced, not yet run
  h.flush()
  await Promise.resolve(); await Promise.resolve()
  expect(h.changes.length).toBe(1)
  expect(h.svc.get("abc")?.ahead).toBe(1)
})

test("multiple schedules within the window coalesce to one compute", async () => {
  let computes = 0
  const h = harness({ compute: async () => { computes++; return { mode: "base", compareRef: "m", ahead: 0, behind: 0, dirty: 0, computedAt: 0 } } })
  h.svc.sync([{ id: "abc", workdir: "/w" }])   // schedule #1
  h.svc.scheduleRecompute("abc")                // schedule #2 cancels #1
  h.svc.scheduleRecompute("abc")                // schedule #3 cancels #2
  h.flush()
  await Promise.resolve(); await Promise.resolve()
  expect(computes).toBe(1)
})

test("single-flight: a recompute requested mid-flight reruns exactly once after", async () => {
  let resolve!: () => void
  let computes = 0
  const gate = new Promise<void>((r) => { resolve = r })
  const h = harness({ compute: async () => { computes++; if (computes === 1) await gate; return { mode: "base", compareRef: "m", ahead: computes, behind: 0, dirty: 0, computedAt: 0 } } })
  h.svc.sync([{ id: "abc", workdir: "/w" }]); h.flush()          // first compute starts, awaits gate
  await Promise.resolve()
  h.svc.scheduleRecompute("abc"); h.flush()                       // requested mid-flight → queued
  resolve()
  await new Promise((r) => setTimeout(r, 0)); await new Promise((r) => setTimeout(r, 0))
  expect(computes).toBe(2)
})

test("a base-ref change recomputes all sessions sharing the common dir", () => {
  const h = harness()
  h.svc.sync([{ id: "a", workdir: "/wa" }, { id: "b", workdir: "/wb" }])
  h.scheduled.length = 0                          // ignore initial schedules
  const refsDir = join("/common", "refs", "heads")
  h.watchers.get(refsDir)!()                      // fire base-ref change
  const ids = h.scheduled.map((s) => /* recompute target captured via closure */ true)
  expect(h.scheduled.length).toBe(2)             // both a and b rescheduled
})

test("sync untracks a removed session and closes its unique watch", () => {
  const h = harness()
  h.svc.sync([{ id: "a", workdir: "/wa" }])
  expect(h.watchers.has("/git/a")).toBe(true)
  h.svc.sync([])                                  // a is gone
  expect(h.watchers.has("/git/a")).toBe(false)
  expect(h.svc.get("a")).toBeUndefined()
})

test("non-repo sessions (resolveGitDirs null) cache null and set no watches", () => {
  const h = harness({ resolveGitDirs: () => null })
  h.svc.sync([{ id: "a", workdir: "/wa" }])
  expect(h.watchers.size).toBe(0)
  expect(h.scheduled.length).toBe(0)
})

// Live end-to-end: real fs.watch + real computeLiteStatus on a real worktree.
test("live: committing in a worktree triggers a recompute", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-svc-")); execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "config", "user.email", "t@t.t"); g(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "f.txt"), "1\n"); g(dir, "add", "."); g(dir, "commit", "-m", "init")
  const wt = await createWorktree({ repoRoot: dir, baseBranch: "main", sessionName: "s" })
  const { watch } = await import("node:fs")
  const { resolve, isAbsolute } = await import("node:path")
  const changes: Array<GitLiteStatus | null> = []
  const svc = new GitStatusService({
    compute: (s) => computeLiteStatus(s),
    resolveGitDirs: (s) => {
      try {
        const gitDir = execFileSync("git", ["rev-parse", "--absolute-git-dir"], { cwd: s.workdir, encoding: "utf-8" }).trim()
        const raw = execFileSync("git", ["rev-parse", "--git-common-dir"], { cwd: s.workdir, encoding: "utf-8" }).trim()
        return { gitDir, commonDir: isAbsolute(raw) ? raw : resolve(s.workdir, raw) }
      } catch { return null }
    },
    watch: (d, onEvent) => { const w = watch(d, { persistent: false }, () => onEvent()); w.on("error", () => {}); return { close: () => w.close() } },
    onChange: (_id, git) => changes.push(git),
    schedule: (fn, ms) => setTimeout(fn, ms),
    cancel: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
    debounceMs: 50,
  })
  svc.sync([{ id: "s", workdir: wt.worktreeDir, repo_root: dir, base_branch: "main", session_branch: wt.sessionBranch }])
  await new Promise((r) => setTimeout(r, 300))               // let initial compute land
  writeFileSync(join(wt.worktreeDir, "a.txt"), "x"); g(wt.worktreeDir, "add", "."); g(wt.worktreeDir, "commit", "-m", "w")
  for (let i = 0; i < 40 && !changes.some((c) => c?.ahead === 1); i++) await new Promise((r) => setTimeout(r, 100))
  svc.sync([])                                                // close watches
  expect(changes.some((c) => c?.ahead === 1)).toBe(true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/worktree/git-status-service.test.ts`
Expected: FAIL — `Cannot find module './git-status-service'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/worktree/git-status-service.ts`:

```typescript
import { join } from "node:path"
import type { GitLiteStatus } from "./lite-status"

export interface ServiceSession {
  id: string
  workdir: string
  repo_root?: string | null
  base_branch?: string | null
  session_branch?: string | null
}

export interface WatchHandle { close(): void }

export interface GitStatusServiceDeps {
  compute(s: ServiceSession): Promise<GitLiteStatus | null>
  resolveGitDirs(s: ServiceSession): { gitDir: string; commonDir: string } | null
  watch(dir: string, onEvent: () => void): WatchHandle
  onChange(id: string, git: GitLiteStatus | null): void
  schedule(fn: () => void, ms: number): unknown
  cancel(handle: unknown): void
  debounceMs?: number
}

interface Tracked { s: ServiceSession; dirs: string[] }

/**
 * Holds per-session GitLiteStatus, recomputes it on demand / fs change / turn-end,
 * and broadcasts changes. Watches are refcounted by directory so many worktree
 * sessions of one repo share the common-dir + refs/heads watch (instance-cap safe).
 */
export class GitStatusService {
  private readonly cache = new Map<string, GitLiteStatus | null>()
  private readonly tracked = new Map<string, Tracked>()
  private readonly dirWatch = new Map<string, { handle: WatchHandle; refs: Set<string> }>()
  private readonly timers = new Map<string, unknown>()
  private readonly inflight = new Set<string>()
  private readonly rerun = new Set<string>()

  constructor(private readonly deps: GitStatusServiceDeps) {}

  get(id: string): GitLiteStatus | undefined {
    const v = this.cache.get(id)
    return v ?? undefined
  }

  /** Idempotent reconcile: track new repo sessions, untrack vanished ones. */
  sync(sessions: ServiceSession[]): void {
    const want = new Set(sessions.map((s) => s.id))
    for (const id of [...this.tracked.keys()]) if (!want.has(id)) this.untrack(id)
    for (const s of sessions) if (!this.tracked.has(s.id)) this.track(s)
  }

  scheduleRecompute(id: string): void {
    if (!this.tracked.has(id)) return
    const prev = this.timers.get(id)
    if (prev !== undefined) this.deps.cancel(prev)
    this.timers.set(id, this.deps.schedule(() => { this.timers.delete(id); void this.recompute(id) }, this.deps.debounceMs ?? 400))
  }

  private track(s: ServiceSession): void {
    const dirs = this.deps.resolveGitDirs(s)
    if (!dirs) { this.cache.set(s.id, null); this.tracked.set(s.id, { s, dirs: [] }); return }
    const watchDirs = [...new Set([dirs.gitDir, dirs.commonDir, join(dirs.commonDir, "refs", "heads")])]
    this.tracked.set(s.id, { s, dirs: watchDirs })
    for (const d of watchDirs) this.addDirRef(d, s.id)
    this.scheduleRecompute(s.id)
  }

  private untrack(id: string): void {
    const t = this.tracked.get(id)
    if (t) for (const d of t.dirs) this.removeDirRef(d, id)
    this.tracked.delete(id)
    const timer = this.timers.get(id)
    if (timer !== undefined) { this.deps.cancel(timer); this.timers.delete(id) }
    this.cache.delete(id)
    this.rerun.delete(id)
  }

  private addDirRef(dir: string, id: string): void {
    const w = this.dirWatch.get(dir)
    if (w) { w.refs.add(id); return }
    const handle = this.deps.watch(dir, () => this.onDirEvent(dir))
    this.dirWatch.set(dir, { handle, refs: new Set([id]) })
  }

  private removeDirRef(dir: string, id: string): void {
    const w = this.dirWatch.get(dir)
    if (!w) return
    w.refs.delete(id)
    if (w.refs.size === 0) { w.handle.close(); this.dirWatch.delete(dir) }
  }

  private onDirEvent(dir: string): void {
    const w = this.dirWatch.get(dir)
    if (!w) return
    for (const id of w.refs) this.scheduleRecompute(id)   // base-ref dir → fans out to all sharers
  }

  private async recompute(id: string): Promise<void> {
    const t = this.tracked.get(id)
    if (!t) return
    if (this.inflight.has(id)) { this.rerun.add(id); return }
    this.inflight.add(id)
    try {
      const git = await this.deps.compute(t.s)
      if (!this.tracked.has(id)) return        // untracked while computing
      this.cache.set(id, git)
      this.deps.onChange(id, git)
    } finally {
      this.inflight.delete(id)
      if (this.rerun.delete(id)) void this.recompute(id)
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/worktree/git-status-service.test.ts`
Expected: PASS (7 tests). The "live" test may take ~1–3s.

- [ ] **Step 5: Commit**

```bash
git add src/core/worktree/git-status-service.ts src/core/worktree/git-status-service.test.ts
git commit -m "feat(worktree): GitStatusService — cached, debounced, refcounted-watch git status"
```

---

## Task 3: Broker wiring (construct service, broadcast, snapshot, idle hook)

**Files:**
- Modify: `src/channels/web/index.ts:80-93` (SessionSnapshot)
- Modify: `src/main.ts` (construct + wire)

- [ ] **Step 1: Add the `git` field to `SessionSnapshot`**

In `src/channels/web/index.ts`, the interface currently ends:

```typescript
  session_branch?: string
  repo_root?: string
  finish_job?: import("../../core/worktree/finish-job").FinishJob
}
```

Change to:

```typescript
  session_branch?: string
  repo_root?: string
  git?: import("../../core/worktree/lite-status").GitLiteStatus
  finish_job?: import("../../core/worktree/finish-job").FinishJob
}
```

- [ ] **Step 2: Import the service + compute in `main.ts`**

Near the other `core/worktree` imports in `src/main.ts`, add:

```typescript
import { computeLiteStatus } from "./core/worktree/lite-status"
import { GitStatusService, type ServiceSession } from "./core/worktree/git-status-service"
```

Also ensure these node imports exist at the top of `src/main.ts` (add any missing ones to existing import lines):

```typescript
import { watch as fsWatch, existsSync } from "node:fs"
import { resolve as resolvePath, isAbsolute } from "node:path"
import { execFileSync } from "node:child_process"
```

- [ ] **Step 3: Construct the service**

In `src/main.ts`, immediately after the `AgentStateStore` is created (`const agentStateStore = new AgentStateStore()`, ~line 311), add:

```typescript
function resolveGitDirs(workdir: string): { gitDir: string; commonDir: string } | null {
  try {
    const gitDir = execFileSync("git", ["rev-parse", "--absolute-git-dir"], { cwd: workdir, encoding: "utf-8" }).trim()
    const raw = execFileSync("git", ["rev-parse", "--git-common-dir"], { cwd: workdir, encoding: "utf-8" }).trim()
    return { gitDir, commonDir: isAbsolute(raw) ? raw : resolvePath(workdir, raw) }
  } catch { return null }
}

const gitStatusService = new GitStatusService({
  compute: (s) => computeLiteStatus(s),
  resolveGitDirs: (s) => resolveGitDirs(s.workdir),
  watch: (dir, onEvent) => {
    try {
      const w = fsWatch(dir, { persistent: false }, () => onEvent())
      w.on("error", () => {})   // dir may vanish (worktree removed) — degrade silently
      return { close: () => { try { w.close() } catch {} } }
    } catch { return { close: () => {} } }
  },
  onChange: (id, git) => webChannel?.broadcastToAll({ type: "session_git", session: id, git }),
  schedule: (fn, ms) => setTimeout(fn, ms),
  cancel: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  debounceMs: 400,
})

function gitServiceSessions(): ServiceSession[] {
  return registry.listVisible().map((s) => ({
    id: s.id, workdir: s.workdir,
    repo_root: s.repo_root, base_branch: s.base_branch, session_branch: s.session_branch,
  }))
}
```

> NOTE: `webChannel` is referenced inside `onChange` but assigned later in `main.ts`. That is fine — `onChange` only runs on a recompute, well after startup. If your linter flags use-before-assign, declare `let webChannel` earlier (it already is) — no change needed.

- [ ] **Step 4: Recompute on turn-end (idle)**

In `src/main.ts`, the existing `agentStateStore.on("change", …)` block is at ~line 2813. Do NOT modify it — add a SECOND listener immediately after that block closes:

```typescript
agentStateStore.on("change", (sessionId: string, state) => {
  if (state.phase === "idle") gitStatusService.scheduleRecompute(sessionId)
})
```

- [ ] **Step 5: Sync the service inside `getSessionsSnapshot` and add the `git` field**

In `src/main.ts`, replace the current `getSessionsSnapshot` (lines ~973-989):

```typescript
getSessionsSnapshot: () =>
  registry.listVisible().map((s) => ({
    id: s.id,
    name: s.name,
    workdir: s.workdir,
    mute: !!s.mute,
    connected: !!s.connected,
    agent: s.agent,
    role: s.role,
    isDefault: s.is_default,
    model: s.model,
    reasoningLevel: s.reasoningLevel,
    status: s.status,
    session_branch: s.session_branch || undefined,
    repo_root: s.repo_root || undefined,
    finish_job: s.finish_job,
  })),
```

with:

```typescript
getSessionsSnapshot: () => {
  gitStatusService.sync(gitServiceSessions())
  return registry.listVisible().map((s) => ({
    id: s.id,
    name: s.name,
    workdir: s.workdir,
    mute: !!s.mute,
    connected: !!s.connected,
    agent: s.agent,
    role: s.role,
    isDefault: s.is_default,
    model: s.model,
    reasoningLevel: s.reasoningLevel,
    status: s.status,
    session_branch: s.session_branch || undefined,
    repo_root: s.repo_root || undefined,
    git: gitStatusService.get(s.id),
    finish_job: s.finish_job,
  }))
},
```

- [ ] **Step 6: Sync promptly after spawn**

In `src/main.ts`, at the END of `spawnSession`, the function currently finishes with:

```typescript
  if (wt && registry.get(r.session_id)) {
    registry.sessions.setWorktree(r.session_id, {
      repo_root: wt.repoRoot, base_branch: wt.baseBranch, session_branch: wt.sessionBranch,
    })
  }
  return r
}
```

Change to:

```typescript
  if (wt && registry.get(r.session_id)) {
    registry.sessions.setWorktree(r.session_id, {
      repo_root: wt.repoRoot, base_branch: wt.baseBranch, session_branch: wt.sessionBranch,
    })
  }
  gitStatusService.sync(gitServiceSessions())
  return r
}
```

- [ ] **Step 7: Verify typecheck + full backend suite stay green**

Run: `bun run typecheck`
Expected: no NEW errors in `src/core/worktree/*`, `src/main.ts`, `src/channels/web/index.ts`. (Pre-existing repo-wide errors unrelated to these files may exist — confirm none are in the touched files.)

Run: `bun test`
Expected: the new `lite-status` + `git-status-service` suites PASS and nothing previously green regresses.

- [ ] **Step 8: Commit**

```bash
git add src/main.ts src/channels/web/index.ts
git commit -m "feat(broker): wire GitStatusService — snapshot git field + session_git broadcast + idle recompute"
```

---

## Task 4: Web — store, dispatch, badge formatter, SessionRow badge

**Files:**
- Create: `src/web-app/src/stores/gitStatus.ts` + `.test.ts`
- Create: `src/web-app/src/lib/gitBadge.ts` + `.test.ts`
- Modify: `src/web-app/src/api/ws.ts`
- Modify: `src/web-app/src/components/SessionRow.vue`

- [ ] **Step 1: Write the failing store test**

Create `src/web-app/src/stores/gitStatus.test.ts`:

```typescript
import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useGitStatus } from "./gitStatus"

beforeEach(() => setActivePinia(createPinia()))

const sample = { mode: "base" as const, compareRef: "main", ahead: 2, behind: 1, dirty: 3, computedAt: 1 }

test("get is undefined for an unknown session", () => {
  expect(useGitStatus().get("s1")).toBeUndefined()
})

test("set then get returns the status", () => {
  const s = useGitStatus(); s.set("s1", sample)
  expect(s.get("s1")).toEqual(sample)
})

test("set null clears a session", () => {
  const s = useGitStatus(); s.set("s1", sample); s.set("s1", null)
  expect(s.get("s1")).toBeUndefined()
})

test("fromSnapshot ignores empty and stores present", () => {
  const s = useGitStatus(); s.fromSnapshot("s1", undefined); s.fromSnapshot("s2", sample)
  expect(s.get("s1")).toBeUndefined()
  expect(s.get("s2")).toEqual(sample)
})
```

- [ ] **Step 2: Run it (fails)**

Run: `bun test src/web-app/src/stores/gitStatus.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Create the store**

Create `src/web-app/src/stores/gitStatus.ts`:

```typescript
import { defineStore } from "pinia"
import { ref } from "vue"

export interface GitLiteStatus {
  mode: "base" | "remote"
  compareRef: string
  ahead: number
  behind: number
  dirty: number
  unpublished?: boolean
  computedAt: number
}

export const useGitStatus = defineStore("gitStatus", () => {
  const bySession = ref<Record<string, GitLiteStatus>>({})

  function set(id: string, git: GitLiteStatus | null | undefined) {
    if (!git) { clear(id); return }
    bySession.value = { ...bySession.value, [id]: git }
  }
  function fromSnapshot(id: string, git?: GitLiteStatus | null) { if (git) set(id, git) }
  function clear(id: string) {
    if (id in bySession.value) { const n = { ...bySession.value }; delete n[id]; bySession.value = n }
  }
  function get(id: string): GitLiteStatus | undefined { return bySession.value[id] }

  return { bySession, set, fromSnapshot, clear, get }
})
```

- [ ] **Step 4: Run it (passes)**

Run: `bun test src/web-app/src/stores/gitStatus.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the failing badge-formatter test**

Create `src/web-app/src/lib/gitBadge.test.ts`:

```typescript
import { test, expect } from "bun:test"
import { gitBadge } from "./gitBadge"

test("undefined → null (no badge)", () => { expect(gitBadge(undefined)).toBeNull() })

test("clean → muted in-sync", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 0, behind: 0, dirty: 0, computedAt: 0 })
  expect(b).toEqual({ text: "✓ in sync", title: "In sync with main", tone: "muted" })
})

test("ahead/behind/dirty → glyphs + active tone, base label", () => {
  const b = gitBadge({ mode: "base", compareRef: "main", ahead: 2, behind: 1, dirty: 3, computedAt: 0 })
  expect(b?.text).toBe("↑2 ↓1 ·3")
  expect(b?.tone).toBe("active")
  expect(b?.title).toBe("2 ahead / 1 behind main · 3 uncommitted")
})

test("remote mode uses origin label", () => {
  const b = gitBadge({ mode: "remote", compareRef: "origin/x", ahead: 1, behind: 0, dirty: 0, computedAt: 0 })
  expect(b?.title).toBe("1 ahead origin")
})

test("unpublished remote → muted unpublished", () => {
  const b = gitBadge({ mode: "remote", compareRef: "x", ahead: 0, behind: 0, dirty: 0, unpublished: true, computedAt: 0 })
  expect(b).toEqual({ text: "unpublished", title: "Not published", tone: "muted" })
})
```

- [ ] **Step 6: Run it (fails)**

Run: `bun test src/web-app/src/lib/gitBadge.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 7: Create the badge formatter**

Create `src/web-app/src/lib/gitBadge.ts`:

```typescript
import type { GitLiteStatus } from "@/stores/gitStatus"

export interface GitBadge { text: string; title: string; tone: "muted" | "active" }

export function gitBadge(git: GitLiteStatus | undefined): GitBadge | null {
  if (!git) return null
  const ref = git.mode === "base" ? (git.compareRef || "base") : "origin"
  if (git.unpublished) return { text: "unpublished", title: "Not published", tone: "muted" }

  const parts: string[] = []
  if (git.ahead) parts.push(`↑${git.ahead}`)
  if (git.behind) parts.push(`↓${git.behind}`)
  if (git.dirty) parts.push(`·${git.dirty}`)
  if (parts.length === 0) return { text: "✓ in sync", title: `In sync with ${ref}`, tone: "muted" }

  const ab: string[] = []
  if (git.ahead) ab.push(`${git.ahead} ahead`)
  if (git.behind) ab.push(`${git.behind} behind`)
  const titleBits: string[] = []
  if (ab.length) titleBits.push(`${ab.join(" / ")} ${ref}`)
  if (git.dirty) titleBits.push(`${git.dirty} uncommitted`)
  return { text: parts.join(" "), title: titleBits.join(" · "), tone: "active" }
}
```

- [ ] **Step 8: Run it (passes)**

Run: `bun test src/web-app/src/lib/gitBadge.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 9: Dispatch `session_git` + hydrate in `ws.ts`**

In `src/web-app/src/api/ws.ts`, add the store import next to the other store imports at the top of the module, and instantiate it alongside the existing module-level store consts (e.g. where `const finishJob = useFinishJob()` and `const sessions = useSessions()` are):

```typescript
import { useGitStatus } from "@/stores/gitStatus"
// ... with the other `const x = useX()` lines:
const gitStatus = useGitStatus()
```

In the `snapshot` branch of `dispatch`, the existing line is:

```typescript
    for (const s of (frame.sessions ?? [])) finishJob.fromSnapshot(s.id, s.finish_job)
```

Change to:

```typescript
    for (const s of (frame.sessions ?? [])) { finishJob.fromSnapshot(s.id, s.finish_job); gitStatus.fromSnapshot(s.id, s.git) }
```

The existing `session_added` line is:

```typescript
  } else if (frame.type === "session_added")    { sessions.add(frame.session); finishJob.fromSnapshot(frame.session.id, frame.session.finish_job) }
```

Change to:

```typescript
  } else if (frame.type === "session_added")    { sessions.add(frame.session); finishJob.fromSnapshot(frame.session.id, frame.session.finish_job); gitStatus.fromSnapshot(frame.session.id, frame.session.git) }
```

And add a new dispatch case next to the `finish_job` case:

```typescript
  else if   (frame.type === "session_git")       gitStatus.set(frame.session, frame.git)
```

Also drop the cache on session removal — the existing `session_removed` branch is:

```typescript
  else if   (frame.type === "session_removed")  {
    sessions.remove(frame.id)
    commands.remove(frame.id)
    useSessionCache().drop(frame.id)
    navigateAwayFromKilledSession(frame.id)
  }
```

Add one line inside it:

```typescript
  else if   (frame.type === "session_removed")  {
    sessions.remove(frame.id)
    commands.remove(frame.id)
    gitStatus.clear(frame.id)
    useSessionCache().drop(frame.id)
    navigateAwayFromKilledSession(frame.id)
  }
```

- [ ] **Step 10: Render the badge in `SessionRow.vue`**

In `src/web-app/src/components/SessionRow.vue` `<script setup>`, add imports + a computed (after the existing `working` computed):

```typescript
import { useGitStatus } from "@/stores/gitStatus"
import { gitBadge } from "@/lib/gitBadge"
// ...
const gitStatus = useGitStatus()
const badge = computed(() => gitBadge(gitStatus.get(props.id)))
```

In the template, the secondary info row currently is:

```vue
        <div class="flex items-center justify-between gap-2 mt-0.5">
          <div
            class="text-[11px] truncate"
            :class="lastText ? 'text-muted-foreground/65' : 'text-muted-foreground/50 italic'"
          >
            {{ lastText || "no messages yet" }}
          </div>
          <span
            v-if="props.unread"
            class="h-5 w-1 rounded-full bg-primary/70 shrink-0"
            aria-label="unread"
          />
        </div>
```

Insert the badge span just before the unread `<span>`:

```vue
        <div class="flex items-center justify-between gap-2 mt-0.5">
          <div
            class="text-[11px] truncate"
            :class="lastText ? 'text-muted-foreground/65' : 'text-muted-foreground/50 italic'"
          >
            {{ lastText || "no messages yet" }}
          </div>
          <span
            v-if="badge"
            :title="badge.title"
            class="shrink-0 font-mono text-[10px] tabular-nums"
            :class="badge.tone === 'muted' ? 'text-muted-foreground/45' : 'text-muted-foreground/80'"
          >{{ badge.text }}</span>
          <span
            v-if="props.unread"
            class="h-5 w-1 rounded-full bg-primary/70 shrink-0"
            aria-label="unread"
          />
        </div>
```

- [ ] **Step 11: Web typecheck**

Run: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: no errors. (If `frame.git`/`frame.session.git` flag as unknown, that's expected — `dispatch(frame: any)` and `frame.session` are untyped `any`, so access is allowed.)

- [ ] **Step 12: Run the new web tests**

Run (from repo root): `bun test src/web-app/src/stores/gitStatus.test.ts src/web-app/src/lib/gitBadge.test.ts`
Expected: PASS (9 tests).

- [ ] **Step 13: Commit**

```bash
git add src/web-app/src/stores/gitStatus.ts src/web-app/src/stores/gitStatus.test.ts src/web-app/src/lib/gitBadge.ts src/web-app/src/lib/gitBadge.test.ts src/web-app/src/api/ws.ts src/web-app/src/components/SessionRow.vue
git commit -m "feat(web): session-list git badge — store, session_git dispatch, glyph badge"
```

---

## Task 5: Web — mode-aware chat-header pill

**Files:**
- Modify: `src/web-app/src/components/BranchSyncStatus.vue`

The header pill currently shows ahead/behind vs `origin`. For a worktree session (base mode) the headline must show ahead/behind vs base instead; the publish/push/pull dropdown (remote ops) stays unchanged.

- [ ] **Step 1: Read base-mode status in the component**

In `src/web-app/src/components/BranchSyncStatus.vue` `<script setup>`, after the existing `const git = useGitRemote()` line, add:

```typescript
import { useGitStatus } from "@/stores/gitStatus"
const liteGit = useGitStatus()
const base = computed(() => {
  const g = liteGit.get(props.sessionId)
  return g && g.mode === "base" ? g : null
})
```

- [ ] **Step 2: Make the headline label + tooltip base-aware**

The existing computed (lines ~29-35) is:

```typescript
const stateLabel = computed(() => {
  if (!published.value) return "not published"
  if (ahead.value && behind.value) return `↑${ahead.value} ↓${behind.value}`
  if (ahead.value) return `↑${ahead.value}`
  if (behind.value) return `↓${behind.value}`
  return "✓"
})
```

Replace with:

```typescript
const stateLabel = computed(() => {
  if (base.value) {
    const a = base.value.ahead, b = base.value.behind
    if (a && b) return `↑${a} ↓${b}`
    if (a) return `↑${a}`
    if (b) return `↓${b}`
    return "✓"
  }
  if (!published.value) return "not published"
  if (ahead.value && behind.value) return `↑${ahead.value} ↓${behind.value}`
  if (ahead.value) return `↑${ahead.value}`
  if (behind.value) return `↓${behind.value}`
  return "✓"
})

const stateTitle = computed(() =>
  base.value ? `vs ${base.value.compareRef}` : (published.value ? "vs origin" : "not published to origin"))
```

The existing `showState` (line ~27) is:

```typescript
const showState = computed(() => !!status.value?.hasRemote && !!status.value?.branch)
```

Replace with (so the headline shows for base-mode sessions even before remote status loads):

```typescript
const showState = computed(() => !!base.value || (!!status.value?.hasRemote && !!status.value?.branch))
```

- [ ] **Step 3: Apply the tooltip in the template**

The sync-state trigger button (lines ~131-135) is:

```vue
        <button type="button" :class="segBtn" class="shrink-0" aria-label="Branch sync">
          <span class="opacity-80">· {{ stateLabel }}</span>
          <Loader2Icon v-if="busy" class="size-3 shrink-0 animate-spin" />
        </button>
```

Change to add the title:

```vue
        <button type="button" :class="segBtn" class="shrink-0" aria-label="Branch sync" :title="stateTitle">
          <span class="opacity-80">· {{ stateLabel }}</span>
          <Loader2Icon v-if="busy" class="size-3 shrink-0 animate-spin" />
        </button>
```

- [ ] **Step 4: Web typecheck**

Run: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/components/BranchSyncStatus.vue
git commit -m "feat(web): mode-aware header pill — worktree sessions show ahead/behind vs base"
```

---

## Task 6: Native wire-compat (Kotlin DTO + frame + contract)

Native rendering is deferred, but the shared Kotlin DTOs MUST gain the field/frame so (a) snapshot sessions carrying `git` still parse and (b) the exhaustive `ServerFrame` contract stays compiling.

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`
- Modify: `apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt`
- Create: `apps/shared/src/jvmTest/resources/frames/session_git.json`
- Modify: `apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt`

- [ ] **Step 1: Add the DTO + field + frame in `Frames.kt`**

Add the data class (near `FinishJobDto`):

```kotlin
@Serializable
data class GitLiteStatusDto(
    val mode: String = "base",        // base | remote
    val compareRef: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val dirty: Int = 0,
    val unpublished: Boolean? = null,
    val computedAt: Double = 0.0,     // epoch millis
)
```

Add the field to `SessionInfo` (after `session_branch`):

```kotlin
    val session_branch: String? = null,
    /** At-a-glance worktree-vs-base (or branch-vs-remote) divergence; null when not a git repo. */
    val git: GitLiteStatusDto? = null,
    /** Last/in-flight finish job for this session (mirrors the broker session record). */
    val finish_job: FinishJobDto? = null,
```

Add the frame to the `ServerFrame` sealed interface (near `FinishJobFrame`):

```kotlin
    // Per-session git status delta: broker broadcasts `{type:"session_git",session,git}`
    // on every recompute — src/main.ts: gitStatusService onChange.
    @Serializable @SerialName("session_git")
    data class SessionGit(val session: String = "", val git: GitLiteStatusDto? = null) : ServerFrame
```

- [ ] **Step 2: Add the fixture**

Create `apps/shared/src/jvmTest/resources/frames/session_git.json`:

```json
{"type":"session_git","session":"s1","git":{"mode":"base","compareRef":"main","ahead":2,"behind":1,"dirty":3,"computedAt":1}}
```

- [ ] **Step 3: Update the exhaustive contract test**

In `apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt`, add `"session_git"` to the `names` list:

```kotlin
        val names = listOf("snapshot", "session_added", "session_removed", "agent_state", "agent_error", "message_append", "activity_append", "commands_changed", "finish_job", "session_git")
```

And add the branch to the exhaustive `when` (alongside the others):

```kotlin
                is ServerFrame.SessionGit -> {}
```

- [ ] **Step 4: Add parse tests**

In `apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt`, add:

```kotlin
    @Test fun parses_session_git_frame() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_git","session":"s1","git":{"mode":"base","compareRef":"main","ahead":2,"behind":1,"dirty":3,"computedAt":1}}""")
        assertTrue(f is ServerFrame.SessionGit)
        val frame = f as ServerFrame.SessionGit
        assertEquals("s1", frame.session)
        assertEquals("base", frame.git?.mode)
        assertEquals(2, frame.git?.ahead)
        assertEquals(3, frame.git?.dirty)
    }

    @Test fun snapshot_session_carries_git() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","sessions":[{"id":"s1","name":"n","workdir":"/w","agent":"claude",
               |"git":{"mode":"remote","compareRef":"origin/x","ahead":0,"behind":0,"dirty":0,"unpublished":true,"computedAt":1}}]}""".trimMargin())
        assertTrue(f is ServerFrame.Snapshot)
        val s = (f as ServerFrame.Snapshot).sessions[0]
        assertEquals("remote", s.git?.mode)
        assertEquals(true, s.git?.unpublished)
    }
```

- [ ] **Step 5: Build/test the shared module (best effort)**

Run: `./gradlew :shared:jvmTest --tests "dev.supermux.proto.*"`
Expected: PASS (contract stays exhaustive, new parse tests green).

If the Kotlin/Gradle toolchain is not available on this host, the changes still keep the contract compiling by construction; record that the gradle run was skipped and rely on code review of Steps 1-4.

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt apps/shared/src/jvmTest/resources/frames/session_git.json apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt
git commit -m "feat(shared): session_git frame + SessionInfo.git wire-compat (native render deferred)"
```

---

## Task 7: Full verification + wrap-up

- [ ] **Step 1: Backend suite + typecheck**

Run: `bun test`
Expected: all green; new `lite-status` (5) + `git-status-service` (7) suites pass; no regressions.

Run: `bun run typecheck`
Expected: no NEW errors in any touched file.

- [ ] **Step 2: Web typecheck + new web tests**

Run: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: no errors.

Run (repo root): `bun test src/web-app/src/stores/gitStatus.test.ts src/web-app/src/lib/gitBadge.test.ts`
Expected: PASS (9).

- [ ] **Step 3: Manual smoke (optional, needs a broker restart — ask before doing)**

Per the spec, live behavior is the point. If the user authorizes a restart, use the `mux:preview-broker` skill to run the worktree broker against the live PWA, then: open the session list → confirm worktree sessions show `↑/↓/·` glyphs and clean ones show muted "✓ in sync"; make the agent commit → badge updates within ~1s; merge another session into the shared base → dependents tick to `↓1`. Do NOT restart the production broker without explicit permission.

- [ ] **Step 4: Final status**

Confirm all task commits are present (`git log --oneline`), report the test/typecheck output verbatim, and summarize what shipped vs. what's deferred (native rendering; tracked-tree watcher upgrade path).

---

## Self-Review (completed during planning)

- **Spec coverage:** adaptive base/remote modes (Task 1) ✓; tiered 2-call compute (Task 1) ✓; async + single-flight + debounce (Task 2) ✓; `.git`-metadata refcounted watcher + base-moved fan-out (Task 2) ✓; turn-end recompute (Task 3 idle hook) ✓; on-open/lazy via snapshot `sync()` (Task 3) ✓; in-memory cache, no DB (Task 2) ✓; WS snapshot field + `session_git` frame (Task 3) ✓; session-list glyph badge + muted in-sync (Task 4) ✓; mode-aware header pill (Task 5) ✓; native wire-compat, render deferred (Task 6) ✓; out-of-scope items (conflict preflight, diff stats, tracked-tree watcher) correctly excluded.
- **Placeholder scan:** every code step has complete code; commands have expected output. No TBD/TODO.
- **Type consistency:** `GitLiteStatus` shape identical across `lite-status.ts`, `gitStatus.ts` (web), `GitLiteStatusDto` (Kotlin); `ServiceSession`, `GitStatusServiceDeps`, `gitBadge`/`GitBadge` names match their call sites; `session_git` frame shape `{session, git}` consistent broker↔web↔Kotlin.
