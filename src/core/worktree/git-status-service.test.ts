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
