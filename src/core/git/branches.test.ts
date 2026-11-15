// src/core/git/branches.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync, realpathSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { listBranches, repoToplevel, switchBranch } from "./branches"

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
  expect(l.detachedSha).toBeNull()
  expect(l.local).toEqual([])
  expect(l.remote).toEqual([])
})

test("switchBranch: plain switch to an existing local branch", () => {
  const { work } = repo()
  const r = switchBranch(work, "dev")
  expect(r).toEqual({ status: "switched", branch: "dev" })
  expect(g(work, "branch", "--show-current")).toBe("dev")
})

test("switchBranch: non-conflicting uncommitted changes carry over", () => {
  const { work } = repo()
  writeFileSync(join(work, "f.txt"), "edited\n") // same content on both branches → carry
  const r = switchBranch(work, "dev")
  expect(r.status).toBe("switched")
  expect(g(work, "status", "--porcelain")).toContain("f.txt")
})

test("switchBranch: clobbering uncommitted changes → clobber with files", () => {
  const { work } = repo()
  g(work, "switch", "dev")
  writeFileSync(join(work, "f.txt"), "dev version\n")
  g(work, "add", "."); g(work, "commit", "-m", "diverge f.txt")
  g(work, "switch", "main")
  writeFileSync(join(work, "f.txt"), "uncommitted local\n")
  const r = switchBranch(work, "dev")
  expect(r.status).toBe("clobber")
  if (r.status === "clobber") expect(r.files).toContain("f.txt")
  if (r.status === "clobber") expect(r.branch).toBe("dev")
  expect(g(work, "branch", "--show-current")).toBe("main") // unchanged
})

test("switchBranch: merge in progress → merge_in_progress", () => {
  const { work } = repo()
  // Build a conflicting merge: dev and main both edit f.txt.
  g(work, "switch", "dev")
  writeFileSync(join(work, "f.txt"), "dev\n"); g(work, "add", "."); g(work, "commit", "-m", "dev edit")
  g(work, "switch", "main")
  writeFileSync(join(work, "f.txt"), "main\n"); g(work, "add", "."); g(work, "commit", "-m", "main edit")
  try { g(work, "merge", "dev") } catch { /* conflict expected */ }
  const r = switchBranch(work, "dev")
  expect(r).toEqual({ status: "merge_in_progress" })
})

test("switchBranch: unknown name → error", () => {
  const { work } = repo()
  const r = switchBranch(work, "no-such-branch")
  expect(r.status).toBe("error")
})

test("switchBranch: from detached HEAD back onto a branch", () => {
  const { work } = repo()
  g(work, "checkout", "--detach")
  const r = switchBranch(work, "main")
  expect(r).toEqual({ status: "switched", branch: "main" })
})

test("switchBranch create: new branch off HEAD", () => {
  const { work } = repo()
  const r = switchBranch(work, "feature/x", { create: true })
  expect(r).toEqual({ status: "switched", branch: "feature/x" })
  expect(g(work, "branch", "--show-current")).toBe("feature/x")
})

test("switchBranch create: invalid names rejected without touching the repo", () => {
  const { work } = repo()
  expect(switchBranch(work, "has space", { create: true }).status).toBe("invalid_name")
  expect(switchBranch(work, "-leading-dash", { create: true }).status).toBe("invalid_name")
  expect(switchBranch(work, "  ", { create: true }).status).toBe("invalid_name")
  expect(g(work, "branch", "--show-current")).toBe("main")
})

test("switchBranch create: existing name → error from git", () => {
  const { work } = repo()
  const r = switchBranch(work, "dev", { create: true })
  expect(r.status).toBe("error")
})

test("switchBranch create: works on an unborn HEAD", () => {
  const work = mkdtempSync(join(tmpdir(), "mux-br-unborn2-"))
  execFileSync("git", ["init", "-b", "main", work])
  const r = switchBranch(work, "fresh", { create: true })
  expect(r).toEqual({ status: "switched", branch: "fresh" })
})

test("switchBranch remote: creates a local tracking branch", () => {
  const { work } = repo()
  const r = switchBranch(work, "origin/remote-only")
  expect(r).toEqual({ status: "switched", branch: "remote-only" })
  expect(g(work, "branch", "--show-current")).toBe("remote-only")
  expect(g(work, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")).toBe("origin/remote-only")
})

test("switchBranch remote: existing local twin → just switches to it", () => {
  const { work } = repo()
  g(work, "switch", "dev") // so switching to main is a real change
  const r = switchBranch(work, "origin/main") // local "main" already exists
  expect(r).toEqual({ status: "switched", branch: "main" })
  expect(g(work, "branch", "--show-current")).toBe("main")
})

test("switchBranch: branch held by another worktree → checked_out_elsewhere", () => {
  const { work } = repo()
  const wt = join(mkdtempSync(join(tmpdir(), "mux-br-wt2-")), "w")
  g(work, "worktree", "add", wt, "dev")
  const r = switchBranch(work, "dev")
  expect(r.status).toBe("checked_out_elsewhere")
  if (r.status === "checked_out_elsewhere") expect(r.path).toContain("/w")
})

// ---- #72 G9: one git-branch service for a session's copy (G4) and the main folder (#152) ----
import { branchList, folderState } from "./branches"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const G9_MS = 30_000

test("branchList: default, upstream with ahead/behind, and where each branch is open", () => {
  const { work } = repo()
  const root = realpathSync(work)
  g(work, "branch", "--set-upstream-to=origin/main", "main")
  writeFileSync(join(work, "f.txt"), "2\n"); g(work, "commit", "-qam", "ahead")
  const wtS = join(realpathSync(mkdtempSync(join(tmpdir(), "mux-br-wt-"))), "s")
  g(work, "worktree", "add", "-q", "-b", "mux/s", wtS, "main")
  const wtX = join(realpathSync(mkdtempSync(join(tmpdir(), "mux-br-wt-"))), "x")
  g(work, "worktree", "add", "-q", wtX, "dev")
  const owners = [{ id: "s-1", name: "Search", workdir: wtS }]
  const fromFolder = branchList(work, owners)
  const by = Object.fromEntries(fromFolder.local.map((b) => [b.name, b]))
  expect(by["main"]).toMatchObject({ isDefault: true, upstream: "origin/main", ahead: 1, behind: 0, openIn: { kind: "this" } })
  expect(by["mux/s"]!.openIn).toEqual({ kind: "session", id: "s-1", name: "Search" })
  expect(by["dev"]!.openIn).toEqual({ kind: "external", path: realpathSync(wtX) })
  expect(fromFolder.remote.find((r) => r.name === "origin/main")).toMatchObject({ isDefault: true })
  expect(fromFolder.remote.find((r) => r.name === "origin/remote-only")).toMatchObject({ isDefault: false })
  // from the session's copy, the main folder's branch reads as the folder
  expect(Object.fromEntries(branchList(wtS, owners).local.map((b) => [b.name, b.openIn]))["main"]).toEqual({ kind: "folder" })
  expect(fromFolder.current).toBe("main")
  expect(root).toBe(realpathSync(work))
}, G9_MS)

test("branchList: no origin/HEAD falls back to init.defaultBranch, then main / master", () => {
  const { work } = repo()
  g(work, "remote", "set-head", "origin", "-d")
  expect(branchList(work, []).local.find((b) => b.isDefault)?.name).toBe("main")
}, G9_MS)

test("folderState: clean, or how many files changed (new ones too), and the branch", () => {
  const { work } = repo()
  expect(folderState(work)).toEqual({ branch: "main", detachedSha: null, clean: true, changed: 0 })
  writeFileSync(join(work, "f.txt"), "x\n"); writeFileSync(join(work, "new.txt"), "n\n")
  expect(folderState(work)).toEqual({ branch: "main", detachedSha: null, clean: false, changed: 2 })
}, G9_MS)

test("switchBranch requireClean: a folder with changes is refused before git runs; a clean one switches", () => {
  const { work } = repo()
  writeFileSync(join(work, "new.txt"), "n\n")
  expect(switchBranch(work, "dev", { requireClean: true })).toEqual({ status: "dirty", changed: 1 })
  expect(g(work, "symbolic-ref", "--short", "HEAD")).toBe("main")
  expect(switchBranch(work, "dev")).toEqual({ status: "switched", branch: "dev" })   // without the rule, git's own
}, G9_MS)

test("branchList: each local and remote branch's last commit, its author and time (#152's who · ago)", () => {
  const { work } = repo()
  g(work, "switch", "-q", "dev")
  writeFileSync(join(work, "d.txt"), "d\n"); g(work, "add", ".")
  execFileSync("git", ["-C", work, "commit", "-qm", "dev work", "--author", "Deniz <d@x>"], { env: { ...process.env, GIT_COMMITTER_DATE: "2026-11-01T10:00:00Z" } })
  g(work, "push", "-q", "origin", "dev")
  g(work, "switch", "-q", "main")
  const l = branchList(work, [])
  const at = Date.parse("2026-11-01T10:00:00Z")
  expect(l.local.find((b) => b.name === "dev")).toMatchObject({ author: "Deniz", at })
  expect(l.remote.find((b) => b.name === "origin/dev")).toEqual({ name: "origin/dev", isDefault: false, author: "Deniz", at })
  expect(l.local.find((b) => b.name === "main")!.author).toBe("t")
}, G9_MS)
