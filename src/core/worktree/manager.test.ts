// src/core/worktree/manager.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, existsSync, writeFileSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree, removeWorktree, deriveSessionBranch, ensureWorktreeAt, existingBranchNames } from "./manager"

function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-wt-"))
  execFileSync("git", ["init", "-b", "main", dir])
  execFileSync("git", ["-C", dir, "config", "user.email", "t@t.t"])
  execFileSync("git", ["-C", dir, "config", "user.name", "t"])
  writeFileSync(join(dir, "f.txt"), "hi")
  execFileSync("git", ["-C", dir, "add", "."]); execFileSync("git", ["-C", dir, "commit", "-m", "init"])
  return dir
}

test("deriveSessionBranch slugs and prefixes, avoiding collisions", () => {
  expect(deriveSessionBranch("My Feature!", new Set())).toBe("mux/my-feature")
  expect(deriveSessionBranch("My Feature!", new Set(["mux/my-feature"]))).toBe("mux/my-feature-2")
})

test("createWorktree makes a worktree on a new branch off the base, then removeWorktree cleans up", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "feat x" })
  expect(existsSync(h.worktreeDir)).toBe(true)
  expect(h.sessionBranch).toBe("mux/feat-x")
  // worktree HEAD is on the new branch
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe("mux/feat-x")

  await removeWorktree(repo, h.worktreeDir, h.sessionBranch)
  expect(existsSync(h.worktreeDir)).toBe(false)
  const branches = execFileSync("git", ["-C", repo, "for-each-ref", "--format=%(refname:short)", "refs/heads"], { encoding: "utf-8" })
  expect(branches).not.toContain("mux/feat-x")
})

test("createWorktree copies .worktreeinclude files", async () => {
  const repo = tmpRepo()
  writeFileSync(join(repo, ".env"), "SECRET=1")
  writeFileSync(join(repo, ".worktreeinclude"), ".env\n")
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  expect(readFileSync(join(h.worktreeDir, ".env"), "utf-8")).toBe("SECRET=1")
})

test("ensureWorktreeAt recreates a worktree at the same path when the dir AND its branch are gone (merged-session resume)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "tabbar" })
  expect(existsSync(h.worktreeDir)).toBe(true)
  // Simulate finish→merge cleanup: worktree dir removed AND branch deleted.
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch)
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect(existingBranchNames(repo).has(h.sessionBranch)).toBe(false)

  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })

  // Restored at the SAME path (so claude --resume's cwd-hash still matches its transcript).
  expect(existsSync(h.worktreeDir)).toBe(true)
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe(h.sessionBranch)
  expect(existsSync(join(h.worktreeDir, "f.txt"))).toBe(true)
})

test("ensureWorktreeAt reuses the existing branch when the dir is gone but the branch survives", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "keepbranch" })
  // commit on the branch so we can prove the branch's own history is restored (not base)
  writeFileSync(join(h.worktreeDir, "only-on-branch.txt"), "x")
  execFileSync("git", ["-C", h.worktreeDir, "add", "."]); execFileSync("git", ["-C", h.worktreeDir, "commit", "-m", "wip"])
  // Remove the worktree dir but KEEP the branch.
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch, { keepBranch: true })
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect(existingBranchNames(repo).has(h.sessionBranch)).toBe(true)

  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })

  expect(existsSync(h.worktreeDir)).toBe(true)
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe(h.sessionBranch)
  // the branch's OWN commit is restored, proving we checked out the surviving branch (not base)
  expect(existsSync(join(h.worktreeDir, "only-on-branch.txt"))).toBe(true)
})

test("ensureWorktreeAt is a no-op when the worktree dir already exists (never clobber live work)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "healthy" })
  writeFileSync(join(h.worktreeDir, "live-edit.txt"), "uncommitted work")
  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })
  expect(readFileSync(join(h.worktreeDir, "live-edit.txt"), "utf-8")).toBe("uncommitted work")
})

test("ensureWorktreeAt prunes a stale registration when the dir was removed without git", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "stale" })
  // Manual/partial deletion: remove the dir but leave git's worktree registration + branch.
  execFileSync("rm", ["-rf", h.worktreeDir])
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect(existingBranchNames(repo).has(h.sessionBranch)).toBe(true)

  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })

  expect(existsSync(h.worktreeDir)).toBe(true)
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe(h.sessionBranch)
})
