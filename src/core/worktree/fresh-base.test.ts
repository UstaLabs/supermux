// Card #72: a new copy starts from its explicit base, fetched first when the base tracks a remote branch.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createSessionBranch, createWorktree, ensureWorktreeAt, freshBase, removeWorktree, repoSlug, worktreesRoot } from "./manager"

const git = (dir: string, ...args: string[]) => execFileSync("git", ["-C", dir, ...args], { encoding: "utf-8" }).trim()
function commit(dir: string, file: string, msg: string) {
  writeFileSync(join(dir, file), msg)
  git(dir, "add", "."); git(dir, "commit", "-q", "-m", msg)
  return git(dir, "rev-parse", "HEAD")
}
function identity(dir: string) { git(dir, "config", "user.email", "t@t.t"); git(dir, "config", "user.name", "t") }

/** origin (bare) with `dev`; `local` clones it with dev tracking origin/dev; `other` pushes to origin behind local's back. */
function setup() {
  const root = mkdtempSync(join(tmpdir(), "mux-fresh-"))
  const origin = join(root, "origin.git"), seed = join(root, "seed"), local = join(root, "local"), other = join(root, "other")
  execFileSync("git", ["init", "-q", "--bare", "-b", "dev", origin])
  execFileSync("git", ["init", "-q", "-b", "dev", seed]); identity(seed)
  commit(seed, "a.txt", "one"); git(seed, "remote", "add", "origin", origin); git(seed, "push", "-q", "origin", "dev")
  execFileSync("git", ["clone", "-q", "-b", "dev", origin, local]); identity(local)
  execFileSync("git", ["clone", "-q", "-b", "dev", origin, other]); identity(other)
  return { origin, local, other }
}
const pushNew = (other: string, msg: string) => { const sha = commit(other, "b.txt", msg); git(other, "push", "-q", "origin", "dev"); return sha }

test("a base that tracks a remote branch is fetched first, and the copy starts from it", async () => {
  const { local, other } = setup()
  const fresh = pushNew(other, "two")
  expect(await freshBase(local, "dev")).toBe("origin/dev")
  expect(git(local, "rev-parse", "origin/dev")).toBe(fresh)
  expect(git(local, "rev-parse", "dev")).not.toBe(fresh)   // the user's own branch is never moved
})

test("a branch made at Send moves to the fetched base at its first checkout, and does not track it", async () => {
  const { local, other } = setup()
  await createSessionBranch(local, "mux/born", "dev")
  const fresh = pushNew(other, "two")
  const workdir = join(worktreesRoot(), repoSlug(local), "born-fresh")
  expect(await ensureWorktreeAt({ repoRoot: local, workdir, sessionBranch: "mux/born", baseBranch: "dev" })).toEqual({ created: true })
  expect(git(workdir, "rev-parse", "HEAD")).toBe(fresh)
  expect(() => git(local, "rev-parse", "--abbrev-ref", "mux/born@{upstream}")).toThrow()
  await removeWorktree(local, workdir, "mux/born")
})

test("a branch with a commit of its own is never moved", async () => {
  const { local, other } = setup()
  await createSessionBranch(local, "mux/worked", "dev")
  const tmp = join(worktreesRoot(), repoSlug(local), "worked-tmp")
  git(local, "worktree", "add", "-q", tmp, "mux/worked")
  const own = commit(tmp, "c.txt", "mine")
  git(local, "worktree", "remove", "--force", tmp)
  pushNew(other, "two")
  const workdir = join(worktreesRoot(), repoSlug(local), "worked")
  await ensureWorktreeAt({ repoRoot: local, workdir, sessionBranch: "mux/worked", baseBranch: "dev" })
  expect(git(workdir, "rev-parse", "HEAD")).toBe(own)
  await removeWorktree(local, workdir, "mux/worked")
})

test("a local base with commits the remote lacks stays the start, so nothing local is dropped", async () => {
  const { local, other } = setup()
  const mine = commit(local, "l.txt", "local only")
  pushNew(other, "two")
  expect(await freshBase(local, "dev")).toBe("dev")
  expect(git(local, "rev-parse", "dev")).toBe(mine)
})

test("a base with no upstream is used as it is, and nothing assumes main", async () => {
  const { local } = setup()
  git(local, "switch", "-q", "-c", "release/2.4")
  commit(local, "r.txt", "release")
  expect(await freshBase(local, "release/2.4")).toBe("release/2.4")
  expect(await freshBase(local, "HEAD")).toBe("HEAD")
})

test("a fetch that fails falls back to what the clone already has", async () => {
  const { local } = setup()
  git(local, "remote", "set-url", "origin", join(tmpdir(), "no-such-remote-" + Date.now()))
  expect(await freshBase(local, "dev")).toBe("origin/dev")   // the last known upstream still holds dev
})

test("createWorktree (a worktree made beforehand) starts from the fetched base too", async () => {
  const { local, other } = setup()
  const fresh = pushNew(other, "two")
  const h = await createWorktree({ repoRoot: local, baseBranch: "dev", sessionName: "council member" })
  expect(git(h.worktreeDir, "rev-parse", "HEAD")).toBe(fresh)
  expect(h.baseBranch).toBe("dev")
  await removeWorktree(h.repoRoot, h.worktreeDir, h.sessionBranch)
})
