// #72: commit, commit all, amend, and undo the last commit.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { commit, messageFromFiles, uncommit } from "./commit"

const git = (d: string, ...a: string[]) => execFileSync("git", ["-C", d, ...a], { encoding: "utf-8" }).trim()
function repo() {
  const d = mkdtempSync(join(tmpdir(), "mux-commit-"))
  git(d, "init", "-q", "-b", "dev"); git(d, "config", "user.email", "t@t"); git(d, "config", "user.name", "t")
  writeFileSync(join(d, "a.txt"), "1\n"); git(d, "add", "."); git(d, "commit", "-qm", "base")
  return d
}

test("an empty message is written from the files", () => {
  expect(messageFromFiles(["src/App.jsx"])).toBe("Update App.jsx")
  expect(messageFromFiles(["src/App.jsx", "src/server.ts"])).toBe("Update App.jsx and server.ts")
  expect(messageFromFiles(["a/x.ts", "b/y.ts", "c/z.ts", "d/w.ts"])).toBe("Update x.ts and 3 more files")
})

test("commit takes only what is in the next commit; all takes everything", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "2\n"); writeFileSync(join(d, "b.txt"), "b\n")
  git(d, "add", "a.txt")
  const r = await commit(d, { message: "Change a" })
  expect(r).toMatchObject({ ok: true, subject: "Change a" })
  expect(git(d, "status", "--porcelain")).toBe("?? b.txt")
  const all = await commit(d, { all: true })
  expect(all).toMatchObject({ ok: true, subject: "Update b.txt" })
  expect(git(d, "status", "--porcelain")).toBe("")
})

test("nothing in the next commit is said, not committed", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "2\n")
  expect(await commit(d, {})).toEqual({ ok: false, reason: "nothing" })
})

test("amend rewrites the last commit; an empty message keeps its own", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "2\n"); git(d, "add", "."); await commit(d, { message: "first" })
  writeFileSync(join(d, "a.txt"), "3\n"); git(d, "add", ".")
  expect(await commit(d, { amend: true })).toMatchObject({ ok: true, subject: "first" })
  expect(git(d, "rev-list", "--count", "HEAD")).toBe("2")
  expect(await commit(d, { amend: true, message: "better" })).toMatchObject({ ok: true, subject: "better" })
})

test("a failing hook says why and commits nothing", async () => {
  const d = repo()
  writeFileSync(join(d, ".git", "hooks", "pre-commit"), "#!/bin/sh\necho 'lint failed' >&2\nexit 1\n", { mode: 0o755 })
  writeFileSync(join(d, "a.txt"), "2\n"); git(d, "add", ".")
  const r = await commit(d, { message: "x" })
  expect(r).toMatchObject({ ok: false, reason: "failed" })
  expect((r as any).message).toContain("lint failed")
  expect(git(d, "rev-list", "--count", "HEAD")).toBe("1")
})

test("undo keeps the changes in the next commit, and refuses the first commit, a merge and a pushed one", async () => {
  const d = repo()
  expect(await uncommit(d)).toMatchObject({ ok: false, reason: "none" })
  writeFileSync(join(d, "a.txt"), "2\n"); git(d, "add", "."); await commit(d, { message: "mine" })
  expect(await uncommit(d)).toEqual({ ok: true, subject: "mine" })
  expect(git(d, "diff", "--cached", "--name-only")).toBe("a.txt")
  await commit(d, { message: "mine again" })
  // a merge
  git(d, "switch", "-q", "-c", "side", "HEAD~1"); writeFileSync(join(d, "s.txt"), "s\n"); git(d, "add", "."); git(d, "commit", "-qm", "side")
  git(d, "switch", "-q", "dev"); git(d, "merge", "-q", "--no-edit", "side")
  expect(await uncommit(d)).toMatchObject({ ok: false, reason: "merge" })
  // pushed
  const remote = mkdtempSync(join(tmpdir(), "mux-commit-remote-"))
  execFileSync("git", ["init", "-q", "--bare", remote]); git(d, "remote", "add", "origin", remote); git(d, "push", "-q", "-u", "origin", "dev")
  writeFileSync(join(d, "a.txt"), "9\n"); git(d, "add", "."); await commit(d, { message: "local" })
  expect(await uncommit(d)).toEqual({ ok: true, subject: "local" })
  await commit(d, { message: "local" }); git(d, "push", "-q")
  expect(await uncommit(d)).toMatchObject({ ok: false, reason: "pushed" })
})
