// src/core/worktree/manager.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync, existsSync, writeFileSync, readFileSync, symlinkSync } from "fs"
import { homedir, tmpdir } from "os"
import { join, relative, resolve, sep } from "path"
import { createWorktree, removeWorktree, deriveSessionBranch, ensureWorktreeAt, existingBranchNames, worktreesRoot, createSessionBranch, repoSlug } from "./manager"

function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-wt-"))
  execFileSync("git", ["init", "-b", "main", dir])
  execFileSync("git", ["-C", dir, "config", "user.email", "t@t.t"])
  execFileSync("git", ["-C", dir, "config", "user.name", "t"])
  writeFileSync(join(dir, "f.txt"), "hi")
  execFileSync("git", ["-C", dir, "add", "."]); execFileSync("git", ["-C", dir, "commit", "-m", "init"])
  return dir
}

test("tests never create worktrees in the live ~/.mux/worktrees (preload sets MUX_WORKTREES_ROOT)", async () => {
  const live = resolve(homedir(), ".mux", "worktrees")
  expect(resolve(worktreesRoot())).not.toBe(live)
  const h = await createWorktree({ repoRoot: tmpRepo(), baseBranch: "main", sessionName: "iso" })
  expect(resolve(h.worktreeDir).startsWith(live + sep)).toBe(false)
  expect(resolve(h.worktreeDir).startsWith(resolve(process.env.MUX_WORKTREES_ROOT!) + sep)).toBe(true)
  await removeWorktree(h.repoRoot, h.worktreeDir, h.sessionBranch)
})

// Without MUX_WORKTREES_ROOT the worktrees live in the mux home, wherever MUX_HOME puts it: the root was
// $HOME/.mux/worktrees, so a broker with MUX_HOME set made its worktrees outside its own home.
test("worktreesRoot reads MUX_WORKTREES_ROOT at call time, else the mux home's worktrees folder", () => {
  const prev = { root: process.env.MUX_WORKTREES_ROOT, home: process.env.MUX_HOME }
  try {
    process.env.MUX_WORKTREES_ROOT = "/tmp/x-root"
    expect(worktreesRoot()).toBe("/tmp/x-root")
    delete process.env.MUX_WORKTREES_ROOT
    process.env.MUX_HOME = "/tmp/x-mux-home"
    expect(worktreesRoot()).toBe(join("/tmp/x-mux-home", "worktrees"))
  } finally {
    for (const [key, value] of [["MUX_WORKTREES_ROOT", prev.root], ["MUX_HOME", prev.home]] as const) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
})

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

test("a .worktreeinclude folder's links point into the worktree, never back into the main checkout", async () => {
  const repo = tmpRepo()
  mkdirSync(join(repo, "config", "v2"), { recursive: true })
  writeFileSync(join(repo, "config", "v2", "app.env"), "MODE=main")
  symlinkSync("v2", join(repo, "config", "current"), "dir")
  writeFileSync(join(repo, ".worktreeinclude"), "config\n")
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "links" })
  // A session that writes through the link changes its own copy; the main checkout keeps its file.
  writeFileSync(join(h.worktreeDir, "config", "current", "app.env"), "MODE=worktree")
  expect(readFileSync(join(repo, "config", "v2", "app.env"), "utf-8")).toBe("MODE=main")
  expect(readFileSync(join(h.worktreeDir, "config", "v2", "app.env"), "utf-8")).toBe("MODE=worktree")
})

test("a .worktreeinclude entry that links out of the repo still reaches its file from the worktree", async () => {
  const repo = tmpRepo()
  const secrets = mkdtempSync(join(tmpdir(), "mux-wt-secrets-"))
  writeFileSync(join(secrets, ".env"), "SECRET=2")
  // Relative, as `ln -s ../x .env` writes it: from the worktree, which sits elsewhere, it would point nowhere.
  symlinkSync(relative(repo, join(secrets, ".env")), join(repo, ".env"), "file")
  writeFileSync(join(repo, ".worktreeinclude"), ".env\n")
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "outward" })
  expect(readFileSync(join(h.worktreeDir, ".env"), "utf-8")).toBe("SECRET=2")
})

test("ensureWorktreeAt recreates a worktree at the same path when the dir AND its branch are gone (merged-session resume)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "tabbar" })
  expect(existsSync(h.worktreeDir)).toBe(true)
  // Simulate finish→merge cleanup: worktree dir removed AND branch deleted.
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch)
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect((await existingBranchNames(repo)).has(h.sessionBranch)).toBe(false)

  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })

  // Restored at the SAME path (so claude --resume's cwd-hash still matches its transcript).
  expect(existsSync(h.worktreeDir)).toBe(true)
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe(h.sessionBranch)
  expect(existsSync(join(h.worktreeDir, "f.txt"))).toBe(true)
})

test("ensureWorktreeAt reuses the existing branch when the dir is gone but the branch survives", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "keepbranch" })
  // commit on the branch so we can prove the branch's own history is restored (not base)
  writeFileSync(join(h.worktreeDir, "only-on-branch.txt"), "x")
  execFileSync("git", ["-C", h.worktreeDir, "add", "."]); execFileSync("git", ["-C", h.worktreeDir, "commit", "-m", "wip"])
  // Remove the worktree dir but KEEP the branch.
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch, { keepBranch: true })
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect((await existingBranchNames(repo)).has(h.sessionBranch)).toBe(true)

  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })

  expect(existsSync(h.worktreeDir)).toBe(true)
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe(h.sessionBranch)
  // the branch's OWN commit is restored, proving we checked out the surviving branch (not base)
  expect(existsSync(join(h.worktreeDir, "only-on-branch.txt"))).toBe(true)
})

test("ensureWorktreeAt is a no-op when the worktree dir already exists (never clobber live work)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "healthy" })
  writeFileSync(join(h.worktreeDir, "live-edit.txt"), "uncommitted work")
  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })
  expect(readFileSync(join(h.worktreeDir, "live-edit.txt"), "utf-8")).toBe("uncommitted work")
})

test("ensureWorktreeAt prunes a stale registration when the dir was removed without git", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "stale" })
  // Manual/partial deletion: remove the dir but leave git's worktree registration + branch.
  execFileSync("rm", ["-rf", h.worktreeDir])
  expect(existsSync(h.worktreeDir)).toBe(false)
  expect((await existingBranchNames(repo)).has(h.sessionBranch)).toBe(true)

  await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })

  expect(existsSync(h.worktreeDir)).toBe(true)
  const head = execFileSync("git", ["-C", h.worktreeDir, "branch", "--show-current"], { encoding: "utf-8" }).trim()
  expect(head).toBe(h.sessionBranch)
})

test("createWorktree does not block the event loop while the setup hook runs", async () => {
  const repo = tmpRepo()
  const { mkdirSync } = await import("fs")
  mkdirSync(join(repo, ".mux"), { recursive: true })
  writeFileSync(join(repo, ".mux", "worktree-setup.sh"), "sleep 1\n")
  let fired = 0
  const t0 = Date.now()
  const timer = setTimeout(() => { fired = Date.now() - t0 }, 50)
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "hook" })
  clearTimeout(timer)
  expect(fired).toBeGreaterThan(0)
  expect(fired).toBeLessThan(500)
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch)
})

test("createWorktree reports each step's time to onPhase (card #75): branches, add, includes, hook", async () => {
  const repo = tmpRepo()
  writeFileSync(join(repo, ".worktreeinclude"), "notes\nmissing\n")
  execFileSync("mkdir", ["-p", join(repo, "notes")])
  writeFileSync(join(repo, "notes", "a.md"), "local only")
  const phases: Array<[string, Record<string, unknown>]> = []
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "timed", onPhase: (name, fields) => phases.push([name, fields]) })
  expect(phases.map(([name]) => name)).toEqual(["branches", "add", "includes", "hook"])
  for (const [, fields] of phases) expect(typeof fields.ms).toBe("number")
  expect(phases[0]![1].count).toBe(1)
  expect(phases[1]![1].branch).toBe("mux/timed")
  expect(phases[2]![1].copied).toBe(1)
  expect(phases[3]![1].present).toBe(false)
  expect(readFileSync(join(h.worktreeDir, "notes", "a.md"), "utf-8")).toBe("local only")
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch)
})

// Card #75: a session is born asleep with its branch made at Send; its worktree is checked out when
// it wakes, and that checkout gets what createWorktree's gets.
function repoWithIncludesAndHook(): string {
  const repo = tmpRepo()
  writeFileSync(join(repo, ".worktreeinclude"), "notes\n")
  mkdirSync(join(repo, "notes"))
  writeFileSync(join(repo, "notes", "a.md"), "local only")
  mkdirSync(join(repo, ".mux"))
  writeFileSync(join(repo, ".mux", "worktree-setup.sh"), "touch setup-ran\n")
  return repo
}
const branchOf = (dir: string) => execFileSync("git", ["-C", dir, "branch", "--show-current"], { encoding: "utf-8" }).trim()

test("createSessionBranch makes the branch at its base without checking anything out", async () => {
  const repo = tmpRepo()
  await createSessionBranch(repo, "mux/born", "main")
  expect((await existingBranchNames(repo)).has("mux/born")).toBe(true)
  expect(branchOf(repo)).toBe("main")
})

test("a branch made at Send is checked out at wake with the includes copied and the setup hook run", async () => {
  const repo = repoWithIncludesAndHook()
  await createSessionBranch(repo, "mux/born", "main")
  const workdir = join(worktreesRoot(), repoSlug(repo), "born-uuid")
  expect(await ensureWorktreeAt({ repoRoot: repo, workdir, sessionBranch: "mux/born", baseBranch: "main" })).toEqual({ created: true })
  expect(branchOf(workdir)).toBe("mux/born")
  expect(readFileSync(join(workdir, "notes", "a.md"), "utf-8")).toBe("local only")
  expect(existsSync(join(workdir, "setup-ran"))).toBe(true)
  await removeWorktree(repo, workdir, "mux/born")
})

test("onCheckedOut fires once git has checked the files out, before the includes and the setup hook", async () => {
  const repo = repoWithIncludesAndHook()
  await createSessionBranch(repo, "mux/told", "main")
  const workdir = join(worktreesRoot(), repoSlug(repo), "told-uuid")
  let seen: { file: boolean; include: boolean; hook: boolean } | undefined
  await ensureWorktreeAt({
    repoRoot: repo, workdir, sessionBranch: "mux/told", baseBranch: "main",
    onCheckedOut: () => { seen = { file: existsSync(join(workdir, "f.txt")), include: existsSync(join(workdir, "notes")), hook: existsSync(join(workdir, "setup-ran")) } },
  })
  expect(seen).toEqual({ file: true, include: false, hook: false })
  await removeWorktree(repo, workdir, "mux/told")
})

test("an empty folder left by a crash is removed and the worktree made in its place", async () => {
  const repo = tmpRepo()
  await createSessionBranch(repo, "mux/half", "main")
  const workdir = join(worktreesRoot(), repoSlug(repo), "half-uuid")
  mkdirSync(workdir, { recursive: true })
  expect(await ensureWorktreeAt({ repoRoot: repo, workdir, sessionBranch: "mux/half", baseBranch: "main" })).toEqual({ created: true })
  expect(branchOf(workdir)).toBe("mux/half")
  expect(readFileSync(join(workdir, "f.txt"), "utf-8")).toBe("hi")
  await removeWorktree(repo, workdir, "mux/half")
})

test("a folder with something in it is never removed, even one git doesn't list: it may hold work", async () => {
  const repo = tmpRepo()
  await createSessionBranch(repo, "mux/kept", "main")
  const workdir = join(worktreesRoot(), repoSlug(repo), "kept-uuid")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "notes.txt"), "uncommitted work")
  expect(await ensureWorktreeAt({ repoRoot: repo, workdir, sessionBranch: "mux/kept", baseBranch: "main" })).toEqual({ created: false })
  expect(readFileSync(join(workdir, "notes.txt"), "utf-8")).toBe("uncommitted work")
})

test("a session folder whose repo is gone is used as it is (no git call can fail the start)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "orphan" })
  writeFileSync(join(h.worktreeDir, "work.txt"), "still here")
  const gone = repo + "-moved"
  execFileSync("mv", [repo, gone])
  expect(await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })).toEqual({ created: false })
  expect(readFileSync(join(h.worktreeDir, "work.txt"), "utf-8")).toBe("still here")
  execFileSync("rm", ["-rf", gone, h.worktreeDir])
})

test("a healthy worktree is left alone: nothing is copied or run again", async () => {
  const repo = repoWithIncludesAndHook()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "healthy" })
  execFileSync("rm", ["-f", join(h.worktreeDir, "setup-ran")])
  expect(await ensureWorktreeAt({ repoRoot: repo, workdir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })).toEqual({ created: false })
  expect(existsSync(join(h.worktreeDir, "setup-ran"))).toBe(false)
  await removeWorktree(repo, h.worktreeDir, h.sessionBranch)
})
