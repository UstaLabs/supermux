// #152: a session on a pull request works on the PR's OWN head branch and makes no new branch. A same-repo PR's
// branch is fetched from origin; a fork's gets its own remote, and pushes go back to the fork (when its author
// allows maintainer edits) so they update the PR. A local branch with commits the PR doesn't have is never
// overwritten: the launcher says so first, and the user picks.
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
import type { PrNumberRead, PrSummary } from "../git/forge"
import { createSessionBranch, existingBranchNames, worktreesRoot } from "../worktree/manager"
import { AgentKind } from "../../shared/agents"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
function tmp(prefix: string): string {
  const d = realpathSync(mkdtempSync(join(tmpdir(), prefix)))
  dirs.push(d)
  return d
}
const git = (cwd: string, ...a: string[]) => execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
function commit(dir: string, file: string, text: string) {
  writeFileSync(join(dir, file), text); git(dir, "add", "."); git(dir, "commit", "-q", "-m", text)
  return git(dir, "rev-parse", "HEAD")
}

/** origin with main + feat/login (a same-repo PR's head); ozan's fork with deps/sdk (a fork PR's head); your clone. */
function world() {
  const root = tmp("pr-world-")
  const origin = join(root, "origin.git"), fork = join(root, "fork.git"), seed = join(root, "seed"), dir = join(root, "you")
  execFileSync("git", ["init", "-q", "--bare", "-b", "main", origin])
  execFileSync("git", ["init", "-q", "-b", "main", seed])
  git(seed, "config", "user.email", "t@t.t"); git(seed, "config", "user.name", "t")
  commit(seed, "a.txt", "base")
  git(seed, "remote", "add", "origin", origin); git(seed, "push", "-q", "origin", "main")
  git(seed, "switch", "-q", "-c", "feat/login"); const loginTip = commit(seed, "login.txt", "login fix"); git(seed, "push", "-q", "origin", "feat/login")
  execFileSync("git", ["clone", "-q", "--bare", origin, fork])
  git(seed, "switch", "-q", "main"); git(seed, "switch", "-q", "-c", "deps/sdk"); const sdkTip = commit(seed, "sdk.txt", "sdk 4.2")
  git(seed, "push", "-q", fork, "deps/sdk")
  execFileSync("git", ["clone", "-q", origin, dir])
  git(dir, "config", "user.email", "t@t.t"); git(dir, "config", "user.name", "t")
  return { origin, fork, dir, loginTip, sdkTip }
}

function pr(o: Partial<PrSummary> & Pick<PrSummary, "number" | "head">): PrSummary {
  return {
    forge: "github", title: "t", url: `https://github.com/acme/storefront/pull/${o.number}`, author: "deniz", base: "main",
    headRepo: "acme/storefront", isFork: false, maintainerCanModify: false, draft: false, checks: "ok", ...o,
  }
}

function setup(prs: Record<string, PrNumberRead>) {
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
    stateDir: tmp("pr-state-"),
    captureBaseCommits: () => ({ "": "base-sha" }),
    newId: () => `id-${++n}`,
    readPr: async (_cwd, ref) => prs[ref] ?? { status: "none", forge: "github" },
  }
  return { registry, deps }
}
const refusal = async (p: Promise<unknown>): Promise<Record<string, unknown>> => {
  const err = await p.then(() => undefined, (e) => e)
  expect(err).toBeInstanceOf(SourceRefused)
  return { code: (err as SourceRefused).code, ...(err as SourceRefused).details }
}

test("a same-repo PR: its own head branch, fetched from origin and tracking it, the PR's target as the base, the PR kept", async () => {
  const w = world()
  const { deps, registry } = setup({ "#123": { status: "found", pr: pr({ number: 123, head: "feat/login" }) } })
  const s = await createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 123 } })
  expect(s).toMatchObject({ session_branch: "feat/login", base_branch: "main", source_kind: "pr", pr_forge: "github", pr_number: 123, pr_url: "https://github.com/acme/storefront/pull/123", push_remote: "origin" })
  expect(git(w.dir, "rev-parse", "feat/login")).toBe(w.loginTip)
  expect(git(w.dir, "rev-parse", "--abbrev-ref", "feat/login@{upstream}")).toBe("origin/feat/login")
  expect(registry.sessions.getById(s.id)).toMatchObject({ pr_number: 123, push_remote: "origin" })
}, REAL_GIT_MS)

test("a fork's PR whose author allows edits: the fork as its own remote, and pushes go back to the PR's branch", async () => {
  const w = world()
  const { deps } = setup({ "#121": { status: "found", pr: pr({ number: 121, head: "deps/sdk", isFork: true, headRepo: "ozan/storefront", headOwner: "ozan", headUrl: w.fork, maintainerCanModify: true }) } })
  const s = await createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 121 } })
  expect(s).toMatchObject({ session_branch: "ozan/deps/sdk", base_branch: "main", source_kind: "pr", pr_number: 121, push_remote: "pr-ozan" })
  expect(git(w.dir, "remote", "get-url", "pr-ozan")).toBe(w.fork)
  expect(git(w.dir, "rev-parse", "ozan/deps/sdk")).toBe(w.sdkTip)
  expect(git(w.dir, "rev-parse", "--abbrev-ref", "ozan/deps/sdk@{upstream}")).toBe("pr-ozan/deps/sdk")
  expect(git(w.dir, "config", "branch.ozan/deps/sdk.pushRemote")).toBe("pr-ozan")
  expect(git(w.dir, "config", "--get-all", "remote.pr-ozan.push")).toBe("refs/heads/ozan/deps/sdk:refs/heads/deps/sdk")
}, REAL_GIT_MS)

test("a fork's PR whose author doesn't allow edits: checked out to run and review, with nowhere to push", async () => {
  const w = world()
  const { deps } = setup({ "#109": { status: "found", pr: pr({ number: 109, head: "deps/sdk", isFork: true, headRepo: "ozan/storefront", headOwner: "ozan", headUrl: w.fork, maintainerCanModify: false }) } })
  const s = await createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 109 } })
  expect(s).toMatchObject({ session_branch: "ozan/deps/sdk", source_kind: "pr", pr_number: 109 })
  expect(s.push_remote).toBeUndefined()
  expect(() => git(w.dir, "config", "branch.ozan/deps/sdk.pushRemote")).toThrow()
}, REAL_GIT_MS)

test("a local branch with commits the PR doesn't have: refused with how many; then the PR as it is (mine kept as -backup) or mine", async () => {
  const w = world()
  git(w.dir, "switch", "-q", "-c", "feat/login", "origin/feat/login")
  commit(w.dir, "mine1.txt", "mine 1"); const mine = commit(w.dir, "mine2.txt", "mine 2")
  git(w.dir, "switch", "-q", "main")
  const { deps } = setup({ "#123": { status: "found", pr: pr({ number: 123, head: "feat/login", localAhead: 2 }) } })
  expect(await refusal(createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 123 } }))).toEqual({ code: "local_ahead", branch: "feat/login", ahead: 2 })
  const theirs = await createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 123, divergedPolicy: "pr" } })
  expect(theirs.session_branch).toBe("feat/login")
  expect(git(w.dir, "rev-parse", "feat/login")).toBe(w.loginTip)
  expect(git(w.dir, "rev-parse", "feat/login-backup")).toBe(mine)
}, REAL_GIT_MS)

test("keep my local branch: the session works on it as it is", async () => {
  const w = world()
  git(w.dir, "switch", "-q", "-c", "feat/login", "origin/feat/login")
  const mine = commit(w.dir, "mine.txt", "mine")
  git(w.dir, "switch", "-q", "main")
  const { deps } = setup({ "#123": { status: "found", pr: pr({ number: 123, head: "feat/login", localAhead: 1 }) } })
  const s = await createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 123, divergedPolicy: "mine" } })
  expect(s.session_branch).toBe("feat/login")
  expect(git(w.dir, "rev-parse", "feat/login")).toBe(mine)
}, REAL_GIT_MS)

test("the PR's branch checked out in the folder is held; a PR that isn't open, or none, is refused", async () => {
  const w = world()
  git(w.dir, "switch", "-q", "-c", "feat/login", "origin/feat/login")
  const { deps } = setup({
    "#123": { status: "found", pr: pr({ number: 123, head: "feat/login" }) },
    "#5": { status: "not_open", forge: "github", number: 5, state: "merged" },
  })
  expect(await refusal(createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 123 } }))).toEqual({ code: "held_elsewhere", branch: "feat/login", holder: { kind: "folder" } })
  expect(await refusal(createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 5 } }))).toEqual({ code: "pr_unavailable", status: "not_open", state: "merged" })
  expect(await refusal(createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 77 } }))).toEqual({ code: "pr_unavailable", status: "none" })
}, REAL_GIT_MS)

test("a fork that can't be fetched is read from origin's own PR ref instead, to run and review, with nowhere to push", async () => {
  const w = world()
  execFileSync("git", ["-C", w.fork, "push", "-q", w.origin, "deps/sdk:refs/pull/121/head"])
  const { deps } = setup({ "#121": { status: "found", pr: pr({ number: 121, head: "deps/sdk", isFork: true, headRepo: "ozan/storefront", headOwner: "ozan", headUrl: join(w.dir, "no-such-fork.git"), maintainerCanModify: true }) } })
  const s = await createAsleep(deps, { workdir: w.dir, agent: AgentKind.Claude, source: { kind: "pr", number: 121 } })
  expect(s).toMatchObject({ session_branch: "ozan/deps/sdk", source_kind: "pr", pr_number: 121 })
  expect(s.push_remote).toBeUndefined()
  expect(git(w.dir, "rev-parse", "ozan/deps/sdk")).toBe(w.sdkTip)
}, REAL_GIT_MS)
