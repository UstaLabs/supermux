// src/core/git/remote.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { remoteStatus, fetchRemote, publishBranch, pushBranch, pullBranch } from "./remote"

function g(cwd: string, ...a: string[]) {
  return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim()
}

/** A working repo on branch `mux/s` whose `origin` is a local bare repo (no network). */
function repoWithRemote(): { work: string; bare: string } {
  const bare = mkdtempSync(join(tmpdir(), "mux-bare-"))
  execFileSync("git", ["init", "--bare", "-b", "main", bare])
  const work = mkdtempSync(join(tmpdir(), "mux-work-"))
  execFileSync("git", ["init", "-b", "main", work])
  g(work, "config", "user.email", "t@t.t"); g(work, "config", "user.name", "t")
  writeFileSync(join(work, "f.txt"), "1\n"); g(work, "add", "."); g(work, "commit", "-m", "init")
  g(work, "remote", "add", "origin", bare)
  g(work, "push", "-u", "origin", "main")
  g(work, "checkout", "-b", "mux/s")
  return { work, bare }
}

/** Clone origin's `branch`, add+push one commit touching `file`. */
function clonePushCommit(bare: string, branch: string, file: string, content = "y\n") {
  const other = mkdtempSync(join(tmpdir(), "mux-other-"))
  execFileSync("git", ["clone", "-b", branch, bare, other])
  g(other, "config", "user.email", "o@o.o"); g(other, "config", "user.name", "o")
  writeFileSync(join(other, file), content); g(other, "add", "."); g(other, "commit", "-m", "remote")
  g(other, "push", "origin", branch)
}

test("no remote → hasRemote false, branch set, no upstream", () => {
  const work = mkdtempSync(join(tmpdir(), "mux-norem-"))
  execFileSync("git", ["init", "-b", "main", work])
  g(work, "config", "user.email", "t@t.t"); g(work, "config", "user.name", "t")
  writeFileSync(join(work, "f.txt"), "1\n"); g(work, "add", "."); g(work, "commit", "-m", "init")
  const s = remoteStatus(work)
  expect(s.hasRemote).toBe(false)
  expect(s.branch).toBe("main")
  expect(s.upstream).toBeNull()
  expect(s.ahead).toBe(0); expect(s.behind).toBe(0)
})

test("unpublished branch → hasRemote true, upstream null", () => {
  const { work } = repoWithRemote()
  const s = remoteStatus(work)
  expect(s.hasRemote).toBe(true)
  expect(s.branch).toBe("mux/s")
  expect(s.upstream).toBeNull()
})

test("published, no divergence → upstream set, 0/0", () => {
  const { work } = repoWithRemote()
  g(work, "push", "-u", "origin", "mux/s")
  const s = remoteStatus(work)
  expect(s.upstream).toBe("origin/mux/s")
  expect(s.ahead).toBe(0); expect(s.behind).toBe(0)
})

test("local commit after publish → ahead 1", () => {
  const { work } = repoWithRemote()
  g(work, "push", "-u", "origin", "mux/s")
  writeFileSync(join(work, "n.txt"), "x"); g(work, "add", "."); g(work, "commit", "-m", "work")
  const s = remoteStatus(work)
  expect(s.ahead).toBe(1); expect(s.behind).toBe(0)
})

test("remote-only commit → behind 1 after fetch", () => {
  const { work, bare } = repoWithRemote()
  g(work, "push", "-u", "origin", "mux/s")
  clonePushCommit(bare, "mux/s", "r.txt")
  fetchRemote(work)
  const s = remoteStatus(work)
  expect(s.behind).toBe(1); expect(s.ahead).toBe(0)
})

test("detached HEAD → branch null", () => {
  const { work } = repoWithRemote()
  g(work, "checkout", "--detach")
  const s = remoteStatus(work)
  expect(s.branch).toBeNull()
})

test("publishBranch sets upstream and pushes", () => {
  const { work } = repoWithRemote()
  const r = publishBranch(work)
  expect(r.status).toBe("pushed")
  expect(remoteStatus(work).upstream).toBe("origin/mux/s")
})

test("pushBranch: new commit → pushed, then up_to_date", () => {
  const { work } = repoWithRemote()
  publishBranch(work)
  writeFileSync(join(work, "n.txt"), "x"); g(work, "add", "."); g(work, "commit", "-m", "work")
  expect(pushBranch(work).status).toBe("pushed")
  expect(pushBranch(work).status).toBe("up_to_date")
  expect(remoteStatus(work).ahead).toBe(0)
})

test("pushBranch when remote moved → rejected_non_ff", () => {
  const { work, bare } = repoWithRemote()
  publishBranch(work)
  clonePushCommit(bare, "mux/s", "r.txt")          // origin/mux/s advances
  writeFileSync(join(work, "n.txt"), "x"); g(work, "add", "."); g(work, "commit", "-m", "local") // we diverge
  expect(pushBranch(work).status).toBe("rejected_non_ff")
})

test("pull when behind fast-forwards → clean", () => {
  const { work, bare } = repoWithRemote()
  publishBranch(work)
  clonePushCommit(bare, "mux/s", "r.txt")
  const r = pullBranch(work)
  expect(r.status).toBe("clean")
  expect(remoteStatus(work).behind).toBe(0)
})

test("pull when already current → up_to_date", () => {
  const { work } = repoWithRemote()
  publishBranch(work)
  expect(pullBranch(work).status).toBe("up_to_date")
})

test("pull with conflicting edits → conflict (leaves merge in progress)", () => {
  const { work, bare } = repoWithRemote()
  publishBranch(work)
  clonePushCommit(bare, "mux/s", "f.txt", "remote\n")              // remote edits f.txt
  writeFileSync(join(work, "f.txt"), "local\n"); g(work, "add", "."); g(work, "commit", "-m", "local") // we edit f.txt
  const r = pullBranch(work)
  expect(r.status).toBe("conflict")
  if (r.status === "conflict") expect(r.files).toContain("f.txt")
})

test("pull blocked by uncommitted changes → dirty", () => {
  const { work } = repoWithRemote()
  publishBranch(work)
  writeFileSync(join(work, "f.txt"), "uncommitted\n")             // dirty, not committed
  const r = pullBranch(work)
  expect(r.status).toBe("dirty")
  if (r.status === "dirty") expect(r.files).toContain("f.txt")
})

test("pull: second call after resolving conflict completes merge → clean", () => {
  const { work, bare } = repoWithRemote()
  publishBranch(work)
  clonePushCommit(bare, "mux/s", "f.txt", "remote\n")
  writeFileSync(join(work, "f.txt"), "local\n"); g(work, "add", "."); g(work, "commit", "-m", "local")
  const first = pullBranch(work)
  expect(first.status).toBe("conflict")
  // Resolve: write merged content and stage it.
  writeFileSync(join(work, "f.txt"), "resolved\n"); g(work, "add", "f.txt")
  // Second call completes the in-progress merge.
  const second = pullBranch(work)
  expect(second.status).toBe("clean")
})
