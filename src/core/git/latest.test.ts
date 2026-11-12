// #72: "Get latest from <base>" merges the base in; conflicts are settled as mine, the base's, or both.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { abortMerge, getLatest, resolveConflict, unmerged } from "./latest"
import { commit } from "./commit"
import { indexState, stagedOf } from "./stage"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

const git = (d: string, ...a: string[]) => execFileSync("git", ["-C", d, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
const put = (d: string, f: string, t: string) => writeFileSync(join(d, f), t)

/** A repo on `dev` and a chat's worktree on `mux/chat`, both from the same commit. */
function setup() {
  const root = mkdtempSync(join(tmpdir(), "mux-latest-"))
  const repo = join(root, "repo"), wt = join(root, "wt")
  execFileSync("git", ["init", "-q", "-b", "dev", repo]); git(repo, "config", "user.email", "t@t"); git(repo, "config", "user.name", "t")
  put(repo, "a.txt", "one\ntwo\nthree\n"); put(repo, "b.txt", "b\n"); git(repo, "add", "."); git(repo, "commit", "-qm", "base")
  git(repo, "worktree", "add", "-q", "-b", "mux/chat", wt, "dev")
  return { repo, wt }
}
const onDev = (repo: string, f: string, t: string, m: string) => { put(repo, f, t); git(repo, "commit", "-qam", m) }

test("nothing new on the base is said; new commits come in as a merge", async () => {
  const { repo, wt } = setup()
  expect(await getLatest(wt, repo, "dev")).toEqual({ status: "up_to_date", from: "dev" })
  onDev(repo, "b.txt", "b2\n", "base moves")
  expect(await getLatest(wt, repo, "dev")).toEqual({ status: "clean", from: "dev", commits: 1 })
  expect(readFileSync(join(wt, "b.txt"), "utf-8")).toBe("b2\n")
}, REAL_GIT_MS)

test("a clash stops the merge and names the files, which show as conflicts", async () => {
  const { repo, wt } = setup()
  put(wt, "a.txt", "one\nMINE\nthree\n"); git(wt, "commit", "-qam", "mine")
  onDev(repo, "a.txt", "one\nTHEIRS\nthree\n", "theirs")
  expect(await getLatest(wt, repo, "dev")).toEqual({ status: "conflict", from: "dev", files: ["a.txt"] })
  expect(stagedOf("a.txt", await indexState(wt))).toBe("conflict")
  // asking again while it is still in progress says so, without merging twice
  expect(await getLatest(wt, repo, "dev")).toMatchObject({ status: "conflict", files: ["a.txt"] })
}, REAL_GIT_MS)

for (const [side, want] of [["mine", "one\nMINE\nthree\n"], ["theirs", "one\nTHEIRS\nthree\n"], ["both", "one\nMINE\nTHEIRS\nthree\n"]] as const) {
  test(`use ${side}: the file is settled and "Continue merge" commits with git's own message`, async () => {
    const { repo, wt } = setup()
    put(wt, "a.txt", "one\nMINE\nthree\n"); git(wt, "commit", "-qam", "mine")
    onDev(repo, "a.txt", "one\nTHEIRS\nthree\n", "theirs")
    await getLatest(wt, repo, "dev")
    await resolveConflict(wt, "a.txt", side)
    expect(readFileSync(join(wt, "a.txt"), "utf-8")).toBe(want)
    expect(await unmerged(wt)).toEqual([])
    expect(await commit(wt, {})).toMatchObject({ ok: true, subject: "Merge branch 'dev' into mux/chat" })
  }, REAL_GIT_MS)
}

test("a side that deleted the file deletes it", async () => {
  const { repo, wt } = setup()
  put(wt, "b.txt", "mine\n"); git(wt, "commit", "-qam", "mine")
  git(repo, "rm", "-q", "b.txt"); git(repo, "commit", "-qm", "gone")
  await getLatest(wt, repo, "dev")
  await resolveConflict(wt, "b.txt", "theirs")
  expect(existsSync(join(wt, "b.txt"))).toBe(false)
  await expect(resolveConflict(wt, "nope.txt", "both")).rejects.toThrow()
}, REAL_GIT_MS)

test("cancel puts everything back as it was before the merge", async () => {
  const { repo, wt } = setup()
  put(wt, "a.txt", "one\nMINE\nthree\n"); git(wt, "commit", "-qam", "mine")
  const before = git(wt, "rev-parse", "HEAD")
  onDev(repo, "a.txt", "one\nTHEIRS\nthree\n", "theirs")
  await getLatest(wt, repo, "dev")
  await abortMerge(wt)
  expect(git(wt, "rev-parse", "HEAD")).toBe(before)
  expect(readFileSync(join(wt, "a.txt"), "utf-8")).toBe("one\nMINE\nthree\n")
}, REAL_GIT_MS)

test("no base, no guess", async () => {
  const { repo, wt } = setup()
  expect(await getLatest(wt, repo, "")).toMatchObject({ status: "failed" })
  expect(await getLatest(wt, repo, "HEAD")).toMatchObject({ status: "failed" })
}, REAL_GIT_MS)
