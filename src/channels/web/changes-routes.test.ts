import { test, expect, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

function git(cwd: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
}

async function setup() {
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
    reviewList: () => [],
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return { dir, base, token }
}

async function get(token: string, path: string) {
  return fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, { headers: { authorization: `Bearer ${token}` } })
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
