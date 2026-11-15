// #152: the new-session launcher reads and switches the PROJECT folder's branch through #72's branch service.
import { test, expect, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync, realpathSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { liveBranch } from "../../core/git/branches"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

const git = (cwd: string, ...a: string[]) => execFileSync("git", a, { cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()

/** Your folder on `main` with an origin; a session works in its own copy on fix/cart; feat/search is free. */
async function setup() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "mux-project-branches-")))
  const bare = join(root, "origin.git"), repo = join(root, "repo"), copy = join(root, "copy")
  execFileSync("git", ["init", "-q", "--bare", "-b", "main", bare])
  execFileSync("git", ["init", "-q", "-b", "main", repo])
  git(repo, "config", "user.email", "t@t"); git(repo, "config", "user.name", "t"); git(repo, "config", "core.autocrlf", "false")
  writeFileSync(join(repo, "a.txt"), "a\n"); git(repo, "add", "."); git(repo, "commit", "-qm", "base")
  git(repo, "remote", "add", "origin", bare)
  git(repo, "push", "-q", "-u", "origin", "main")
  git(repo, "push", "-q", "origin", "main:feat/login")
  git(repo, "fetch", "-q", "origin"); git(repo, "remote", "set-head", "origin", "main")
  git(repo, "branch", "feat/search")
  git(repo, "worktree", "add", "-q", "-b", "fix/cart", copy, "main")
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-project-branches-dev-")), "devices.json")
  const snap = [{ id: "s-1", name: "Cart total off by one", workdir: copy, session_branch: "fix/cart", repo_root: repo }]
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => snap as any, getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getSessionWorkdir: (id) => snap.find((s) => s.id === id)?.workdir,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return { root, repo, copy, token }
}
const call = (token: string, path: string, body?: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
  method: body === undefined ? "GET" : "POST",
  headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  body: body === undefined ? undefined : JSON.stringify(body),
})
const q = (dir: string) => `?dir=${encodeURIComponent(dir)}`

test("the folder's branches: the default, the folder's own, the one a session has open, and origin's", async () => {
  const { repo, token } = await setup()
  const res = await call(token, `/projects/branches${q(repo)}`)
  expect(res.status).toBe(200)
  const r = (await res.json()) as any
  expect(r.current).toBe("main")
  const by = Object.fromEntries(r.local.map((b: any) => [b.name, b]))
  expect(by["main"]).toMatchObject({ isDefault: true, upstream: "origin/main", ahead: 0, behind: 0, openIn: { kind: "this" } })
  expect(by["fix/cart"].openIn).toEqual({ kind: "session", id: "s-1", name: "Cart total off by one" })
  expect(by["feat/search"].openIn).toBeNull()
  expect(r.remote.find((b: any) => b.name === "origin/main")).toMatchObject({ isDefault: true, author: "t" })
  expect(r.remote.find((b: any) => b.name === "origin/feat/login")).toMatchObject({ isDefault: false, author: "t" })
  expect(by["feat/search"].at).toBeGreaterThan(0)
}, REAL_GIT_MS)

test("the folder's state: its branch, and whether it is clean", async () => {
  const { repo, token } = await setup()
  expect(await (await call(token, `/projects/folder-state${q(repo)}`)).json()).toEqual({ branch: "main", detachedSha: null, clean: true, changed: 0 })
  writeFileSync(join(repo, "a.txt"), "changed\n"); writeFileSync(join(repo, "new.txt"), "n\n")
  expect(await (await call(token, `/projects/folder-state${q(repo)}`)).json()).toEqual({ branch: "main", detachedSha: null, clean: false, changed: 2 })
}, REAL_GIT_MS)

test("a clean folder switches to a free branch, or to origin's as a tracking branch", async () => {
  const { repo, token } = await setup()
  expect(await (await call(token, "/projects/switch", { dir: repo, name: "feat/search" })).json()).toEqual({ status: "switched", branch: "feat/search" })
  expect(liveBranch(repo)).toBe("feat/search")
  expect(await (await call(token, "/projects/switch", { dir: repo, name: "origin/feat/login" })).json()).toEqual({ status: "switched", branch: "feat/login" })
  expect(git(repo, "rev-parse", "--abbrev-ref", "feat/login@{upstream}")).toBe("origin/feat/login")
}, REAL_GIT_MS)

test("a folder with changes is never switched, even when git could carry them", async () => {
  const { repo, token } = await setup()
  writeFileSync(join(repo, "new.txt"), "n\n")
  expect(await (await call(token, "/projects/switch", { dir: repo, name: "feat/search" })).json()).toEqual({ status: "dirty", changed: 1 })
  expect(liveBranch(repo)).toBe("main")
}, REAL_GIT_MS)

test("a branch a session has open is refused by git, and says which session has it", async () => {
  const { repo, token } = await setup()
  const r = (await (await call(token, "/projects/switch", { dir: repo, name: "fix/cart" })).json()) as any
  expect(r.status).toBe("checked_out_elsewhere")
  expect(r.openIn).toEqual({ kind: "session", id: "s-1", name: "Cart total off by one" })
  expect(liveBranch(repo)).toBe("main")
}, REAL_GIT_MS)

test("no folder, a missing one, a folder that is not a repository, or no branch name: a clear refusal", async () => {
  const { root, repo, token } = await setup()
  const plain = join(root, "plain"); mkdirSync(plain)
  expect((await call(token, "/projects/branches")).status).toBe(400)
  expect((await call(token, `/projects/branches${q(join(root, "gone"))}`)).status).toBe(400)
  const notRepo = await call(token, `/projects/folder-state${q(plain)}`)
  expect(notRepo.status).toBe(409)
  expect(await notRepo.json()).toEqual({ error: "not a git repository" })
  expect((await call(token, `/projects/branches${q(plain)}`)).status).toBe(409)
  expect((await call(token, "/projects/switch", { dir: plain, name: "main" })).status).toBe(409)
  expect((await call(token, "/projects/switch", { dir: repo })).status).toBe(400)
  expect((await call(token, "/projects/switch", { name: "main" })).status).toBe(400)
}, REAL_GIT_MS)

test("the Pull request tab: a folder without origin says so, and a ref that names no PR is refused", async () => {
  const { root, repo, token } = await setup()
  const lone = join(root, "lone")
  execFileSync("git", ["init", "-q", "-b", "main", lone])
  expect(await (await call(token, `/projects/prs${q(lone)}`)).json()).toEqual({ status: "unavailable", reason: "no_remote" })
  expect(await (await call(token, `/projects/pr${q(lone)}&ref=${encodeURIComponent("#12")}`)).json()).toEqual({ status: "unavailable", reason: "no_remote" })
  expect(await (await call(token, `/projects/pr${q(lone)}&ref=feat%2Fx`)).json()).toEqual({ status: "bad_ref" })
  // An origin that is a folder on this machine is no forge either.
  expect(await (await call(token, `/projects/prs${q(repo)}`)).json()).toEqual({ status: "unavailable", reason: "no_remote" })
  expect((await call(token, `/projects/prs?dir=${encodeURIComponent(join(root, "nope"))}`)).status).toBe(400)
}, REAL_GIT_MS)
