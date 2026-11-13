// #72: the History tab's two lists, and revert / branch / tag on one commit.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createRef, history, revert } from "./history"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

const git = (d: string, ...a: string[]) => execFileSync("git", ["-C", d, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
const commit = (d: string, f: string, t: string, m: string) => { writeFileSync(join(d, f), t); git(d, "add", "."); git(d, "commit", "-qm", m); return git(d, "rev-parse", "HEAD") }
function repo() {
  const d = mkdtempSync(join(tmpdir(), "mux-history-"))
  git(d, "init", "-q", "-b", "dev"); git(d, "config", "user.email", "t@t"); git(d, "config", "user.name", "Deniz")
  git(d, "config", "core.autocrlf", "false")   // Windows CI: files git writes keep "\n"
  commit(d, "a.txt", "1\n", "Base one"); commit(d, "a.txt", "2\n", "Base two")
  git(d, "switch", "-q", "-c", "mux/chat")
  return d
}

test("the chat's list is what its branch has that the base doesn't; the base's comes after, newest first", async () => {
  const d = repo()
  commit(d, "b.txt", "b\n", "Add search box"); commit(d, "b.txt", "bb\n", "Debounce search")
  const h = await history(d, "dev")
  expect(h.branch).toBe("mux/chat")
  expect(h.base).toBe("dev")
  expect(h.chat.map((c) => c.subject)).toEqual(["Debounce search", "Add search box"])
  expect(h.chat[0]).toMatchObject({ author: "Deniz" })
  expect(h.chat[0]!.at).toBeGreaterThan(0)
  expect(h.baseCommits.map((c) => c.subject)).toEqual(["Base two", "Base one"])
}, REAL_GIT_MS)

test("no base known: no guess, the chat's list is the branch's own recent commits", async () => {
  const d = repo()
  const h = await history(d, null)
  expect(h.base).toBeNull()
  expect(h.baseCommits).toEqual([])
  expect(h.chat.map((c) => c.subject)).toEqual(["Base two", "Base one"])
  expect((await history(d, "no-such-branch")).base).toBeNull()
}, REAL_GIT_MS)

test("pushed commits say so", async () => {
  const d = repo()
  const remote = mkdtempSync(join(tmpdir(), "mux-history-remote-"))
  execFileSync("git", ["init", "-q", "--bare", remote])
  git(d, "remote", "add", "origin", remote)
  commit(d, "b.txt", "b\n", "Pushed one"); git(d, "push", "-q", "-u", "origin", "mux/chat")
  commit(d, "b.txt", "bb\n", "Local one")
  const h = await history(d, "dev")
  expect(h.chat.map((c) => [c.subject, c.pushed])).toEqual([["Local one", false], ["Pushed one", true]])
}, REAL_GIT_MS)

test("revert adds a commit that undoes one; a clash leaves nothing half-done", async () => {
  const d = repo()
  const one = commit(d, "b.txt", "b\n", "Add b")
  expect(await revert(d, one)).toMatchObject({ ok: true })
  expect(git(d, "log", "-1", "--format=%s")).toBe('Revert "Add b"')
  const two = commit(d, "c.txt", "1\n", "c one"); commit(d, "c.txt", "2\n", "c two")
  const before = git(d, "rev-parse", "HEAD")
  expect(await revert(d, two)).toEqual({ ok: false, reason: "conflict" })
  expect(git(d, "rev-parse", "HEAD")).toBe(before)
  expect(readFileSync(join(d, "c.txt"), "utf-8")).toBe("2\n")
  expect(await revert(d, "not a sha")).toEqual({ ok: false, reason: "bad" })
}, REAL_GIT_MS)

test("a branch or a tag is made at the commit and nothing moves; bad or taken names are refused", async () => {
  const d = repo()
  const sha = git(d, "rev-parse", "HEAD~1")
  expect(await createRef(d, "branch", "try/old", sha)).toEqual({ ok: true })
  expect(git(d, "rev-parse", "try/old")).toBe(sha)
  expect(git(d, "branch", "--show-current")).toBe("mux/chat")
  expect(await createRef(d, "tag", "v1.0", sha)).toEqual({ ok: true })
  expect(await createRef(d, "tag", "v1.0", sha)).toEqual({ ok: false, reason: "exists" })
  for (const bad of ["", "-x", "a..b", "with space", "end."]) expect(await createRef(d, "branch", bad, sha)).toEqual({ ok: false, reason: "bad" })
}, REAL_GIT_MS)
