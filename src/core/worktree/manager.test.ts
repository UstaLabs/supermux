// src/core/worktree/manager.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, existsSync, writeFileSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree, removeWorktree, deriveSessionBranch } from "./manager"

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
