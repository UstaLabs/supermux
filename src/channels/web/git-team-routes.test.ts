// #72 v5: a chat's git, end to end through the routes the app calls, against real git and real "remotes" (bare
// repos), as a developer in a team meets it: a chat's copy made the way supermux makes it, a teammate pushing to the
// same branch, a conflict, the base moving on, several remotes, a refused push, Create Pull Request's questions.
import { test, expect, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { chmodSync, mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { createWorktree } from "../../core/worktree/manager"

/** Real git, many spawns: slow on Windows CI. */
const REAL_GIT_MS = 60_000

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

const git = (cwd: string, ...args: string[]) => execFileSync("git", args, { cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
const tmp = (p: string) => mkdtempSync(join(tmpdir(), p))
const write = (d: string, f: string, t: string) => writeFileSync(join(d, f), t)
function ident(d: string, name: string) { git(d, "config", "user.email", `${name.toLowerCase()}@t`); git(d, "config", "user.name", name); git(d, "config", "core.autocrlf", "false") }

/** A team's bare "GitHub" with main, my clone of it, a chat's copy of it made as supermux makes one, and the broker's routes. */
async function team() {
  const origin = tmp("mux-tr-origin-"); execFileSync("git", ["init", "-q", "--bare", "-b", "main", origin])
  const seed = tmp("mux-tr-seed-"); execFileSync("git", ["init", "-q", "-b", "main", seed]); ident(seed, "Lead")
  write(seed, "notes.md", "line one\nline two\n"); git(seed, "add", "."); git(seed, "commit", "-qm", "Initial commit")
  git(seed, "remote", "add", "origin", origin); git(seed, "push", "-q", "-u", "origin", "main")
  const repo = tmp("mux-tr-me-"); execFileSync("git", ["clone", "-q", origin, repo]); ident(repo, "Ada")
  const chat = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "search box" })
  ident(chat.worktreeDir, "Ada")
  const devicesFile = join(tmp("mux-tr-dev-"), "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getWorkspaceWorkdir: (id) => (id === "ws-1" ? chat.worktreeDir : undefined),
    getSessionWorkdir: (id) => (id === "s-1" ? chat.worktreeDir : undefined),
    getSessionGitBase: (id) => (id === "s-1" ? { repoRoot: repo, base: "main" } : undefined),
    getSessionBaseCommits: () => ({}), getSessionCreatedAt: () => undefined,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  const call = async (method: string, path: string, body?: unknown) => {
    const r = await fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
      method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body),
    })
    return { status: r.status, body: await r.json().catch(() => null) as any }
  }
  /** A teammate's clone, to push from. */
  const teammate = () => { const d = tmp("mux-tr-mate-"); execFileSync("git", ["clone", "-q", origin, d]); ident(d, "Grace"); return d }
  return { origin, repo, wt: chat.worktreeDir, branch: chat.sessionBranch, call, teammate }
}

test("a chat's copy: no upstream until Publish, Publish only the branch, then pull / push are its own", async () => {
  const t = await team()
  write(t.wt, "search.ts", "export const search = 1\n"); git(t.wt, "add", "."); git(t.wt, "commit", "-qm", "Add search")
  let st = (await t.call("GET", "/workspaces/ws-1/git/status")).body
  expect(st).toMatchObject({ hasRemote: true, remotes: ["origin"], branch: t.branch, upstream: null })
  // The base moving on is never the chat's pull: still "not published", nothing to pull.
  const grace = t.teammate(); write(grace, "x.md", "x\n"); git(grace, "add", "."); git(grace, "commit", "-qm", "Main moves"); git(grace, "push", "-q")
  st = (await t.call("GET", "/workspaces/ws-1/git/status")).body
  expect(st.upstream).toBeNull()
  // Publish: only this branch goes up, and it is the upstream from now on.
  expect((await t.call("POST", "/workspaces/ws-1/git/publish", {})).body.status).toBe("pushed")
  expect(git(t.origin, "branch", "--format=%(refname:short)").split("\n").sort()).toEqual(["main", t.branch].sort())
  st = (await t.call("GET", "/workspaces/ws-1/git/status")).body
  expect(st).toMatchObject({ upstream: `origin/${t.branch}`, ahead: 0, behind: 0 })
  // The base, separately: its own line's numbers (the chat's commits, the base's new ones).
  const h = (await t.call("GET", "/sessions/s-1/git/history?repo=")).body
  expect(h).toMatchObject({ base: "main" })
  expect(h.chat.map((c: any) => c.subject)).toEqual(["Add search"])
}, REAL_GIT_MS)

test("a teammate pushes to the same branch: behind 1, Pull brings it in, Push sends mine", async () => {
  const t = await team()
  write(t.wt, "a.ts", "1\n"); git(t.wt, "add", "."); git(t.wt, "commit", "-qm", "Mine 1")
  await t.call("POST", "/workspaces/ws-1/git/publish", {})
  const grace = t.teammate(); git(grace, "switch", "-q", t.branch); write(grace, "g.ts", "g\n"); git(grace, "add", "."); git(grace, "commit", "-qm", "Grace's part"); git(grace, "push", "-q")
  write(t.wt, "a.ts", "2\n"); git(t.wt, "commit", "-qam", "Mine 2")
  expect((await t.call("POST", "/workspaces/ws-1/git/fetch", {})).body.ok).toBe(true)
  expect((await t.call("GET", "/workspaces/ws-1/git/status")).body).toMatchObject({ ahead: 1, behind: 1 })
  expect((await t.call("POST", "/workspaces/ws-1/git/pull", {})).body.status).toBe("clean")
  expect((await t.call("POST", "/workspaces/ws-1/git/push", {})).body.status).toBe("pushed")
  expect((await t.call("GET", "/workspaces/ws-1/git/status")).body).toMatchObject({ ahead: 0, behind: 0 })
}, REAL_GIT_MS)

test("a conflict on Pull: the file is listed, mine is kept, Continue commits, Push sends it", async () => {
  const t = await team()
  write(t.wt, "notes.md", "line one\nmine\n"); git(t.wt, "commit", "-qam", "Mine")
  await t.call("POST", "/workspaces/ws-1/git/publish", {})
  const grace = t.teammate(); git(grace, "switch", "-q", t.branch); write(grace, "notes.md", "line one\ntheirs\n"); git(grace, "commit", "-qam", "Theirs"); git(grace, "push", "-q")
  write(t.wt, "notes.md", "line one\nmine again\n"); git(t.wt, "commit", "-qam", "Mine again")
  const pull = (await t.call("POST", "/workspaces/ws-1/git/pull", {})).body
  expect(pull).toMatchObject({ status: "conflict", files: ["notes.md"] })
  const changes = (await t.call("GET", "/workspaces/ws-1/changes?base=head")).body
  expect(changes.repos[0].merging).toBe(true)
  expect(changes.repos[0].files.find((f: any) => f.path === "notes.md").staged).toBe("conflict")
  expect((await t.call("POST", "/workspaces/ws-1/git/resolve", { repo: "", path: "notes.md", side: "mine" })).body.ok).toBe(true)
  expect((await t.call("POST", "/workspaces/ws-1/git/commit", { repo: "", message: "" })).body.ok).toBe(true)
  expect((await t.call("POST", "/workspaces/ws-1/git/push", {})).body.status).toBe("pushed")
}, REAL_GIT_MS)

test("Get latest from the base: main's new commits come into the chat's branch; Undo puts it back", async () => {
  const t = await team()
  write(t.wt, "a.ts", "1\n"); git(t.wt, "add", "."); git(t.wt, "commit", "-qm", "Mine")
  const grace = t.teammate(); write(grace, "m.md", "m\n"); git(grace, "add", "."); git(grace, "commit", "-qm", "Main moves on"); git(grace, "push", "-q")
  const r = (await t.call("POST", "/sessions/s-1/git/latest", {})).body
  expect(r).toMatchObject({ status: "clean", commits: 1 })
  expect(git(t.wt, "log", "--format=%s", "-3")).toContain("Main moves on")
  expect((await t.call("POST", "/sessions/s-1/git/latest/undo", { before: r.before, after: r.after })).body.ok).toBe(true)
  expect(git(t.wt, "log", "--format=%s", "-3")).not.toContain("Main moves on")
}, REAL_GIT_MS)

test("several remotes: the status lists them and Publish goes to the one picked", async () => {
  const t = await team()
  const fork = tmp("mux-tr-fork-"); execFileSync("git", ["init", "-q", "--bare", "-b", "main", fork])
  git(t.wt, "remote", "add", "fork", fork)
  write(t.wt, "f.ts", "f\n"); git(t.wt, "add", "."); git(t.wt, "commit", "-qm", "Fix")
  expect((await t.call("GET", "/workspaces/ws-1/git/status")).body.remotes.sort()).toEqual(["fork", "origin"])
  expect((await t.call("POST", "/workspaces/ws-1/git/publish", { remote: "fork" })).body.status).toBe("pushed")
  expect((await t.call("GET", "/workspaces/ws-1/git/status")).body.upstream).toBe(`fork/${t.branch}`)
  expect(git(t.origin, "branch", "--format=%(refname:short)")).toBe("main")        // origin got nothing
}, REAL_GIT_MS)

test("a push the remote refuses (a protected branch): the status says so, with the remote's words", async () => {
  const t = await team()
  write(t.wt, "a.ts", "1\n"); git(t.wt, "add", "."); git(t.wt, "commit", "-qm", "Mine")
  await t.call("POST", "/workspaces/ws-1/git/publish", {})
  const hook = join(t.origin, "hooks", "pre-receive")
  writeFileSync(hook, "#!/bin/sh\necho 'GH006: Protected branch update failed.' >&2\nexit 1\n"); chmodSync(hook, 0o755)
  write(t.wt, "a.ts", "2\n"); git(t.wt, "commit", "-qam", "More")
  const r = (await t.call("POST", "/workspaces/ws-1/git/push", {})).body
  expect(r.status).not.toBe("pushed")
  expect(JSON.stringify(r)).toContain("Protected branch update failed")
}, REAL_GIT_MS)

test("Create Pull Request: the default target is the chat's base, and the extension's questions come first", async () => {
  const t = await team()
  write(t.wt, "a.ts", "1\n"); git(t.wt, "add", "."); git(t.wt, "commit", "-qm", "Mine")
  // The form's default target and its two checks.
  expect((await t.call("GET", "/workspaces/ws-1/git/pr/base")).body).toEqual({ target: "main", onRemote: true, ahead: 1 })
  expect((await t.call("GET", "/workspaces/ws-1/git/pr/base?target=nope")).body).toMatchObject({ target: "nope", onRemote: false })
  const pr = { title: "Add search", target: "main" }
  // Not published: asked first; nothing pushed unasked.
  expect((await t.call("POST", "/workspaces/ws-1/git/pr", pr))).toEqual({ status: 409, body: { status: "ask", ask: "publish" } })
  expect(git(t.origin, "branch", "--format=%(refname:short)")).toBe("main")
  // Published, then two more commits: asked to push them.
  await t.call("POST", "/workspaces/ws-1/git/publish", {})
  write(t.wt, "a.ts", "2\n"); git(t.wt, "commit", "-qam", "Two"); write(t.wt, "a.ts", "3\n"); git(t.wt, "commit", "-qam", "Three")
  expect((await t.call("POST", "/workspaces/ws-1/git/pr", pr)).body).toEqual({ status: "ask", ask: "push", count: 2 })
  // "Push Commits": they go up first (the create itself then needs GitHub, which a bare repo isn't).
  const pushed = (await t.call("POST", "/workspaces/ws-1/git/pr", { ...pr, push: true })).body
  expect(pushed.status).not.toBe("ask")
  expect(git(t.origin, "log", "-1", "--format=%s", t.branch)).toBe("Three")
}, REAL_GIT_MS)
