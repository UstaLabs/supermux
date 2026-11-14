// #72: a chat with its own copy can switch branch or make a new one; the list says where each branch is open.
import { test, expect, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, realpathSync, writeFileSync } from "fs"
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

/** A repo on `dev` (your folder), two chats with their own copies on mux/one and mux/two, and a free feature branch. */
async function setup() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "mux-branches-route-")))
  const repo = join(root, "repo"), one = join(root, "one"), two = join(root, "two")
  execFileSync("git", ["init", "-q", "-b", "dev", repo])
  git(repo, "config", "user.email", "t@t"); git(repo, "config", "user.name", "t"); git(repo, "config", "core.autocrlf", "false")
  writeFileSync(join(repo, "a.txt"), "a\n"); git(repo, "add", "."); git(repo, "commit", "-qm", "base")
  git(repo, "branch", "feature/payments")
  git(repo, "worktree", "add", "-q", "-b", "mux/one", one, "dev")
  git(repo, "worktree", "add", "-q", "-b", "mux/two", two, "dev")
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-branches-dev-")), "devices.json")
  const snap = [
    { id: "s-1", name: "One", workdir: one, session_branch: "mux/one", repo_root: repo },
    { id: "s-2", name: "Two", workdir: two, session_branch: "mux/two", repo_root: repo },
  ]
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => snap as any, getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getSessionWorkdir: (id) => snap.find((s) => s.id === id)?.workdir,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return { repo, one, two, token }
}
const call = (token: string, path: string, body?: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
  method: body === undefined ? "GET" : "POST",
  headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  body: body === undefined ? undefined : JSON.stringify(body),
})

test("the list names the chat or the folder that has each branch open", async () => {
  const { token } = await setup()
  const r = (await (await call(token, "/sessions/s-1/git/branches")).json()) as any
  expect(r.current).toBe("mux/one")
  const by = Object.fromEntries(r.local.map((b: any) => [b.name, b.openIn]))
  expect(by["mux/one"]).toEqual({ kind: "this" })
  expect(by["mux/two"]).toEqual({ kind: "chat", id: "s-2", name: "Two" })
  expect(by["dev"]).toEqual({ kind: "folder" })
  expect(by["feature/payments"]).toBeNull()
}, REAL_GIT_MS)

test("a chat with its own copy switches to a free branch, or makes a new one; the copy follows", async () => {
  const { one, token } = await setup()
  expect(await (await call(token, "/sessions/s-1/git/switch", { name: "feature/payments" })).json()).toEqual({ status: "switched", branch: "feature/payments" })
  expect(liveBranch(one)).toBe("feature/payments")
  expect(await (await call(token, "/sessions/s-1/git/switch", { name: "fix/search", create: true })).json()).toEqual({ status: "switched", branch: "fix/search" })
  expect(git(one, "symbolic-ref", "--short", "HEAD")).toBe("fix/search")
}, REAL_GIT_MS)

test("a branch open in another chat or in the folder is refused by git, and nothing moves", async () => {
  const { one, token } = await setup()
  const r = (await (await call(token, "/sessions/s-1/git/switch", { name: "mux/two" })).json()) as any
  expect(r.status).toBe("checked_out_elsewhere")
  expect(((await (await call(token, "/sessions/s-1/git/switch", { name: "dev" })).json()) as any).status).toBe("checked_out_elsewhere")
  expect(liveBranch(one)).toBe("mux/one")
}, REAL_GIT_MS)

test("liveBranch is null off a branch", async () => {
  const { one } = await setup()
  git(one, "checkout", "-q", "--detach")
  expect(liveBranch(one)).toBeNull()
}, REAL_GIT_MS)
