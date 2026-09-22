// src/core/worktree/inventory.test.ts
import { afterAll, test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { existsSync, mkdtempSync, mkdirSync, readdirSync, renameSync, symlinkSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { deleteWorktrees, listWorktrees, worktreeChanges, worktreeSize, type OwnerRow } from "./inventory"

// Every temp dir a test makes; removed after the file (M4).
const temps: string[] = []
const tmp = (prefix: string) => { const d = mkdtempSync(join(tmpdir(), prefix)); temps.push(d); return d }
afterAll(() => { for (const d of temps) rmSync(d, { recursive: true, force: true }) })

const git = (cwd: string, ...args: string[]) => execFileSync("git", args, { cwd, stdio: "pipe" }).toString().trim()

/** root/<slug>/<uuid> worktrees of one repo, like ~/.mux/worktrees. */
function fixture() {
  const base = tmp("mux-inv-")
  const repo = join(base, "repo")
  const root = join(base, "worktrees")
  mkdirSync(repo, { recursive: true })
  git(repo, "init", "-q", "-b", "main")
  git(repo, "config", "user.email", "t@t")
  git(repo, "config", "user.name", "t")
  writeFileSync(join(repo, ".gitignore"), "docs/\nnode_modules/\n")
  git(repo, "add", ".")
  git(repo, "commit", "-q", "-m", "init")
  const add = (uuid: string, branch: string) => {
    const dir = join(root, "repo-abc", uuid)
    mkdirSync(join(root, "repo-abc"), { recursive: true })
    git(repo, "worktree", "add", "-q", "-b", branch, dir, "main")
    return dir
  }
  return { base, repo, root, add }
}

const row = (o: Partial<OwnerRow> & Pick<OwnerRow, "id" | "workdir">): OwnerRow =>
  ({ name: o.id, status: "active", user_status: "in_progress", base_branch: "main", session_branch: null, ...o })

test("lists every worktree with its owners (live / archived / orphan)", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const b = f.add("u2", "mux/b")
  const c = f.add("u3", "mux/c")
  const owners = [
    row({ id: "live", workdir: a, status: "active" }),
    row({ id: "arch", workdir: b, status: "archived" }),
  ]
  const list = await listWorktrees(f.root, owners)
  const byId = Object.fromEntries(list.map((w) => [w.id, w]))
  expect(Object.keys(byId).sort()).toEqual(["repo-abc/u1", "repo-abc/u2", "repo-abc/u3"])
  expect(byId["repo-abc/u1"]!.owners).toEqual([{ id: "live", name: "live", status: "live" }])
  expect(byId["repo-abc/u2"]!.owners).toEqual([{ id: "arch", name: "arch", status: "archived" }])
  expect(byId["repo-abc/u3"]!.owners).toEqual([])
  expect(byId["repo-abc/u3"]!.branch).toBe("mux/c")
  expect(byId["repo-abc/u3"]!.repoRoot).toBe(f.repo)
  expect(c).toContain("u3")
})

test("draft sessions do not count as owners", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const list = await listWorktrees(f.root, [row({ id: "d", workdir: a, user_status: "draft" })])
  expect(list[0]!.owners).toEqual([])
})

test("counts uncommitted, unmerged and splits ignored into well-known vs not", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "new.txt"), "x")                       // untracked → uncommitted
  mkdirSync(join(a, "docs")); writeFileSync(join(a, "docs", "spec.md"), "s")   // ignored, NOT well-known
  mkdirSync(join(a, "node_modules")); writeFileSync(join(a, "node_modules", "m.js"), "m") // ignored, well-known
  const b = f.add("u2", "mux/b")
  writeFileSync(join(b, "c.txt"), "c"); git(b, "add", "."); git(b, "commit", "-q", "-m", "c")  // 1 unmerged
  const owners = [row({ id: "a", workdir: a, session_branch: "mux/a" }), row({ id: "b", workdir: b, session_branch: "mux/b" })]
  const list = await listWorktrees(f.root, owners)
  const wa = list.find((w) => w.id === "repo-abc/u1")!
  const wb = list.find((w) => w.id === "repo-abc/u2")!
  expect(wa.uncommitted).toBe(1)
  expect(wa.ignored).toEqual([{ name: "docs", wellKnown: false }, { name: "node_modules", wellKnown: true }])
  expect(wa.hasChanges).toBe(true)
  expect(wb.unmerged).toBe(1)
  expect(wb.hasChanges).toBe(true)
})

test("a worktree with only well-known ignored entries has no changes", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  mkdirSync(join(a, "node_modules")); writeFileSync(join(a, "node_modules", "m.js"), "m")
  const list = await listWorktrees(f.root, [row({ id: "a", workdir: a, session_branch: "mux/a" })])
  expect(list[0]!.hasChanges).toBe(false)
})

test("orphan with no upstream reports unknown base (unmerged null)", async () => {
  const f = fixture()
  f.add("u1", "mux/a")
  const list = await listWorktrees(f.root, [])
  expect(list[0]!.unmerged).toBeNull()
  expect(list[0]!.hasChanges).toBe(true)   // unknown base is not "no changes" (I1)
})

test("listing skips registrations whose folder is gone and never prunes the repo (I4)", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  f.add("u2", "mux/b")
  rmSync(a, { recursive: true, force: true })
  const list = await listWorktrees(f.root, [])
  expect(list.map((w) => w.id)).toEqual(["repo-abc/u2"])
  expect(git(f.repo, "worktree", "list")).toContain("u1")   // read-only: registration untouched
})

test("a folder git does not know is listed with an error, not dropped", async () => {
  const f = fixture()
  f.add("u1", "mux/a")
  mkdirSync(join(f.root, "repo-abc", "stray"))
  const list = await listWorktrees(f.root, [])
  const stray = list.find((w) => w.id === "repo-abc/stray")!
  expect(stray.error).toBeTruthy()
  expect(stray.owners).toEqual([])
})

test("worktreeChanges returns files, commits and ignored entries with sizes", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "c.txt"), "c"); git(a, "add", "."); git(a, "commit", "-q", "-m", "add c")
  writeFileSync(join(a, "new.txt"), "x")
  mkdirSync(join(a, "docs")); writeFileSync(join(a, "docs", "spec.md"), "s".repeat(5000))
  const ch = await worktreeChanges(f.root, "repo-abc/u1", [row({ id: "a", workdir: a, session_branch: "mux/a" })])
  expect(ch.files).toEqual([{ status: "??", path: "new.txt" }])
  expect(ch.commits.map((c) => c.subject)).toEqual(["add c"])
  expect(ch.ignored[0]!.name).toBe("docs")
  expect(ch.ignored[0]!.bytes).toBeGreaterThan(0)
})

test("worktreeSize measures the folder", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "big.bin"), Buffer.alloc(200_000))
  expect(await worktreeSize(f.root, "repo-abc/u1")).toBeGreaterThan(150_000)
})

test("refuses a worktree a live session uses", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const r = await deleteWorktrees(f.root, ["repo-abc/u1"], () => [row({ id: "s", name: "Sess", workdir: a, status: "active" })])
  expect(r).toEqual([{ id: "repo-abc/u1", ok: false, error: "in_use", inUseBy: ["Sess"] }])
  expect(existsSync(a)).toBe(true)
})

test("force-deletes a dirty worktree with ignored files, and its mux branch", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "new.txt"), "x")
  mkdirSync(join(a, "docs")); writeFileSync(join(a, "docs", "spec.md"), "s")
  const r = await deleteWorktrees(f.root, ["repo-abc/u1"], () => [row({ id: "s", workdir: a, status: "archived" })])
  expect(r).toEqual([{ id: "repo-abc/u1", ok: true }])
  expect(existsSync(a)).toBe(false)
  expect(git(f.repo, "branch", "--list", "mux/a")).toBe("")
})

test("keeps a non-mux branch", async () => {
  const f = fixture()
  const a = f.add("u1", "feature/x")
  await deleteWorktrees(f.root, ["repo-abc/u1"], () => [])
  expect(existsSync(a)).toBe(false)
  expect(git(f.repo, "branch", "--list", "feature/x")).toContain("feature/x")
})

test("deletes a folder git does not know", async () => {
  const f = fixture()
  mkdirSync(join(f.root, "repo-abc", "stray"), { recursive: true })
  const r = await deleteWorktrees(f.root, ["repo-abc/stray"], () => [])
  expect(r[0]!.ok).toBe(true)
  expect(existsSync(join(f.root, "repo-abc", "stray"))).toBe(false)
})

test("rejects ids outside the root, '..' and symlink escapes; the batch continues", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const outside = tmp("mux-outside-")
  symlinkSync(outside, join(f.root, "repo-abc", "link"))
  const r = await deleteWorktrees(f.root, ["../repo", "repo-abc/..", "repo-abc/link", "repo-abc/u1"], () => [])
  expect(r.slice(0, 3).every((x) => !x.ok)).toBe(true)
  expect(r[3]).toEqual({ id: "repo-abc/u1", ok: true })
  expect(existsSync(outside)).toBe(true)
  expect(existsSync(a)).toBe(false)
})

// ---- Regression tests for the safety review (C1–C3, I1–I5, M1, M3) ----

test("C1: a symlinked root still protects a live session's worktree (owner stored unresolved)", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const link = join(f.base, "wt-link")
  symlinkSync(f.root, link)
  const viaLink = join(link, "repo-abc", "u1")
  const rows = [row({ id: "s", name: "Sess", workdir: viaLink, status: "active" })]
  expect(await deleteWorktrees(link, ["repo-abc/u1"], () => rows)).toEqual([{ id: "repo-abc/u1", ok: false, error: "in_use", inUseBy: ["Sess"] }])
  expect(existsSync(a)).toBe(true)
  // A live session in a subfolder that no longer exists, recorded through the link.
  const gone = [row({ id: "s2", name: "Gone", workdir: join(viaLink, "missing", "sub"), status: "active" })]
  expect((await deleteWorktrees(link, ["repo-abc/u1"], () => gone))[0]!.error).toBe("in_use")
  expect(existsSync(a)).toBe(true)
  const list = await listWorktrees(link, rows)
  expect(list[0]!.owners).toEqual([{ id: "s", name: "Sess", status: "live" }])
})

test("C1: a symlinked root still sees an owner stored as a realpath", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const link = join(f.base, "wt-link")
  symlinkSync(f.root, link)
  const rows = [row({ id: "s", name: "Sess", workdir: a, status: "active" })]
  const list = await listWorktrees(link, rows)
  expect(list[0]!.owners).toEqual([{ id: "s", name: "Sess", status: "live" }])
  expect((await deleteWorktrees(link, ["repo-abc/u1"], () => rows))[0]!.error).toBe("in_use")
  expect(existsSync(a)).toBe(true)
})

test("C2: a live session working in a subfolder of the worktree protects it", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  mkdirSync(join(a, "apps", "web"), { recursive: true })
  const rows = [row({ id: "s", name: "Sub", workdir: join(a, "apps", "web") + "/", status: "active" })]
  const r = await deleteWorktrees(f.root, ["repo-abc/u1"], () => rows)
  expect(r).toEqual([{ id: "repo-abc/u1", ok: false, error: "in_use", inUseBy: ["Sub"] }])
  expect(existsSync(a)).toBe(true)
  const list = await listWorktrees(f.root, rows)
  expect(list[0]!.owners).toEqual([{ id: "s", name: "Sub", status: "live" }])
  // A sibling whose name merely starts with the same characters is NOT an owner.
  const b = f.add("u1x", "mux/b")
  const byId = Object.fromEntries((await listWorktrees(f.root, [row({ id: "t", workdir: b })])).map((w) => [w.id, w]))
  expect(byId["repo-abc/u1"]!.owners).toEqual([])
})

test("C3: hundreds of worktrees whose repo is gone list fast as 'repo gone' orphans", async () => {
  const base = tmp("mux-inv-gone-")
  const root = join(base, "worktrees")
  const goneRepo = join(base, "deleted-repo")
  for (let i = 0; i < 200; i++) {
    const dir = join(root, "gone-slug", `u${i}`)
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, ".git"), `gitdir: ${goneRepo}/.git/worktrees/u${i}\n`)
    writeFileSync(join(dir, "f.txt"), "x")
  }
  const owner = join(root, "gone-slug", "u7")
  const t0 = performance.now()
  const list = await listWorktrees(root, [row({ id: "arch", workdir: owner, status: "archived" })])
  expect(performance.now() - t0).toBeLessThan(3000)
  expect(list.length).toBe(200)
  for (const w of list) {
    expect(w.error).toBe("repo gone")
    expect(w.hasChanges).toBe(true)   // unknown contents: never presumed clean (M1)
    expect(w.unmerged).toBeNull()
    expect(w.uncommitted).toBe(0)
    expect(w.repoName).toBe("deleted-repo")
  }
  expect(list.find((w) => w.id === "gone-slug/u7")!.owners).toEqual([{ id: "arch", name: "arch", status: "archived" }])
  expect(await deleteWorktrees(root, ["gone-slug/u3"], () => [])).toEqual([{ id: "gone-slug/u3", ok: true }])
  expect(existsSync(join(root, "gone-slug", "u3"))).toBe(false)
})

test("M1: repo gone by rename — an untracked file may still be there, so hasChanges is true and delete never touches the renamed repo", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "precious.txt"), "x")   // untracked — would be silently lost if presumed clean
  const movedRepo = `${f.repo}-moved`
  renameSync(f.repo, movedRepo)                 // repo renamed/moved, not deleted — its files are still there
  const list = await listWorktrees(f.root, [])
  expect(list[0]!.error).toBe("repo gone")
  expect(list[0]!.hasChanges).toBe(true)
  const before = readdirSync(movedRepo).sort()
  const r = await deleteWorktrees(f.root, ["repo-abc/u1"], () => [])
  expect(r).toEqual([{ id: "repo-abc/u1", ok: true }])
  expect(existsSync(a)).toBe(false)             // the worktree folder is gone
  expect(existsSync(movedRepo)).toBe(true)      // the renamed repo itself was never touched
  expect(readdirSync(movedRepo).sort()).toEqual(before)
})

test("I1: a base branch that no longer exists and no upstream is unknown, not 0", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "c.txt"), "c"); git(a, "add", "."); git(a, "commit", "-q", "-m", "c")
  const list = await listWorktrees(f.root, [row({ id: "a", workdir: a, base_branch: "deleted/base", status: "archived" })])
  expect(list[0]!.unmerged).toBeNull()
  expect(list[0]!.hasChanges).toBe(true)
})

test("I1: a missing base branch falls back to the upstream", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  git(a, "branch", "--set-upstream-to=main")
  writeFileSync(join(a, "c.txt"), "c"); git(a, "add", "."); git(a, "commit", "-q", "-m", "c")
  const rows = [row({ id: "a", workdir: a, base_branch: "deleted/base", status: "archived" })]
  const list = await listWorktrees(f.root, rows)
  expect(list[0]!.unmerged).toBe(1)
  const ch = await worktreeChanges(f.root, "repo-abc/u1", rows)
  expect(ch.commits.map((c) => c.subject)).toEqual(["c"])
})

test("I2: a folder without its own .git inside an enclosing repo is not a git worktree", async () => {
  const f = fixture()
  git(f.repo, "checkout", "-q", "-b", "mux/enclosing")
  const root = join(f.repo, "nested-root")
  mkdirSync(join(root, "slug", "stray"), { recursive: true })
  writeFileSync(join(root, "slug", "stray", "x.txt"), "x")
  const list = await listWorktrees(root, [])
  expect(list[0]!.error).toBe("not a git worktree")
  expect(list[0]!.branch).toBeUndefined()
  expect(list[0]!.repoRoot).toBeUndefined()
  expect(list[0]!.hasChanges).toBe(true)   // unknown contents: never auto-selected
  const ch = await worktreeChanges(root, "slug/stray", [])
  expect(ch.branch).toBeUndefined()
  expect(ch.files).toEqual([])
  expect(await deleteWorktrees(root, ["slug/stray"], () => [])).toEqual([{ id: "slug/stray", ok: true }])
  expect(git(f.repo, "branch", "--list", "mux/enclosing")).toContain("mux/enclosing")
})

test("I3: changes keep the first path intact and handle spaces and renames", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "a.txt"), "1"); writeFileSync(join(a, "b.txt"), "b")
  git(a, "add", "."); git(a, "commit", "-q", "-m", "files")
  writeFileSync(join(a, "a.txt"), "2")                 // " M a.txt" — the first line
  git(a, "mv", "b.txt", "c.txt")                       // "R  c.txt\0b.txt"
  writeFileSync(join(a, "with space.txt"), "s")
  const ch = await worktreeChanges(f.root, "repo-abc/u1", [row({ id: "a", workdir: a })])
  expect(ch.files).toEqual([
    { status: "M", path: "a.txt" },
    { status: "R", path: "c.txt" },
    { status: "??", path: "with space.txt" },
  ])
  const list = await listWorktrees(f.root, [row({ id: "a", workdir: a })])
  expect(list[0]!.uncommitted).toBe(3)
})

test("I5: relative gitdir (worktree.useRelativePaths) resolves the repo and deletes the mux branch", async () => {
  const f = fixture()
  const dir = join(f.root, "repo-abc", "rel")
  mkdirSync(join(f.root, "repo-abc"), { recursive: true })
  git(f.repo, "-c", "worktree.useRelativePaths=true", "worktree", "add", "-q", "-b", "mux/rel", dir, "main")
  const list = await listWorktrees(f.root, [])
  expect(list[0]!.error).toBeUndefined()
  expect(list[0]!.repoRoot).toBe(f.repo)
  expect(list[0]!.branch).toBe("mux/rel")
  expect(await deleteWorktrees(f.root, ["repo-abc/rel"], () => [])).toEqual([{ id: "repo-abc/rel", ok: true }])
  expect(git(f.repo, "branch", "--list", "mux/rel")).toBe("")
})

test("M1: symlinked ids inside the root are rejected (no aliasing another worktree)", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  symlinkSync(a, join(f.root, "repo-abc", "alias"))
  symlinkSync(join(f.root, "repo-abc"), join(f.root, "alias-slug"))
  const r = await deleteWorktrees(f.root, ["repo-abc/alias", "alias-slug/u1"], () => [])
  expect(r.every((x) => !x.ok)).toBe(true)
  expect(existsSync(a)).toBe(true)
  expect(git(f.repo, "branch", "--list", "mux/a")).toContain("mux/a")
})

test("M3: a draft row's base branch is ignored", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "c.txt"), "c"); git(a, "add", "."); git(a, "commit", "-q", "-m", "c")
  const list = await listWorktrees(f.root, [row({ id: "d", workdir: a, user_status: "draft", base_branch: "mux/a" })])
  expect(list[0]!.unmerged).toBeNull()
})

// ---- Final-review fixes (I1, M5) ----

test("final I1: owners are re-read per id — a session restored mid-batch keeps its worktree", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const b = f.add("u2", "mux/b")
  let calls = 0
  // The 2nd read happens after the first delete started: by then a session is live in u2.
  const owners = () => (++calls >= 2 ? [row({ id: "s", name: "Restored", workdir: b, status: "active" })] : [])
  const r = await deleteWorktrees(f.root, ["repo-abc/u1", "repo-abc/u2"], owners)
  expect(r).toEqual([
    { id: "repo-abc/u1", ok: true },
    { id: "repo-abc/u2", ok: false, error: "in_use", inUseBy: ["Restored"] },
  ])
  expect(existsSync(a)).toBe(false)
  expect(existsSync(b)).toBe(true)
  expect(calls).toBeGreaterThanOrEqual(2)
})

test("final M5: an already-gone id inside the root is ok (end state reached); invalid/escaping ids still error", async () => {
  const f = fixture()
  f.add("u1", "mux/a")
  writeFileSync(join(f.root, "a-file"), "x")
  const outside = tmp("mux-outside-")
  symlinkSync(outside, join(f.root, "slug-link"))
  const r = await deleteWorktrees(f.root, ["repo-abc/never-existed", "no-such-slug/u9", "../x", "a/b/c", "a-file/u1", "slug-link/u1"], () => [])
  expect(r[0]).toEqual({ id: "repo-abc/never-existed", ok: true })
  expect(r[1]).toEqual({ id: "no-such-slug/u9", ok: true })
  expect(r.slice(2).every((x) => !x.ok && !!x.error)).toBe(true)
  expect(existsSync(outside)).toBe(true)
})
