// src/core/git/branches.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync, realpathSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { listBranches, repoToplevel } from "./branches"

function g(cwd: string, ...a: string[]) {
  return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim()
}

/** Working repo on `main` with a second local branch `dev` and an `origin`
 *  (local bare) carrying `main` + `remote-only`, with origin/HEAD set. */
function repo(): { work: string; bare: string } {
  const bare = mkdtempSync(join(tmpdir(), "mux-br-bare-"))
  execFileSync("git", ["init", "--bare", "-b", "main", bare])
  const work = mkdtempSync(join(tmpdir(), "mux-br-work-"))
  execFileSync("git", ["init", "-b", "main", work])
  g(work, "config", "user.email", "t@t.t"); g(work, "config", "user.name", "t")
  writeFileSync(join(work, "f.txt"), "1\n"); g(work, "add", "."); g(work, "commit", "-m", "init")
  g(work, "branch", "dev")
  g(work, "remote", "add", "origin", bare)
  g(work, "push", "origin", "main")
  g(work, "push", "origin", "main:remote-only")
  g(work, "fetch", "origin")
  g(work, "remote", "set-head", "origin", "main") // creates the origin/HEAD symref
  return { work, bare }
}

test("repoToplevel: repo root for any path inside, null outside", () => {
  const { work } = repo()
  const sub = join(work, "sub"); mkdirSync(sub)
  expect(repoToplevel(work)).toBe(realpathSync(work))
  expect(repoToplevel(sub)).toBe(realpathSync(work))
  expect(repoToplevel(mkdtempSync(join(tmpdir(), "mux-br-norepo-")))).toBeNull()
})

test("listBranches: locals with occupancy, remotes without origin/HEAD", () => {
  const { work } = repo()
  const l = listBranches(work)
  expect(l.repoRoot).toBe(realpathSync(work))
  expect(l.current).toBe("main")
  expect(l.detachedSha).toBeNull()
  const names = l.local.map((b) => b.name).sort()
  expect(names).toEqual(["dev", "main"])
  expect(l.local.find((b) => b.name === "main")!.checkedOutAt).toBe(realpathSync(work))
  expect(l.local.find((b) => b.name === "dev")!.checkedOutAt).toBeNull()
  expect(l.remote).toContain("origin/main")
  expect(l.remote).toContain("origin/remote-only")
  expect(l.remote).not.toContain("origin/HEAD")
  expect(l.remote).not.toContain("origin")
})

test("listBranches: branch checked out in another worktree gets its path", () => {
  const { work } = repo()
  const wt = join(mkdtempSync(join(tmpdir(), "mux-br-wt-")), "w")
  g(work, "worktree", "add", wt, "dev")
  const l = listBranches(work)
  expect(l.local.find((b) => b.name === "dev")!.checkedOutAt).toBe(realpathSync(wt))
})

test("listBranches: detached HEAD → current null, short sha set", () => {
  const { work } = repo()
  g(work, "checkout", "--detach")
  const l = listBranches(work)
  expect(l.current).toBeNull()
  expect(l.detachedSha).toBe(g(work, "rev-parse", "--short", "HEAD"))
})

test("listBranches: unborn HEAD → current set, lists empty", () => {
  const work = mkdtempSync(join(tmpdir(), "mux-br-unborn-"))
  execFileSync("git", ["init", "-b", "main", work])
  const l = listBranches(work)
  expect(l.current).toBe("main")
  expect(l.detachedSha).toBeNull()
  expect(l.local).toEqual([])
  expect(l.remote).toEqual([])
})

test("listBranches: not a repo → nulls and empties", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-br-plain-"))
  const l = listBranches(dir)
  expect(l.repoRoot).toBeNull()
  expect(l.current).toBeNull()
  expect(l.local).toEqual([])
  expect(l.remote).toEqual([])
})
