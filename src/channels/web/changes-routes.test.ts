import { test, expect, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdirSync, mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

function git(cwd: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
}

async function setup(reviewList: () => any[] = () => []) {
  const dir = mkdtempSync(join(tmpdir(), "mux-changes-route-"))
  git(dir, "init", "-q", "-b", "main")
  git(dir, "config", "user.email", "t@t")
  git(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "a.txt"), "one\n")
  git(dir, "add", ".")
  git(dir, "commit", "-qm", "base")
  const base = git(dir, "rev-parse", "HEAD")
  writeFileSync(join(dir, "a.txt"), "two\n")
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-changes-dev-")), "devices.json")
  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getWorkspaceWorkdir: (id) => (id === "ws-1" ? dir : undefined),
    getWorkspaceDiffBase: () => ({ baseCommits: { "": base }, createdAt: undefined }),
    getSessionWorkdir: (id) => (id === "s-1" ? dir : undefined),
    getSessionBaseCommits: () => ({ "": base }),
    getSessionCreatedAt: () => undefined,
    reviewList,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return { dir, base, token }
}

async function get(token: string, path: string, extra: Record<string, string> = {}) {
  return fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, { headers: { authorization: `Bearer ${token}`, ...extra } })
}

test("workspace changes lists the edited file with its base blob and no text", async () => {
  const { dir, base, token } = await setup()
  const res = await get(token, "/workspaces/ws-1/changes")
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.repos[0].baseSha).toBe(base)
  expect(body.repos[0].files[0]).toMatchObject({ path: "a.txt", status: "modified", added: 1, removed: 1, baseBlob: git(dir, "rev-parse", `${base}:a.txt`) })
  expect(body.repos[0].files[0].diff).toBeUndefined()
  expect(body.comments).toEqual([])
})

test("session changes carries the session's review comments", async () => {
  const { token } = await setup()
  const res = await get(token, "/sessions/s-1/changes?base=head")
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.repos[0].files[0].path).toBe("a.txt")
  expect(body.comments).toEqual([])
})

test("blob returns immutable text; bad repo, bad sha and unknown ids are rejected", async () => {
  const { dir, base, token } = await setup()
  const sha = git(dir, "rev-parse", `${base}:a.txt`)
  const ok = await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${sha}`)
  expect(ok.status).toBe(200)
  expect(await ok.text()).toBe("one\n")
  expect(ok.headers.get("etag")).toBe(`"${sha}"`)
  expect(ok.headers.get("cache-control")).toContain("immutable")
  expect((await get(token, `/workspaces/ws-1/changes/blob?repo=nope&sha=${sha}`)).status).toBe(404)
  expect((await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=HEAD`)).status).toBe(400)
  expect((await get(token, `/workspaces/ws-2/changes/blob?repo=&sha=${sha}`)).status).toBe(404)
  expect((await get(token, `/sessions/s-1/changes/blob?repo=&sha=${sha}`)).status).toBe(200)
})

test("a blob over the limit is 413 with its size", async () => {
  const { dir, token } = await setup()
  writeFileSync(join(dir, "big.txt"), "x".repeat(1024 * 1024 + 1))
  git(dir, "add", "big.txt")
  git(dir, "commit", "-qm", "big")
  const sha = git(dir, "rev-parse", "HEAD:big.txt")
  const res = await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${sha}`)
  expect(res.status).toBe(413)
  expect(await res.json()).toEqual({ error: "TOO_LARGE", size: 1024 * 1024 + 1 })
  expect((await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${sha}&force=1`)).status).toBe(200)
})

test("a committed blob containing a NUL byte is 415", async () => {
  const { dir, token } = await setup()
  writeFileSync(join(dir, "bin.dat"), Buffer.from([1, 0, 2, 3]))
  git(dir, "add", "bin.dat")
  git(dir, "commit", "-qm", "bin")
  const sha = git(dir, "rev-parse", "HEAD:bin.dat")
  const res = await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${sha}`)
  expect(res.status).toBe(415)
  expect(await res.json()).toEqual({ error: "BINARY" })
})

test("session changes re-anchors review comments", async () => {
  const comment = {
    id: "c1", sessionId: "s-1", repo: "", path: "a.txt", side: "RIGHT" as const,
    anchorLine: 3, anchorContext: "two", body: "hm", author: "user" as const,
    createdAt: "2026-01-01T00:00:00Z", status: "open" as const,
  }
  const { token } = await setup(() => [comment, { ...comment, id: "c2", anchorContext: "gone" }])
  const body = await (await get(token, "/sessions/s-1/changes?base=head")).json() as any
  const byId = Object.fromEntries(body.comments.map((c: any) => [c.id, c]))
  expect(byId.c1).toMatchObject({ currentLine: 1, outdated: false })
  expect(byId.c2).toMatchObject({ currentLine: null, outdated: true })
})

test("unknown workspace and session on /changes are 404", async () => {
  const { token } = await setup()
  expect((await get(token, "/workspaces/nope/changes")).status).toBe(404)
  expect((await get(token, "/sessions/nope/changes")).status).toBe(404)
})

test("an all-zero sha is 404", async () => {
  const { token } = await setup()
  expect((await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${"0".repeat(40)}`)).status).toBe(404)
})

test("a nested repo is listed with its repo path and its blob loads", async () => {
  const { dir, token } = await setup()
  const sub = join(dir, "sub")
  mkdirSync(sub)
  git(sub, "init", "-q", "-b", "main")
  git(sub, "config", "user.email", "t@t")
  git(sub, "config", "user.name", "t")
  writeFileSync(join(sub, "s.txt"), "old\n")
  git(sub, "add", ".")
  git(sub, "commit", "-qm", "sub base")
  const subBase = git(sub, "rev-parse", "HEAD")
  writeFileSync(join(sub, "s.txt"), "new\n")
  // Base commits are resolved per repo, so pin the sub repo's base via session-start-less HEAD.
  const list = await (await get(token, "/workspaces/ws-1/changes?base=head")).json() as any
  const r = list.repos.find((x: any) => x.repo === "sub")
  expect(r).toBeTruthy()
  expect(r.baseSha).toBe(subBase)
  expect(r.files[0]).toMatchObject({ path: "s.txt", status: "modified" })
  const res = await get(token, `/workspaces/ws-1/changes/blob?repo=sub&sha=${r.files[0].baseBlob}`)
  expect(res.status).toBe(200)
  expect(await res.text()).toBe("old\n")
}, 30_000)   // two repos of real git: slow on Windows CI

test("a matching If-None-Match is 304 with the cache headers and no body", async () => {
  const { dir, base, token } = await setup()
  const sha = git(dir, "rev-parse", `${base}:a.txt`)
  const res = await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${sha}`, { "if-none-match": `"${sha}"` })
  expect(res.status).toBe(304)
  expect(res.headers.get("etag")).toBe(`"${sha}"`)
  expect(res.headers.get("cache-control")).toContain("immutable")
  expect(await res.text()).toBe("")
})

test("a repo created after the cache warmed is found by a rescan on miss", async () => {
  const { dir, base, token } = await setup()
  const warm = git(dir, "rev-parse", `${base}:a.txt`)
  expect((await get(token, `/workspaces/ws-1/changes/blob?repo=&sha=${warm}`)).status).toBe(200)
  const sub = join(dir, "late")
  mkdirSync(sub)
  git(sub, "init", "-q", "-b", "main")
  git(sub, "config", "user.email", "t@t")
  git(sub, "config", "user.name", "t")
  writeFileSync(join(sub, "l.txt"), "late\n")
  git(sub, "add", ".")
  git(sub, "commit", "-qm", "l")
  const sha = git(sub, "rev-parse", "HEAD:l.txt")
  const res = await get(token, `/workspaces/ws-1/changes/blob?repo=late&sha=${sha}`)
  expect(res.status).toBe(200)
  expect(await res.text()).toBe("late\n")
})

// #72: what goes into the next commit.
async function post(token: string, path: string, body: unknown) {
  return fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body),
  })
}

test("a changed file says it is not in the next commit, then is put in and taken out again", async () => {
  const { dir, token } = await setup()
  let files = ((await (await get(token, "/workspaces/ws-1/changes")).json()) as any).repos[0].files
  expect(files[0]).toMatchObject({ path: "a.txt", staged: "none" })
  expect((await post(token, "/workspaces/ws-1/git/stage", { repo: "", paths: ["a.txt"], staged: true })).status).toBe(200)
  files = ((await (await get(token, "/workspaces/ws-1/changes")).json()) as any).repos[0].files
  expect(files[0].staged).toBe("all")
  expect(git(dir, "diff", "--cached", "--name-only")).toBe("a.txt")
  expect((await post(token, "/sessions/s-1/git/stage", { repo: "", paths: ["a.txt"], staged: false })).status).toBe(200)
  expect(git(dir, "diff", "--cached", "--name-only")).toBe("")
})

test("staging refuses paths outside the repo, unknown repos and bad bodies", async () => {
  const { token } = await setup()
  expect((await post(token, "/workspaces/ws-1/git/stage", { repo: "", paths: ["../x"], staged: true })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/stage", { repo: "", paths: ["/etc/passwd"], staged: true })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/stage", { repo: "", paths: [], staged: true })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/stage", { repo: "", paths: ["a.txt"] })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/stage", { repo: "nope", paths: ["a.txt"], staged: true })).status).toBe(404)
  expect((await post(token, "/workspaces/ws-x/git/stage", { repo: "", paths: ["a.txt"], staged: true })).status).toBe(404)
})

test("commit with nothing in the next commit says so; all commits everything; undo brings it back", async () => {
  const { dir, token } = await setup()
  const none = await post(token, "/workspaces/ws-1/git/commit", { repo: "" })
  expect(none.status).toBe(409)
  expect(await none.json()).toEqual({ ok: false, reason: "nothing" })
  const all = await post(token, "/workspaces/ws-1/git/commit", { repo: "", all: true, message: "" })
  expect(all.status).toBe(200)
  expect(await all.json()).toMatchObject({ ok: true, subject: "Update a.txt" })
  expect(git(dir, "status", "--porcelain")).toBe("")
  const undo = await post(token, "/sessions/s-1/git/uncommit", { repo: "" })
  expect(await undo.json()).toEqual({ ok: true, subject: "Update a.txt" })
  expect(git(dir, "diff", "--cached", "--name-only")).toBe("a.txt")
})

test("commit refuses a bad message and an unknown repo", async () => {
  const { token } = await setup()
  expect((await post(token, "/workspaces/ws-1/git/commit", { repo: "", message: 5 })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/commit", { repo: "nope", all: true })).status).toBe(404)
  expect((await post(token, "/workspaces/ws-1/git/nope", { repo: "" })).status).not.toBe(200)
})

test("discard throws a file's unstaged edit away, or every one with all; staged edits stay", async () => {
  const { dir, token } = await setup()
  writeFileSync(join(dir, "n.txt"), "new\n")
  expect((await post(token, "/workspaces/ws-1/git/discard", { repo: "", paths: ["a.txt"] })).status).toBe(200)
  expect(git(dir, "status", "--porcelain")).toBe("?? n.txt")
  writeFileSync(join(dir, "a.txt"), "staged\n"); git(dir, "add", "a.txt"); writeFileSync(join(dir, "a.txt"), "staged\nmore\n")
  const all = await post(token, "/sessions/s-1/git/discard", { repo: "", all: true })
  expect(all.status).toBe(200)
  expect(((await all.json()) as any).discarded.sort()).toEqual(["a.txt", "n.txt"])
  expect(git(dir, "status", "--porcelain")).toBe("M  a.txt")
})

test("discard refuses paths outside the repo, no paths, and both at once", async () => {
  const { token } = await setup()
  expect((await post(token, "/workspaces/ws-1/git/discard", { repo: "", paths: ["../x"] })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/discard", { repo: "", paths: [] })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/discard", { repo: "" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/discard", { repo: "", paths: ["a.txt"], all: true })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/discard", { repo: "nope", all: true })).status).toBe(404)
})

test("undo get latest moves the branch back only while it is still where get latest left it", async () => {
  const { dir, base, token } = await setup()
  git(dir, "commit", "-qam", "came in")
  const after = git(dir, "rev-parse", "HEAD")
  expect((await post(token, "/sessions/s-1/git/latest/undo", { before: "nope", after })).status).toBe(400)
  expect((await post(token, "/sessions/s-x/git/latest/undo", { before: base, after })).status).toBe(404)
  expect((await post(token, "/sessions/s-1/git/latest/undo", { before: base, after: base })).status).toBe(409)
  const r = await post(token, "/sessions/s-1/git/latest/undo", { before: base, after })
  expect(r.status).toBe(200)
  expect(git(dir, "rev-parse", "HEAD")).toBe(base)
})

test("a chat in a folder (workspace) has status, publish, push, fetch and pull like a chat with its own copy", async () => {
  const { dir, token } = await setup()
  const origin = mkdtempSync(join(tmpdir(), "mux-origin-"))
  git(origin, "init", "-q", "--bare")
  git(dir, "remote", "add", "origin", origin)
  git(dir, "commit", "-qam", "local work")
  let st = (await (await get(token, "/workspaces/ws-1/git/status")).json()) as any
  expect(st).toMatchObject({ isRepo: true, hasRemote: true, branch: "main", upstream: null })
  expect(((await (await post(token, "/workspaces/ws-1/git/publish", {})).json()) as any).status).toBe("pushed")
  st = (await (await get(token, "/workspaces/ws-1/git/status")).json()) as any
  expect(st).toMatchObject({ upstream: "origin/main", ahead: 0, behind: 0 })
  writeFileSync(join(dir, "a.txt"), "three\n"); git(dir, "commit", "-qam", "more")
  expect(((await (await get(token, "/sessions/s-1/git/status")).json()) as any).ahead).toBe(1)
  expect(((await (await post(token, "/workspaces/ws-1/git/push", {})).json()) as any).status).toBe("pushed")
  expect(((await (await post(token, "/workspaces/ws-1/git/fetch", {})).json()) as any).ok).toBe(true)
  expect(((await (await post(token, "/workspaces/ws-1/git/pull", {})).json()) as any).status).toBe("up_to_date")
  expect((await get(token, "/workspaces/ws-x/git/status")).status).toBe(404)
  expect((await post(token, "/workspaces/ws-x/git/push", {})).status).toBe(404)
})

test("the branch's pull request: no origin says so; off a branch says so", async () => {
  const { dir, token } = await setup()
  expect(await (await get(token, "/workspaces/ws-1/git/pr")).json()).toEqual({ status: "unavailable", reason: "no_remote" })
  git(dir, "checkout", "-q", "--detach")
  expect(await (await get(token, "/sessions/s-1/git/pr")).json()).toEqual({ status: "unavailable", reason: "no_branch" })
  expect((await get(token, "/workspaces/ws-x/git/pr")).status).toBe(404)
})

test("creating a pull request needs a title and a target; ready needs a number; a log needs a check's URL", async () => {
  const { token } = await setup()
  expect((await post(token, "/workspaces/ws-1/git/pr", { title: "", target: "dev" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/pr", { title: "T" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-x/git/pr", { title: "T", target: "dev" })).status).toBe(404)
  const noRemote = await post(token, "/workspaces/ws-1/git/pr", { title: "T", target: "dev" })
  expect(noRemote.status).toBe(409)
  expect(await noRemote.json()).toMatchObject({ status: "unavailable", reason: "no_remote" })
  expect((await post(token, "/sessions/s-1/git/pr/ready", { number: "x" })).status).toBe(400)
  expect((await get(token, "/sessions/s-1/git/pr/check-log")).status).toBe(400)
  expect(await (await get(token, "/sessions/s-1/git/pr/check-log?url=https%3A%2F%2Fx.example%2F1")).json()).toEqual({ ok: false, reason: "unknown_check" })
})

test("get latest needs the chat's own base; resolve and abort refuse bad input", async () => {
  const { token } = await setup()
  expect((await post(token, "/sessions/s-1/git/latest", {})).status).toBe(409)   // no base known here: no guess
  expect((await post(token, "/workspaces/ws-1/git/resolve", { repo: "", path: "a.txt", side: "left" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/resolve", { repo: "", path: "../a.txt", side: "mine" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/abort", { repo: "" })).status).toBe(409)   // no merge in progress
})

test("history lists commits; revert/branch/tag refuse bad input", async () => {
  const { token } = await setup()
  const h = await (await get(token, "/workspaces/ws-1/git/history?repo=")).json() as any
  expect(h.chat[0].subject).toBe("base")
  expect(h.base).toBeNull()
  expect((await get(token, "/workspaces/ws-1/git/history?repo=nope")).status).toBe(404)
  expect((await post(token, "/workspaces/ws-1/git/revert", { repo: "", sha: "zzz" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/branch", { repo: "", sha: h.chat[0].sha, name: "-bad" })).status).toBe(400)
  expect((await post(token, "/workspaces/ws-1/git/tag", { repo: "", sha: h.chat[0].sha, name: "v1" })).status).toBe(200)
})
