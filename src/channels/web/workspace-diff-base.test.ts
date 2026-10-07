import { test, expect, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

/**
 * GET /workspaces/:id/fs/diff must resolve "session-start" the same way the
 * session-scoped route does. Without a baseline every tracked file diffs
 * against the empty tree, so the whole repo reads as newly added.
 */

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

function git(cwd: string, args: string[]) {
  execFileSync("git", args, { cwd, stdio: ["pipe", "pipe", "pipe"] })
}

/** A repo with two committed files, then one of them edited in the worktree. */
function repoWithOneEdit() {
  const dir = mkdtempSync(join(tmpdir(), "mux-ws-diff-"))
  git(dir, ["init", "-q", "-b", "main"])
  git(dir, ["config", "user.email", "t@t"])
  git(dir, ["config", "user.name", "t"])
  writeFileSync(join(dir, "old.txt"), "committed before the session\n")
  writeFileSync(join(dir, "kept.txt"), "untouched\n")
  git(dir, ["add", "."])
  git(dir, ["commit", "-qm", "base"])
  const base = execFileSync("git", ["rev-parse", "HEAD"], { cwd: dir, encoding: "utf-8" }).trim()
  writeFileSync(join(dir, "old.txt"), "edited during the session\n")
  return { dir, base }
}

function harness(workdir: string, diffBase: WebChannelOpts["getWorkspaceDiffBase"]) {
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-ws-diff-dev-")), "devices.json")
  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getWorkspaceWorkdir: () => workdir,
    getWorkspaceDiffBase: diffBase,
  })
  return { devicesFile }
}

async function fetchDiff(devicesFile: string) {
  const token = new DeviceStore(devicesFile).mint("test-device").token
  const res = await fetch(`http://127.0.0.1:${channel!.boundPort}/workspaces/ws-1/fs/diff`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(res.status).toBe(200)
  return await res.json() as { repos: Array<{ repo: string; files: Array<{ path: string }> }> }
}

test("workspace diff uses the workspace baseline, not the empty tree", async () => {
  const { dir, base } = repoWithOneEdit()
  const { devicesFile } = harness(dir, () => ({ baseCommits: { "": base } }))
  await channel!.start()

  const body = await fetchDiff(devicesFile)
  const paths = body.repos.flatMap((r) => r.files.map((f) => f.path))
  expect(paths).toEqual(["old.txt"])
})

test("with no baseline at all every committed file reads as added", async () => {
  const { dir } = repoWithOneEdit()
  const { devicesFile } = harness(dir, undefined)
  await channel!.start()

  const body = await fetchDiff(devicesFile)
  const paths = body.repos.flatMap((r) => r.files.map((f) => f.path)).sort()
  expect(paths).toEqual(["kept.txt", "old.txt"])
})
