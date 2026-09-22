// src/core/worktree/inventory.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { existsSync, mkdtempSync, mkdirSync, symlinkSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { deleteWorktrees, listWorktrees, worktreeChanges, worktreeSize, type OwnerRow } from "./inventory"

const git = (cwd: string, ...args: string[]) => execFileSync("git", args, { cwd, stdio: "pipe" }).toString().trim()

/** root/<slug>/<uuid> worktrees of one repo, like ~/.mux/worktrees. */
function fixture() {
  const base = mkdtempSync(join(tmpdir(), "mux-inv-"))
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
})

test("prunes git registrations whose folder is gone and skips them", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  f.add("u2", "mux/b")
  rmSync(a, { recursive: true, force: true })
  const list = await listWorktrees(f.root, [])
  expect(list.map((w) => w.id)).toEqual(["repo-abc/u2"])
  expect(git(f.repo, "worktree", "list")).not.toContain("u1")
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
  const r = await deleteWorktrees(f.root, ["repo-abc/u1"], [row({ id: "s", name: "Sess", workdir: a, status: "active" })])
  expect(r).toEqual([{ id: "repo-abc/u1", ok: false, error: "in_use", inUseBy: ["Sess"] }])
  expect(existsSync(a)).toBe(true)
})

test("force-deletes a dirty worktree with ignored files, and its mux branch", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  writeFileSync(join(a, "new.txt"), "x")
  mkdirSync(join(a, "docs")); writeFileSync(join(a, "docs", "spec.md"), "s")
  const r = await deleteWorktrees(f.root, ["repo-abc/u1"], [row({ id: "s", workdir: a, status: "archived" })])
  expect(r).toEqual([{ id: "repo-abc/u1", ok: true }])
  expect(existsSync(a)).toBe(false)
  expect(git(f.repo, "branch", "--list", "mux/a")).toBe("")
})

test("keeps a non-mux branch", async () => {
  const f = fixture()
  const a = f.add("u1", "feature/x")
  await deleteWorktrees(f.root, ["repo-abc/u1"], [])
  expect(existsSync(a)).toBe(false)
  expect(git(f.repo, "branch", "--list", "feature/x")).toContain("feature/x")
})

test("deletes a folder git does not know", async () => {
  const f = fixture()
  mkdirSync(join(f.root, "repo-abc", "stray"), { recursive: true })
  const r = await deleteWorktrees(f.root, ["repo-abc/stray"], [])
  expect(r[0]!.ok).toBe(true)
  expect(existsSync(join(f.root, "repo-abc", "stray"))).toBe(false)
})

test("rejects ids outside the root, '..' and symlink escapes; the batch continues", async () => {
  const f = fixture()
  const a = f.add("u1", "mux/a")
  const outside = mkdtempSync(join(tmpdir(), "mux-outside-"))
  symlinkSync(outside, join(f.root, "repo-abc", "link"))
  const r = await deleteWorktrees(f.root, ["../repo", "repo-abc/..", "repo-abc/link", "repo-abc/u1"], [])
  expect(r.slice(0, 3).every((x) => !x.ok)).toBe(true)
  expect(r[3]).toEqual({ id: "repo-abc/u1", ok: true })
  expect(existsSync(outside)).toBe(true)
  expect(existsSync(a)).toBe(false)
})
