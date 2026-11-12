// #72: a file's place in the next commit, read from and written to git's index.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { indexState, safeRepoPath, setStaged, stagedOf } from "./stage"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

const git = (dir: string, ...a: string[]) => execFileSync("git", ["-C", dir, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
function repo(commit = true) {
  const d = mkdtempSync(join(tmpdir(), "mux-stage-"))
  git(d, "init", "-q", "-b", "dev"); git(d, "config", "user.email", "t@t"); git(d, "config", "user.name", "t")
  writeFileSync(join(d, "a.txt"), "1\n2\n"); writeFileSync(join(d, "b.txt"), "b\n")
  if (commit) { git(d, "add", "."); git(d, "commit", "-qm", "base") }
  return d
}

test("all, partial and none follow git's index; a committed change has no state", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "1\n2\n3\n"); git(d, "add", "a.txt"); writeFileSync(join(d, "a.txt"), "0\n1\n2\n3\n")
  writeFileSync(join(d, "b.txt"), "B\n"); git(d, "add", "b.txt")
  writeFileSync(join(d, "c.txt"), "new\n")
  const s = await indexState(d)
  expect(stagedOf("a.txt", s)).toBe("partial")
  expect(stagedOf("b.txt", s)).toBe("all")
  expect(stagedOf("c.txt", s, true)).toBe("none")
  expect(stagedOf("z.txt", s)).toBeNull()
}, REAL_GIT_MS)

test("setStaged puts new, changed and deleted files in and takes them out", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "changed\n"); writeFileSync(join(d, "n.txt"), "new\n"); rmSync(join(d, "b.txt"))
  await setStaged(d, ["a.txt", "n.txt", "b.txt"], true)
  expect(git(d, "diff", "--cached", "--name-status").split("\n").sort()).toEqual(["A\tn.txt", "D\tb.txt", "M\ta.txt"])
  await setStaged(d, ["a.txt", "n.txt", "b.txt"], false)
  expect(git(d, "diff", "--cached", "--name-only")).toBe("")
}, REAL_GIT_MS)

// `[ab].txt` as a glob would also match a.txt and b.txt; `*` would say the same but is no file name on Windows.
test("paths are literal: a glob character names one file, not many", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "x\n"); writeFileSync(join(d, "b.txt"), "y\n"); writeFileSync(join(d, "[ab].txt"), "bracket\n")
  await setStaged(d, ["[ab].txt"], true)
  expect(git(d, "diff", "--cached", "--name-only")).toBe("[ab].txt")
}, REAL_GIT_MS)

test("a repo with no commit yet can still take files out of the next commit", async () => {
  const d = repo(false)
  await setStaged(d, ["a.txt"], true)
  expect(git(d, "diff", "--cached", "--name-only")).toBe("a.txt")
  await setStaged(d, ["a.txt"], false)
  expect(git(d, "diff", "--cached", "--name-only")).toBe("")
}, REAL_GIT_MS)

test("only repo-relative paths are accepted", () => {
  for (const ok of ["a.txt", "src/x.ts", ".env", "a..b"]) expect(safeRepoPath(ok)).toBe(true)
  for (const bad of ["", "/etc/passwd", "../x", "src/../../x", "C:\\x", "a\0b", 5, null]) expect(safeRepoPath(bad)).toBe(false)
})
