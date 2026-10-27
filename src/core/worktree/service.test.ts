// src/core/worktree/service.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync, symlinkSync, existsSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { WorktreeService } from "./service"
import type { OwnerRow } from "./inventory"

const git = (cwd: string, ...args: string[]) => execFileSync("git", args, { cwd, stdio: "pipe" }).toString().trim()

function setup() {
  const base = mkdtempSync(join(tmpdir(), "mux-wsvc-"))
  const repo = join(base, "repo"); const root = join(base, "worktrees")
  mkdirSync(repo); mkdirSync(join(root, "repo-abc"), { recursive: true })
  git(repo, "init", "-q", "-b", "main"); git(repo, "config", "user.email", "t@t"); git(repo, "config", "user.name", "t")
  git(repo, "commit", "-q", "--allow-empty", "-m", "init")
  const dir = join(root, "repo-abc", "u1")
  git(repo, "worktree", "add", "-q", "-b", "mux/a", dir, "main")
  const rows: OwnerRow[] = []
  const frames: any[] = []
  const svc = new WorktreeService({ root, owners: () => rows, broadcast: (f) => { frames.push(f) } })
  return { base, root, dir, rows, frames, svc }
}

const r = (id: string, workdir: string, status: string): OwnerRow =>
  ({ id, name: id, status, user_status: "in_progress", workdir, base_branch: "main", session_branch: "mux/a" })

test("idForWorkdir maps a worktree path to its id, anything else to undefined", () => {
  const { dir, svc } = setup()
  expect(svc.idForWorkdir(dir)).toBe("repo-abc/u1")
  expect(svc.idForWorkdir("/somewhere/else")).toBeUndefined()
})

test("idForWorkdir maps a subfolder inside a worktree to the worktree's id", () => {
  const { dir, svc } = setup()
  const sub = join(dir, "src", "nested")
  mkdirSync(sub, { recursive: true })
  expect(svc.idForWorkdir(sub)).toBe("repo-abc/u1")
})

test("idForWorkdir resolves a symlinked root to the same id", () => {
  const { base, root, svc } = setup()
  const alias = join(base, "worktrees-alias")
  symlinkSync(root, alias)
  expect(svc.idForWorkdir(join(alias, "repo-abc", "u1"))).toBe("repo-abc/u1")
})

test("the first list starts a background scan; later lists answer from the cache", async () => {
  const { svc, frames } = setup()
  const first = await svc.list()
  expect(first.scanning).toBe(true)
  await svc.whenScanned()
  const list = await svc.list()
  expect(list.scanning).toBe(false)
  expect(list.worktrees.map((w) => w.id)).toEqual(["repo-abc/u1"])
  await svc.whenSizesSettled()
  const sizes = frames.filter((f) => f.type === "worktree_sizes").flatMap((f) => f.sizes)
  expect(sizes.map((s: any) => s.id)).toEqual(["repo-abc/u1"])
  expect((await svc.list()).worktrees[0]!.bytes).toBeGreaterThan(0)
})

test("owners are read fresh on every list, not from the scan", async () => {
  const { dir, rows, svc } = setup()
  svc.start(); await svc.whenScanned()
  rows.push(r("late", dir, "active"))
  expect((await svc.list()).worktrees[0]!.owners).toEqual([{ id: "late", name: "late", status: "live" }])
})

test("worktrees whose repo is gone are one orphans summary, paged separately", async () => {
  const { base, root, svc } = setup()
  const gone = join(base, "gone")
  mkdirSync(gone)
  git(gone, "init", "-q", "-b", "main"); git(gone, "config", "user.email", "t@t"); git(gone, "config", "user.name", "t")
  git(gone, "commit", "-q", "--allow-empty", "-m", "init")
  mkdirSync(join(root, "gone-xyz"))
  git(gone, "worktree", "add", "-q", "-b", "mux/g", join(root, "gone-xyz", "g1"), "main")
  rmSync(gone, { recursive: true, force: true })
  svc.start(); await svc.whenScanned()
  const list = await svc.list()
  expect(list.worktrees.map((w) => w.id)).toEqual(["repo-abc/u1"])
  expect(list.orphans.count).toBe(1)
  expect(list.orphans.byLocation).toEqual([{ dir: base, count: 1 }])
  const page = await svc.orphans(0, 10)
  expect(page.total).toBe(1)
  expect(page.worktrees.map((w) => w.id)).toEqual(["gone-xyz/g1"])
})

test("refresh re-inspects one worktree, and drops it with worktrees_removed once its folder is gone", async () => {
  const { dir, svc, frames } = setup()
  svc.start(); await svc.whenScanned()
  writeFileSync(join(dir, "new.txt"), "x")
  await svc.refresh("repo-abc/u1")
  expect((await svc.list()).worktrees[0]!.uncommitted).toBe(1)
  rmSync(dir, { recursive: true, force: true })
  await svc.refresh("repo-abc/u1")
  expect((await svc.list()).worktrees).toEqual([])
  expect(frames).toContainEqual({ type: "worktrees_removed", ids: ["repo-abc/u1"] })
})

test("touchWorkdir refreshes the worktree after the debounce", async () => {
  const { dir, root, rows } = setup()
  const svc = new WorktreeService({ root, owners: () => rows, broadcast: () => {}, debounceMs: 5 })
  svc.start(); await svc.whenScanned()
  writeFileSync(join(dir, "new.txt"), "x")
  svc.touchWorkdir(join(dir, "src"))
  await Bun.sleep(300)
  expect((await svc.list()).worktrees[0]!.uncommitted).toBe(1)
  svc.stop()
})

test("a new worktree under a watched root shows up without a rescan", async () => {
  const { base, root, rows } = setup()
  const watched = new Map<string, (file: string | null) => void>()
  const svc = new WorktreeService({
    root, owners: () => rows, broadcast: () => {}, debounceMs: 5,
    watch: (d, on) => { watched.set(d, on); return { close: () => watched.delete(d) } },
  })
  svc.start(); await svc.whenScanned()
  expect([...watched.keys()].sort()).toEqual([root, join(root, "repo-abc"), join(root, "repo-abc", "u1")])
  git(join(base, "repo"), "worktree", "add", "-q", "-b", "mux/n", join(root, "repo-abc", "u2"), "main")
  watched.get(join(root, "repo-abc"))!("u2")
  await Bun.sleep(300)
  expect((await svc.list()).worktrees.map((w) => w.id)).toEqual(["repo-abc/u1", "repo-abc/u2"])
  expect(watched.has(join(root, "repo-abc", "u2"))).toBe(true)
  svc.stop()
  expect(watched.size).toBe(0)
})

test("remove keeps a worktree that still has a live owner", async () => {
  const { dir, rows, svc } = setup()
  rows.push(r("parent", dir, "active"), r("child", dir, "archived"))
  const out = await svc.remove(["repo-abc/u1"])
  expect(out).toEqual([{ id: "repo-abc/u1", ok: false, error: "in_use", inUseBy: ["parent"] }])
  expect(existsSync(dir)).toBe(true)
})

test("remove deletes once the last owner is archived and broadcasts worktrees_removed", async () => {
  const { dir, rows, svc, frames } = setup()
  rows.push(r("only", dir, "archived"))
  const out = await svc.remove(["repo-abc/u1"])
  expect(out).toEqual([{ id: "repo-abc/u1", ok: true }])
  expect(existsSync(dir)).toBe(false)
  expect(frames).toContainEqual({ type: "worktrees_removed", ids: ["repo-abc/u1"] })
})

test("remove reports in_use for a live session working in a subfolder", async () => {
  const { dir, rows, svc } = setup()
  const sub = join(dir, "nested")
  mkdirSync(sub)
  rows.push(r("other", sub, "active"))
  const out = await svc.remove(["repo-abc/u1"])
  expect(out).toEqual([{ id: "repo-abc/u1", ok: false, error: "in_use", inUseBy: ["other"] }])
  expect(existsSync(dir)).toBe(true)
})

test("forWorkdir returns id, all owners and changes without waiting on the size; undefined for non-worktrees", async () => {
  const { dir, rows, svc } = setup()
  rows.push(r("parent", dir, "active"), r("child", dir, "active"))
  const f = await svc.forWorkdir(dir)
  expect(f!.id).toBe("repo-abc/u1")
  expect(f!.owners.map((o) => o.id)).toEqual(["parent", "child"])
  expect(f!.changes.files).toEqual([])
  expect(f!.bytes).toBeUndefined()
  expect(await svc.size("repo-abc/u1")).toBeGreaterThan(0)
  expect((await svc.forWorkdir(dir))!.bytes).toBeGreaterThan(0)
  expect(await svc.forWorkdir("/nope")).toBeUndefined()
})

// ---- Final-review fixes (M1–M4) ----

test("final M1: a failed size broadcast never breaks the sizing chain", async () => {
  const { base, root } = setup()
  const frames: any[] = []
  let fail = true
  const svc = new WorktreeService({
    root, owners: () => [],
    broadcast: (f: any) => { if (f.type === "worktree_sizes" && fail) { fail = false; throw new Error("ws down") } frames.push(f) },
  })
  await svc.rescan()
  await svc.whenSizesSettled()   // resolves (old code: rejected, and every later size run was skipped)
  const repo = join(base, "repo")
  git(repo, "worktree", "add", "-q", "-b", "mux/b", join(root, "repo-abc", "u2"), "main")
  await svc.rescan()
  await svc.whenSizesSettled()
  const ids = frames.filter((f) => f.type === "worktree_sizes").flatMap((f) => f.sizes).map((s: any) => s.id)
  expect(ids).toContain("repo-abc/u2")
})

test("final M2: back-to-back scans size each worktree once", async () => {
  const { svc, frames } = setup()
  await Promise.all([svc.rescan(), svc.rescan(), svc.rescan()])
  await svc.rescan()
  await svc.whenSizesSettled()
  const ids = frames.filter((f) => f.type === "worktree_sizes").flatMap((f) => f.sizes).map((s: any) => s.id)
  expect(ids).toEqual(["repo-abc/u1"])
})

test("final M3: a throwing broadcast does not turn a completed delete into an error", async () => {
  const { root, dir } = setup()
  const svc = new WorktreeService({ root, owners: () => [], broadcast: () => { throw new Error("ws down") } })
  expect(await svc.remove(["repo-abc/u1"])).toEqual([{ id: "repo-abc/u1", ok: true }])
  expect(existsSync(dir)).toBe(false)
})

test("final M4: remove dedupes ids", async () => {
  const { svc, frames } = setup()
  expect(await svc.remove(["repo-abc/u1", "repo-abc/u1"])).toEqual([{ id: "repo-abc/u1", ok: true }])
  expect(frames).toContainEqual({ type: "worktrees_removed", ids: ["repo-abc/u1"] })
})
