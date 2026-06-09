// src/core/worktree/gc.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createWorktree } from "./manager"
import { isWorktreeReclaimable } from "./gc"

function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-gc-"))
  execFileSync("git", ["init", "-b", "main", dir])
  execFileSync("git", ["-C", dir, "config", "user.email", "t@t.t"])
  execFileSync("git", ["-C", dir, "config", "user.name", "t"])
  writeFileSync(join(dir, "f.txt"), "hi")
  execFileSync("git", ["-C", dir, "add", "."]); execFileSync("git", ["-C", dir, "commit", "-m", "init"])
  return dir
}

test("a fresh clean worktree with no commits is reclaimable", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  expect(isWorktreeReclaimable(h.worktreeDir, h.sessionBranch, "main")).toBe(true)
})

test("a worktree with uncommitted changes is NOT reclaimable", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "new.txt"), "wip")
  expect(isWorktreeReclaimable(h.worktreeDir, h.sessionBranch, "main")).toBe(false)
})

test("a worktree with commits not in base is NOT reclaimable", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x")
  execFileSync("git", ["-C", h.worktreeDir, "add", "."])
  execFileSync("git", ["-C", h.worktreeDir, "commit", "-m", "work"])
  expect(isWorktreeReclaimable(h.worktreeDir, h.sessionBranch, "main")).toBe(false)
})

test("committed work with an unknown/detached base ('HEAD' or empty) is NOT reclaimable", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x")
  execFileSync("git", ["-C", h.worktreeDir, "add", "."])
  execFileSync("git", ["-C", h.worktreeDir, "commit", "-m", "work"])
  // `HEAD..branch` inside the worktree would count 0 and hide the commit — must refuse.
  expect(isWorktreeReclaimable(h.worktreeDir, h.sessionBranch, "HEAD")).toBe(false)
  expect(isWorktreeReclaimable(h.worktreeDir, h.sessionBranch, "")).toBe(false)
})
