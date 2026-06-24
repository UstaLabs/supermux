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
