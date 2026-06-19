// src/core/worktree/finish.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { existsSync, mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree } from "./manager"
import { finishWorktree, openPrForSession, discardSession } from "./finish"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }
function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-fin-"))
  execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "config", "user.email", "t@t.t"); g(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "f.txt"), "1\n"); g(dir, "add", "."); g(dir, "commit", "-m", "init")
  return dir
}
function sess(repo: string, h: { worktreeDir: string; sessionBranch: string }) {
  return { repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }
}

test("clean commits, no tests → integrates into base (skipVerify)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "n.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "work")
  const r = await finishWorktree(sess(repo, h), { skipVerify: true })
  expect(r.status).toBe("integrated")
  expect(g(repo, "rev-parse", "main")).toBe(g(repo, "rev-parse", h.sessionBranch))
})

test("red tests block integration", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  execFileSync("mkdir", ["-p", join(h.worktreeDir, ".mux")])
  writeFileSync(join(h.worktreeDir, ".mux", "verify.sh"), "echo FAILING; exit 1")
  writeFileSync(join(h.worktreeDir, "n.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "work")
  const r = await finishWorktree(sess(repo, h))
  expect(r.status).toBe("tests_failed")
  if (r.status === "tests_failed") expect(r.output).toContain("FAILING")
  expect(g(repo, "rev-parse", "main")).not.toBe(g(repo, "rev-parse", h.sessionBranch)) // base untouched
})

test("nothing to integrate when branch == base", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  const r = await finishWorktree(sess(repo, h), { skipVerify: true })
  expect(r.status).toBe("nothing_to_do")
})

test("unknown base is not finishable", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  const r = await finishWorktree({ ...sess(repo, h), baseBranch: "HEAD" }, { skipVerify: true })
  expect(r.status).toBe("error")
})

test("no .mux/verify.sh with commits to integrate → no_verify (not silently skipped)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "n.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "work")
  const r = await finishWorktree(sess(repo, h)) // no skipVerify, no .mux/verify.sh
  expect(r.status).toBe("no_verify")
  expect(g(repo, "rev-parse", "main")).not.toBe(g(repo, "rev-parse", h.sessionBranch)) // base untouched
})

test("uncommitted worktree changes → uncommitted status (not nothing_to_do), base untouched", async () => {
  const repo = tmpRepo()
  const before = g(repo, "rev-parse", "main")
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "f.txt"), "dirty\n") // modify tracked file, do NOT commit
  writeFileSync(join(h.worktreeDir, "u.txt"), "new\n")   // and an untracked file
  const r = await finishWorktree(sess(repo, h), { skipVerify: true })
  expect(r.status).toBe("uncommitted")
  if (r.status === "uncommitted") { expect(r.files).toContain("f.txt"); expect(r.files).toContain("u.txt") }
  expect(g(repo, "rev-parse", "main")).toBe(before) // base untouched
})

test("re-entrant finish after a resolved sync conflict integrates (dirty-check doesn't hijack mid-merge)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  // Divergent edits to the same file → the sync merge conflicts.
  writeFileSync(join(h.worktreeDir, "f.txt"), "branch\n"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "b")
  writeFileSync(join(repo, "f.txt"), "main\n"); g(repo, "add", "."); g(repo, "commit", "-m", "m")
  const r1 = await finishWorktree(sess(repo, h), { skipVerify: true })
  expect(r1.status).toBe("sync_conflict")
  // Agent resolves + stages, but the merge is still in progress (NOT committed).
  writeFileSync(join(h.worktreeDir, "f.txt"), "resolved\n"); g(h.worktreeDir, "add", "f.txt")
  const r2 = await finishWorktree(sess(repo, h), { skipVerify: true })
  expect(r2.status).toBe("integrated") // must complete the merge, NOT report "uncommitted"
  expect(g(repo, "rev-parse", "main")).toBe(g(repo, "rev-parse", h.sessionBranch))
})

test("commitFirst commits the worktree (incl. untracked), then integrates", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "f.txt"), "dirty\n")
  writeFileSync(join(h.worktreeDir, "u.txt"), "new\n")
  const r = await finishWorktree(sess(repo, h), { skipVerify: true, commitFirst: true, commitMessage: "my work" })
  expect(r.status).toBe("integrated")
  expect(g(repo, "rev-parse", "main")).toBe(g(repo, "rev-parse", h.sessionBranch)) // base advanced to branch
  expect(g(repo, "log", "-1", "--pretty=%s", "main")).toBe("my work")                // our message
  expect(g(repo, "show", "main:u.txt")).toBe("new")                                  // untracked got integrated
})

test("merge then atomic cleanup removes worktree and branch", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  const r = await finishWorktree({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }, { skipVerify: true, cleanup: true })
  expect(r.status).toBe("integrated")
  if (r.status === "integrated") expect(r.cleanedUp).toBe(true)
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect(() => g(repo, "rev-parse", "--verify", h.sessionBranch)).toThrow()
})

test("merge with deleteBranch:false keeps the branch but removes the worktree", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  const r = await finishWorktree({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }, { skipVerify: true, cleanup: true, deleteBranch: false })
  expect(r.status).toBe("integrated")
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect(g(repo, "rev-parse", "--verify", h.sessionBranch)).toBeTruthy()  // branch still exists
})

test("cleanup is skipped when MUX_DISABLE_WORKTREE_CLEANUP is set", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  process.env.MUX_DISABLE_WORKTREE_CLEANUP = "1"
  try {
    const r = await finishWorktree({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }, { skipVerify: true, cleanup: true })
    expect(r.status).toBe("integrated")
    if (r.status === "integrated") expect(r.cleanedUp).toBe(false)
    expect(existsSync(h.worktreeDir)).toBe(true)
  } finally { delete process.env.MUX_DISABLE_WORKTREE_CLEANUP }
})

test("discardSession force-removes worktree + branch without merging", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x")  // dirty, uncommitted
  const baseBefore = g(repo, "rev-parse", "main")
  const r = await discardSession({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })
  expect(r.status).toBe("discarded")
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect(g(repo, "rev-parse", "main")).toBe(baseBefore)  // base untouched
  expect(() => g(repo, "rev-parse", "--verify", h.sessionBranch)).toThrow()
})
