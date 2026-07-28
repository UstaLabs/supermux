// src/core/git/repo-info.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { getRepoInfo } from "./repo-info"

function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-repoinfo-"))
  execFileSync("git", ["init", "-b", "main", dir])
  execFileSync("git", ["-C", dir, "config", "user.email", "t@t.t"])
  execFileSync("git", ["-C", dir, "config", "user.name", "t"])
  writeFileSync(join(dir, "f.txt"), "hi")
  execFileSync("git", ["-C", dir, "add", "."])
  execFileSync("git", ["-C", dir, "commit", "-m", "init"])
  return dir
}

test("getRepoInfo: non-git path is not eligible", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-plain-"))
  expect(getRepoInfo(dir)).toMatchObject({ isGitRepo: false, eligible: false })
})

test("getRepoInfo: a repo root is eligible and lists its branch", () => {
  const dir = tmpRepo()
  const info = getRepoInfo(dir)
  expect(info.isGitRepo).toBe(true)
  expect(info.eligible).toBe(true)
  expect(info.currentBranch).toBe("main")
  expect(info.branches?.local).toContain("main")
})

test("getRepoInfo: fetch:true is best-effort — a no-remote repo still lists branches", () => {
  const dir = tmpRepo() // no remote configured → git fetch fails, but must be swallowed
  const info = getRepoInfo(dir, { fetch: true })
  expect(info.isGitRepo).toBe(true)
  expect(info.branches?.local).toContain("main")
})

test("getRepoInfo: a parent dir containing a sub-repo is not a repo root", () => {
  const parent = mkdtempSync(join(tmpdir(), "mux-parent-"))
  mkdirSync(join(parent, "sub"))
  execFileSync("git", ["init", "-b", "main", join(parent, "sub")])
  const info = getRepoInfo(parent)
  expect(info.eligible).toBe(false)
})

test("getRepoInfo: remote list drops origin/HEAD symref (bare origin and …/HEAD)", () => {
  const dir = tmpRepo()
  // Simulate a remote-tracking layout with the origin/HEAD symref git fetch creates.
  execFileSync("git", ["-C", dir, "update-ref", "refs/remotes/origin/main", "HEAD"])
  execFileSync("git", ["-C", dir, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main"])
  const info = getRepoInfo(dir)
  expect(info.branches?.remote).toContain("origin/main")
  expect(info.branches?.remote).not.toContain("origin")
  expect(info.branches?.remote?.some((n) => n.endsWith("/HEAD"))).toBe(false)
})
