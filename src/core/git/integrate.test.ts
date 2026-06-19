// src/core/git/integrate.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync, chmodSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree } from "../worktree/manager"
import { findBranchCheckout, isAncestor, syncBaseIntoBranch, integrateFastForward, changedFiles, aheadBehind, diffStats, mergeTreePreflight } from "./integrate"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }
function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-int-"))
  execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "config", "user.email", "t@t.t"); g(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "f.txt"), "1\n"); g(dir, "add", "."); g(dir, "commit", "-m", "init")
  return dir
}

test("integrate fast-forwards base when checked out & clean (no overlap)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "new.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "work")
  expect(isAncestor(repo, "main", h.sessionBranch)).toBe(true)
  const r = integrateFastForward(repo, "main", h.sessionBranch)
  expect(r.status).toBe("integrated")
  expect(g(repo, "rev-parse", "main")).toBe(g(repo, "rev-parse", h.sessionBranch))
})

test("integrate refuses on dirty overlap in the base checkout", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "f.txt"), "2\n"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "edit f")
  writeFileSync(join(repo, "f.txt"), "local-wip\n")          // dirty the SAME file in base checkout
  const r = integrateFastForward(repo, "main", h.sessionBranch)
  expect(r.status).toBe("dirty_overlap")
  if (r.status === "dirty_overlap") expect(r.files).toContain("f.txt")
})

test("sync reports conflict files and leaves the merge in progress", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "f.txt"), "branch\n"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "b")
  writeFileSync(join(repo, "f.txt"), "main2\n"); g(repo, "add", "."); g(repo, "commit", "-m", "m")  // diverge on f.txt
  const r = syncBaseIntoBranch(h.worktreeDir, "main")
  expect(r.status).toBe("conflict")
  if (r.status === "conflict") expect(r.files).toContain("f.txt")
})

test("sync completes despite a rejecting pre-commit hook (internal merge uses --no-verify)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  // branch and base diverge on DIFFERENT files → clean auto-merge (no conflict)
  writeFileSync(join(h.worktreeDir, "b.txt"), "branch\n"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "b")
  writeFileSync(join(repo, "m.txt"), "main\n"); g(repo, "add", "."); g(repo, "commit", "-m", "m")
  // a pre-commit hook (shared across worktrees) that ALWAYS rejects
  const hook = join(repo, ".git", "hooks", "pre-commit")
  writeFileSync(hook, "#!/bin/sh\nexit 1\n"); chmodSync(hook, 0o755)
  expect(syncBaseIntoBranch(h.worktreeDir, "main").status).toBe("clean")
})

test("aheadBehind counts commits each side of base", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w1")
  writeFileSync(join(h.worktreeDir, "b.txt"), "y"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w2")
  const ab = aheadBehind(h.worktreeDir, "main")
  expect(ab).toEqual({ ahead: 2, behind: 0 })
})

test("diffStats reports files and line counts base..branch", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "l1\nl2\n"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  const s = diffStats(repo, "main", h.sessionBranch)
  expect(s.filesChanged).toBe(1)
  expect(s.insertions).toBeGreaterThanOrEqual(2)
})

test("mergeTreePreflight is clean for non-overlapping work", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "new.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  expect(mergeTreePreflight(repo, "main", h.sessionBranch)).toBe("clean")
})

test("mergeTreePreflight detects a conflict without mutating the repo", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "f.txt"), "branch\n"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "branch edit")
  g(repo, "checkout", "main"); writeFileSync(join(repo, "f.txt"), "base\n"); g(repo, "add", "."); g(repo, "commit", "-m", "base edit")
  expect(mergeTreePreflight(repo, "main", h.sessionBranch)).toBe("will_conflict")
  expect(() => g(repo, "rev-parse", "-q", "--verify", "MERGE_HEAD")).toThrow()  // no merge state left behind
})
