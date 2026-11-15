// #72 v5: git as developers in a team (and alone) use it, against real git and real "remotes" (bare repos).
// Written before the build, from the approved spec: plain git semantics (a branch's own upstream for pull / push),
// the base only in its explicit actions, and the GitHub Pull Requests extension's create flow.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync, chmodSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createSessionBranch, createWorktree } from "../worktree/manager"
import { computeLiteStatus } from "../worktree/lite-status"
import { pullBranch, pushBranch, publishBranch, remoteStatus } from "./remote"
import { createPr, prDefaultTarget, prPreflight, publishNew, type Exec } from "./forge"

/** Real git, many spawns: slow on Windows CI. */
const REAL_GIT_MS = 30_000

const git = (d: string, ...a: string[]) => execFileSync("git", ["-C", d, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
const tryGit = (d: string, ...a: string[]) => { try { return git(d, ...a) } catch { return null } }
const tmp = (p: string) => mkdtempSync(join(tmpdir(), p))
const commit = (d: string, f: string, text: string, msg: string) => { writeFileSync(join(d, f), text); git(d, "add", "."); git(d, "commit", "-qm", msg); return git(d, "rev-parse", "HEAD") }
function ident(d: string, name = "Ada") { git(d, "config", "user.email", `${name.toLowerCase()}@t`); git(d, "config", "user.name", name); git(d, "config", "core.autocrlf", "false") }

/** A team's repo: a bare "GitHub" with main, and a developer's clone of it. */
function team() {
  const remote = tmp("mux-team-remote-"); execFileSync("git", ["init", "-q", "--bare", "-b", "main", remote])
  const seed = tmp("mux-team-seed-"); execFileSync("git", ["init", "-q", "-b", "main", seed]); ident(seed, "Lead")
  commit(seed, "README.md", "team\n", "Initial commit"); git(seed, "remote", "add", "origin", remote); git(seed, "push", "-q", "-u", "origin", "main")
  const me = tmp("mux-team-me-"); execFileSync("git", ["clone", "-q", remote, me]); ident(me)
  return { remote, me }
}
/** A teammate's own clone, to push from. */
function teammate(remote: string) { const d = tmp("mux-team-mate-"); execFileSync("git", ["clone", "-q", remote, d]); ident(d, "Grace"); return d }
/** A fake gh / glab for the create calls (the network part); git itself stays real. */
function fakeGh(answer: (args: string[]) => { ok: boolean; out: string }): Exec & { calls: string[][] } {
  const calls: string[][] = []
  const real: Exec = async (cmd, args, cwd) => {
    try { return { ok: true, out: execFileSync(cmd, args, { cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim() } }
    catch (e: any) { return { ok: false, out: String(e.stderr || e.message) } }
  }
  const f = (async (cmd: string, args: string[], cwd: string) => {
    if (cmd === "gh" || cmd === "glab") { calls.push(args); return answer(args) }
    return real(cmd, args, cwd)
  }) as Exec & { calls: string[][] }
  f.calls = calls
  return f
}

// ── a chat's branch ──

test("a new chat branch has no upstream until it is published, and remembers its base in git config", async () => {
  const { me } = team()
  git(me, "fetch", "-q", "origin")
  await createSessionBranch(me, "mux/search", "origin/main")
  expect(tryGit(me, "rev-parse", "--abbrev-ref", "mux/search@{upstream}")).toBeNull()
  expect(git(me, "config", "branch.mux/search.base")).toBe("main")
  const h = await createWorktree({ repoRoot: me, baseBranch: "main", sessionName: "filters" })
  expect(tryGit(h.worktreeDir, "rev-parse", "--abbrev-ref", "@{upstream}")).toBeNull()
  expect(git(me, "config", `branch.${h.sessionBranch}.base`)).toBe("main")
  // Its footer: nothing to pull or push, just "Publish".
  const lite = await computeLiteStatus({ workdir: h.worktreeDir, repo_root: me, base_branch: "main", session_branch: h.sessionBranch })
  expect(lite?.sync).toBeNull()
}, REAL_GIT_MS)

test("Publish puts only the current branch on the remote and makes it the upstream", async () => {
  const { me, remote } = team()
  git(me, "switch", "-q", "-c", "feature/search"); commit(me, "s.txt", "s\n", "Add search")
  expect(publishBranch(me).status).toBe("pushed")
  expect(git(me, "rev-parse", "--abbrev-ref", "@{upstream}")).toBe("origin/feature/search")
  expect(git(remote, "branch", "--format=%(refname:short)").split("\n").sort()).toEqual(["feature/search", "main"])
}, REAL_GIT_MS)

// ── a branch others push to ──

test("a branch a teammate pushed to: behind shows against its own upstream, Pull brings theirs in, then Push", async () => {
  const { me, remote } = team()
  git(me, "switch", "-q", "-c", "feature/pay"); commit(me, "p.txt", "1\n", "Start payments"); publishBranch(me)
  const grace = teammate(remote); git(grace, "switch", "-q", "feature/pay"); commit(grace, "g.txt", "g\n", "Grace's part"); git(grace, "push", "-q")
  commit(me, "p.txt", "2\n", "My part")
  git(me, "fetch", "-q", "origin")
  expect(remoteStatus(me)).toMatchObject({ upstream: "origin/feature/pay", ahead: 1, behind: 1 })
  expect(pullBranch(me).status).toBe("clean")
  expect(pushBranch(me).status).toBe("pushed")
  expect(remoteStatus(me)).toMatchObject({ ahead: 0, behind: 0 })
}, REAL_GIT_MS)

test("a chat's footer never counts the base: main moving on is not 'pull'", async () => {
  const { me, remote } = team()
  const h = await createWorktree({ repoRoot: me, baseBranch: "main", sessionName: "notes" })
  commit(h.worktreeDir, "n.txt", "n\n", "Notes"); publishBranch(h.worktreeDir)
  const grace = teammate(remote); commit(grace, "m.txt", "m\n", "Main moves on"); git(grace, "push", "-q")
  git(me, "fetch", "-q", "origin")
  const lite = await computeLiteStatus({ workdir: h.worktreeDir, repo_root: me, base_branch: "main", session_branch: h.sessionBranch })
  expect(lite?.sync).toMatchObject({ ahead: 0, behind: 0, upstream: `origin/${h.sessionBranch}` })
}, REAL_GIT_MS)

// ── a protected base ──

test("a push the remote refuses (a protected branch) says so, with the remote's own words", async () => {
  const { me, remote } = team()
  const hook = join(remote, "hooks", "pre-receive")
  writeFileSync(hook, "#!/bin/sh\necho 'GH006: Protected branch update failed for refs/heads/main.' >&2\nexit 1\n"); chmodSync(hook, 0o755)
  commit(me, "x.txt", "x\n", "Straight to main")
  const r = pushBranch(me)
  expect(r.status).not.toBe("pushed")
  expect("message" in r ? r.message : "").toContain("Protected branch update failed")
}, REAL_GIT_MS)

// ── several remotes, and a fork ──

test("several remotes: the status lists them, and Publish goes to the one picked", async () => {
  const { me } = team()
  const mine = tmp("mux-team-fork-"); execFileSync("git", ["init", "-q", "--bare", "-b", "main", mine])
  git(me, "remote", "add", "fork", mine)
  git(me, "switch", "-q", "-c", "fix/typo"); commit(me, "t.txt", "t\n", "Fix a typo")
  expect(remoteStatus(me).remotes.sort()).toEqual(["fork", "origin"])
  expect(publishBranch(me, "fork").status).toBe("pushed")
  expect(git(me, "rev-parse", "--abbrev-ref", "@{upstream}")).toBe("fork/fix/typo")
}, REAL_GIT_MS)

test("a fork's PR branch (no origin at all): status, push and pull use the branch's own remote", async () => {
  const { remote } = team()
  const contributor = tmp("mux-team-contrib-"); execFileSync("git", ["clone", "-q", "-o", "contributor", remote, contributor]); ident(contributor, "Linus")
  git(contributor, "switch", "-q", "-c", "their-fix"); commit(contributor, "f.txt", "f\n", "Their fix"); git(contributor, "push", "-q", "-u", "contributor", "their-fix")
  commit(contributor, "f.txt", "f2\n", "Review follow-up")
  const s = remoteStatus(contributor)
  expect(s).toMatchObject({ hasRemote: true, upstream: "contributor/their-fix", ahead: 1 })
  expect(pushBranch(contributor).status).toBe("pushed")
  expect(pullBranch(contributor).status).toBe("up_to_date")
}, REAL_GIT_MS)

test("a branch whose upstream has another name is pushed to that name (as VS Code does), not refused", async () => {
  const { me, remote } = team()
  git(me, "switch", "-q", "-c", "local-name"); commit(me, "l.txt", "l\n", "Work"); git(me, "push", "-q", "origin", "local-name:remote-name")
  git(me, "branch", "--set-upstream-to=origin/remote-name")
  commit(me, "l.txt", "l2\n", "More")
  expect(pushBranch(me).status).toBe("pushed")
  expect(git(remote, "log", "-1", "--format=%s", "remote-name")).toBe("More")
}, REAL_GIT_MS)

// ── Create Pull Request (the GitHub Pull Requests extension's flow) ──

test("the PR's target is the branch's recorded base, else origin's default; never the branch itself", async () => {
  const { me } = team()
  git(me, "fetch", "-q", "origin")
  await createSessionBranch(me, "mux/report", "origin/main")
  git(me, "switch", "-q", "mux/report")
  expect(await prDefaultTarget(me, "mux/report")).toBe("main")
  git(me, "config", "--unset", "branch.mux/report.base")
  expect(await prDefaultTarget(me, "mux/report")).toBe("main")         // origin's default
  git(me, "switch", "-q", "main")
  expect(await prDefaultTarget(me, "main")).toBeNull()                 // main can't target itself
}, REAL_GIT_MS)

test("before Create: an unpublished branch asks to publish, unpushed commits ask to push (the extension's questions)", async () => {
  const { me } = team()
  git(me, "switch", "-q", "-c", "feature/x"); commit(me, "x.txt", "x\n", "X")
  expect(prPreflight(me)).toEqual({ ask: "publish" })
  publishBranch(me)
  expect(prPreflight(me)).toEqual({ ask: null })
  commit(me, "x.txt", "x2\n", "X2"); commit(me, "x.txt", "x3\n", "X3")
  expect(prPreflight(me)).toEqual({ ask: "push", count: 2 })
}, REAL_GIT_MS)

test("Publish to GitHub from a chat pushes only that branch: the base is not pushed with it", async () => {
  const solo = tmp("mux-solo-"); execFileSync("git", ["init", "-q", "-b", "main", solo]); ident(solo)
  commit(solo, "a.txt", "a\n", "Initial commit")
  git(solo, "switch", "-q", "-c", "mux/idea"); commit(solo, "b.txt", "b\n", "Idea")
  const bare = tmp("mux-solo-gh-"); execFileSync("git", ["init", "-q", "--bare", bare])
  // gh "creates" the repo; its URL is swapped for the local bare repo, so git's own add + push are real.
  const gh = fakeGh(() => ({ ok: true, out: "https://github.com/me/solo" }))
  const ex = (async (cmd: string, args: string[], cwd: string) =>
    gh(cmd, cmd === "git" && args[2] === "remote" && args[3] === "add" ? [...args.slice(0, 5), bare] : args, cwd)) as Exec
  const r = await publishNew(solo, { forge: "github", name: "solo", visibility: "private", branch: "mux/idea" }, ex)
  expect(r).toMatchObject({ ok: true })
  expect(git(bare, "branch", "--format=%(refname:short)")).toBe("mux/idea")
}, REAL_GIT_MS)

test("two branches with no history in common: Create's failure is said in plain words, gh's text kept", async () => {
  const { me } = team()
  git(me, "checkout", "-q", "--orphan", "fresh"); commit(me, "z.txt", "z\n", "Another first commit")
  git(me, "remote", "set-url", "origin", "https://github.com/acme/team.git")   // only gh (faked) is asked from here
  const gh = fakeGh(() => ({ ok: false, out: "pull request create failed: GraphQL: The fresh branch has no history in common with main (createPullRequest)" }))
  const r = await createPr(me, "fresh", { title: "t", body: "", target: "main", draft: false }, gh)
  expect(r).toMatchObject({ status: "unavailable", reason: "unrelated" })
  expect("message" in r ? r.message : "").toContain("no history in common")
}, REAL_GIT_MS)
