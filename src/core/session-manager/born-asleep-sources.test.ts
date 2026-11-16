// #152: a session made from what the launcher picked: a new branch, an existing one, the project folder itself
// (switched at Start), or another session's copy. Git keeps a branch in one place, so a branch held elsewhere is
// refused with who holds it, before anything is made.
import { afterEach, expect, test } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { Registry } from "./registry"
import { createAsleep, SourceRefused, type CreateAsleepDeps } from "./born-asleep"
import { getRepoInfo } from "../git/repo-info"
import { liveBranch } from "../git/branches"
import { createSessionBranch, existingBranchNames, worktreesRoot } from "../worktree/manager"
import { AgentKind } from "../../shared/agents"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
// The native form: on Windows the temp dir can be an 8.3 short name (RUNNER~1), which the broker resolves (git does
// too) to the long one, so a path compared with the broker's must be resolved the same way.
function tmp(prefix: string): string {
  const d = realpathSync.native(mkdtempSync(join(tmpdir(), prefix)))
  dirs.push(d)
  return d
}
const git = (cwd: string, ...a: string[]) => execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()

/** Your folder on `main` with an origin that also has `feat/login`; local branches `feat/search` and `dev`. */
function repo(): string {
  const bare = join(tmp("src-origin-"), "o.git")
  execFileSync("git", ["init", "-q", "--bare", "-b", "main", bare])
  const dir = tmp("src-repo-")
  execFileSync("git", ["init", "-q", "-b", "main", dir])
  git(dir, "config", "user.email", "t@t.t"); git(dir, "config", "user.name", "t"); git(dir, "config", "core.autocrlf", "false")
  writeFileSync(join(dir, "f.txt"), "hi\n"); git(dir, "add", "."); git(dir, "commit", "-q", "-m", "init")
  git(dir, "remote", "add", "origin", bare)
  git(dir, "push", "-q", "-u", "origin", "main"); git(dir, "push", "-q", "origin", "main:feat/login")
  git(dir, "fetch", "-q", "origin"); git(dir, "remote", "set-head", "origin", "main")
  git(dir, "branch", "feat/search"); git(dir, "branch", "dev")
  return dir
}

function setup() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  let n = 0
  const deps: CreateAsleepDeps = {
    registry,
    repoInfo: (workdir) => getRepoInfo(workdir),
    existingBranches: (root) => existingBranchNames(root),
    createBranch: (root, branch, base) => createSessionBranch(root, branch, base),
    worktreesRoot: () => worktreesRoot(),
    stateDir: tmp("src-state-"),
    captureBaseCommits: () => ({ "": "base-sha" }),
    newId: () => `id-${++n}`,
  }
  return { registry, deps }
}
const refusal = async (p: Promise<unknown>): Promise<Record<string, unknown>> => {
  const err = await p.then(() => undefined, (e) => e)
  expect(err).toBeInstanceOf(SourceRefused)
  return { code: (err as SourceRefused).code, ...(err as SourceRefused).details }
}

test("new: a typed name is the branch, made from the base; who asked is recorded", async () => {
  const { deps, registry } = setup()
  const dir = repo()
  const s = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "new", base: "dev", name: "ahmet/fix-login" }, createdBy: '{"kind":"device","name":"laptop"}' })
  expect(s).toMatchObject({ session_branch: "ahmet/fix-login", base_branch: "dev", source_kind: "new", created_by: '{"kind":"device","name":"laptop"}' })
  expect(git(dir, "rev-parse", "ahmet/fix-login")).toBe(git(dir, "rev-parse", "dev"))
  expect(registry.sessions.getById(s.id)).toMatchObject({ source_kind: "new", created_by: '{"kind":"device","name":"laptop"}' })
}, REAL_GIT_MS)

test("new: a typed name that exists is refused and nothing is made; an automatic one gets a free name", async () => {
  const { deps, registry } = setup()
  const dir = repo()
  expect(await refusal(createAsleep(deps, { workdir: dir, requestedName: "Fix", agent: AgentKind.Claude, source: { kind: "new", base: "main", name: "feat/search" } }))).toEqual({ code: "branch_exists", branch: "feat/search" })
  expect(registry.list()).toHaveLength(0)
  expect(registry.takenNames().has("Fix")).toBe(false)
  const s = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "new", base: "main", name: "feat/search", auto: true } })
  expect(s.session_branch).toBe("feat/search-2")
}, REAL_GIT_MS)

test("existing: a worktree on the branch itself, no new branch, the default branch as its base", async () => {
  const { deps } = setup()
  const dir = repo()
  const before = await existingBranchNames(dir)
  const s = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "existing", branch: "feat/search" } })
  expect(s).toMatchObject({ session_branch: "feat/search", base_branch: "main", source_kind: "existing", repo_root: dir })
  expect(s.workdir).not.toBe(dir)
  expect(await existingBranchNames(dir)).toEqual(before)
}, REAL_GIT_MS)

test("existing: a branch only on origin gets a local branch that tracks it", async () => {
  const { deps } = setup()
  const dir = repo()
  const s = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "existing", branch: "origin/feat/login" } })
  expect(s.session_branch).toBe("feat/login")
  expect(git(dir, "rev-parse", "--abbrev-ref", "feat/login@{upstream}")).toBe("origin/feat/login")
}, REAL_GIT_MS)

test("existing: the folder's own branch, or one a session has, is refused with who holds it", async () => {
  const { deps } = setup()
  const dir = repo()
  expect(await refusal(createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "existing", branch: "main" } }))).toEqual({ code: "held_elsewhere", branch: "main", holder: { kind: "folder" } })
  // A session made a moment ago holds its branch before its copy is even checked out.
  const first = await createAsleep(deps, { workdir: dir, requestedName: "Search", agent: AgentKind.Claude, source: { kind: "existing", branch: "feat/search" } })
  expect(await refusal(createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "existing", branch: "feat/search" } }))).toEqual({ code: "held_elsewhere", branch: "feat/search", holder: { kind: "session", id: first.id, name: "Search" } })
  // A worktree no session owns.
  const wt = join(tmp("src-wt-"), "w")
  git(dir, "worktree", "add", "-q", wt, "dev")
  expect(await refusal(createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "existing", branch: "dev" } }))).toEqual({ code: "held_elsewhere", branch: "dev", holder: { kind: "external", path: realpathSync.native(wt) } })
  expect(await refusal(createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "existing", branch: "nope" } }))).toEqual({ code: "no_branch", branch: "nope" })
}, REAL_GIT_MS)

test("local: the folder itself, switched to the picked branch at Start when it is clean", async () => {
  const { deps } = setup()
  const dir = repo()
  const s = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "local", switchTo: "feat/search" } })
  expect(s.workdir).toBe(dir)
  expect(s.source_kind).toBe("local")
  expect(liveBranch(dir)).toBe("feat/search")
  const plain = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "local" } })
  expect(plain.workdir).toBe(dir)
}, REAL_GIT_MS)

test("local: a folder with changes is not switched, and the session is not made", async () => {
  const { deps, registry } = setup()
  const dir = repo()
  writeFileSync(join(dir, "new.txt"), "n\n")
  expect(await refusal(createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "local", switchTo: "feat/search" } }))).toEqual({ code: "dirty", changed: 1 })
  expect(liveBranch(dir)).toBe("main")
  expect(registry.list()).toHaveLength(0)
}, REAL_GIT_MS)

test("reuse: the new session works in the other session's copy, on its branch, with its diff base", async () => {
  const { deps } = setup()
  const dir = repo()
  const other = await createAsleep(deps, { workdir: dir, requestedName: "Cart", agent: AgentKind.Claude, source: { kind: "new", base: "main", name: "fix/cart" } })
  const s = await createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "reuse", sessionId: other.id } })
  expect(s).toMatchObject({ workdir: other.workdir, repo_root: dir, session_branch: "fix/cart", base_branch: "main", source_kind: "reuse", name: "Cart-2" })
  expect(await refusal(createAsleep(deps, { workdir: dir, agent: AgentKind.Claude, source: { kind: "reuse", sessionId: "gone" } }))).toEqual({ code: "no_session", sessionId: "gone" })
}, REAL_GIT_MS)

test("new and existing in a folder that is not a single repository are refused, never quietly run in the folder", async () => {
  const { deps, registry } = setup()
  const plain = tmp("src-plain-")
  expect((await refusal(createAsleep(deps, { workdir: plain, agent: AgentKind.Claude, worktree: false, source: { kind: "new", base: "main" } }))).code).toBe("no_worktree")
  expect((await refusal(createAsleep(deps, { workdir: plain, agent: AgentKind.Claude, source: { kind: "existing", branch: "main" } }))).code).toBe("no_worktree")
  expect(registry.list()).toHaveLength(0)
}, REAL_GIT_MS)
