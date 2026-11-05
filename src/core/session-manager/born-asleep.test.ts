import { afterEach, expect, test } from "bun:test"
import { execFileSync } from "child_process"
import { existsSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join, sep } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { Registry } from "./registry"
import { createAsleep, type CreateAsleepDeps } from "./born-asleep"
import { getRepoInfo } from "../git/repo-info"
import { createSessionBranch, existingBranchNames, repoSlug, worktreesRoot } from "../worktree/manager"
import { homeDirName } from "../../shared/paths"
import { AgentKind } from "../../shared/agents"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

// The real path, as the repo's root comes back from git: on Windows the temp folder can be an 8.3 short name
// (C:\Users\RUNNER~1\…) that git and realpath spell long, and the worktree's folder is named from that spelling.
function tmp(prefix: string): string {
  const d = realpathSync(mkdtempSync(join(tmpdir(), prefix)))
  dirs.push(d)
  return d
}

function repo(): string {
  const dir = tmp("born-repo-")
  execFileSync("git", ["init", "-q", "-b", "main", dir])
  execFileSync("git", ["-C", dir, "config", "user.email", "t@t.t"])
  execFileSync("git", ["-C", dir, "config", "user.name", "t"])
  writeFileSync(join(dir, "f.txt"), "hi")
  execFileSync("git", ["-C", dir, "add", "."])
  execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"])
  return dir
}

function setup() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  const stateDir = tmp("born-state-")
  let n = 0
  const deps: CreateAsleepDeps = {
    registry,
    repoInfo: (workdir) => getRepoInfo(workdir),
    existingBranches: (root) => existingBranchNames(root),
    createBranch: (root, branch, base) => createSessionBranch(root, branch, base),
    worktreesRoot: () => worktreesRoot(),
    stateDir,
    captureBaseCommits: () => ({ "": "base-sha" }),
    newId: () => `id-${++n}`,
  }
  return { registry, deps, stateDir }
}

test("a worktree start: an asleep row with its future worktree, branch and home, and no folder yet", async () => {
  const { registry, deps, stateDir } = setup()
  const root = repo()
  const s = await createAsleep(deps, { workdir: root, requestedName: "Fix login", agent: AgentKind.Codex, model: "gpt-5", reasoningLevel: "high", permissionMode: "auto", account: "codex-team" })
  expect(s.status).toBe("suspended")
  expect(s.connected).toBe(false)
  expect(s.workdir.startsWith(join(worktreesRoot(), repoSlug(root)) + sep)).toBe(true)
  expect(existsSync(s.workdir)).toBe(false)
  expect(s).toMatchObject({ name: "Fix login", agent: "codex", model: "gpt-5", reasoningLevel: "high", account: "codex-team", repo_root: root, base_branch: "main", session_branch: "mux/fix-login", core: true })
  expect(s.agent_home).toBe(join(stateDir, "agents", "codex", homeDirName("Fix login")))
  expect((await existingBranchNames(root)).has("mux/fix-login")).toBe(true)
  expect(registry.sessions.getById(s.id)?.status).toBe("suspended")
})

test("a folder that is not a single repo: the row runs in the folder itself", async () => {
  const { deps } = setup()
  const folder = tmp("born-plain-")
  const s = await createAsleep(deps, { workdir: folder, agent: AgentKind.Claude })
  expect(s.workdir).toBe(folder)
  expect(s.session_branch ?? undefined).toBeUndefined()
  expect(s.status).toBe("suspended")
  expect(s.base_commits).toEqual({ "": "base-sha" })
})

test("two quick Sends to one repo get two branches", async () => {
  const { deps } = setup()
  const root = repo()
  const [a, b] = await Promise.all([
    createAsleep(deps, { workdir: root, requestedName: "Same", agent: AgentKind.Claude }),
    createAsleep(deps, { workdir: root, requestedName: "Same", agent: AgentKind.Claude }),
  ])
  expect(new Set([a.session_branch, b.session_branch]).size).toBe(2)
  expect(new Set([a.name, b.name]).size).toBe(2)
})

test("a council's worktree made beforehand is used as it is, with its base commits taken now", async () => {
  const { deps } = setup()
  const root = repo()
  const made = tmp("born-premade-")
  const s = await createAsleep(deps, {
    workdir: root, agent: AgentKind.Claude, id: "member-1",
    worktreeHandle: { worktreeDir: made, sessionBranch: "mux/council-a", baseBranch: "main", repoRoot: root },
  })
  expect(s).toMatchObject({ id: "member-1", workdir: made, session_branch: "mux/council-a", base_branch: "main", repo_root: root })
  expect(s.base_commits).toEqual({ "": "base-sha" })
})

test("a taken name gets a unique one", async () => {
  const { registry, deps } = setup()
  registry.register({ name: "Busy", workdir: "/x", pid: 0 })
  const s = await createAsleep(deps, { workdir: tmp("born-plain-"), requestedName: "Busy", agent: AgentKind.Claude })
  expect(s.name).not.toBe("Busy")
  expect(s.name.startsWith("Busy")).toBe(true)
})

test("a worktree that must be made in a folder that is not a repo fails, and nothing is registered", async () => {
  const { registry, deps } = setup()
  const before = registry.list().length
  await expect(createAsleep(deps, { workdir: tmp("born-plain-"), agent: AgentKind.Claude, requireWorktree: true })).rejects.toThrow(/not a single git repository/)
  expect(registry.list().length).toBe(before)
})

test("a repo with no commits yet: no branch can be made, so the session runs in the folder, as before", async () => {
  const { registry, deps } = setup()
  const fresh = tmp("born-fresh-")
  execFileSync("git", ["init", "-q", "-b", "main", fresh])
  const s = await createAsleep(deps, { workdir: fresh, requestedName: "Fresh repo", agent: AgentKind.Claude })
  expect(s.workdir).toBe(fresh)
  expect(s.session_branch ?? undefined).toBeUndefined()
  expect(s.status).toBe("suspended")
  // A worktree that must be made (delegate) still fails, and nothing is registered.
  const before = registry.list().length
  await expect(createAsleep(deps, { workdir: fresh, agent: AgentKind.Claude, requireWorktree: true })).rejects.toThrow()
  expect(registry.list().length).toBe(before)
})
