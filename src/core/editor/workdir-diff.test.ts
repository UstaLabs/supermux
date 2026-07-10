import { describe, expect, test, beforeEach, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { parseBaseSpec, computeWorkdirDiff, listRepoRefs } from "./workdir-diff"

function git(cwd: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8" }).trim()
}

let dir: string
function commit(file: string, body: string, msg: string) {
  writeFileSync(join(dir, file), body)
  git(dir, "add", "-A")
  git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", msg)
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "wdiff-"))
  git(dir, "init", "-q", "-b", "main")
  commit("a.txt", "one\n", "c1")
})
afterEach(() => rmSync(dir, { recursive: true, force: true }))

describe("parseBaseSpec", () => {
  test("defaults to session-start", () => {
    expect(parseBaseSpec(undefined)).toEqual({ kind: "session-start" })
    expect(parseBaseSpec("")).toEqual({ kind: "session-start" })
    expect(parseBaseSpec("garbage")).toEqual({ kind: "session-start" })
  })
  test("parses head/commit/branch", () => {
    expect(parseBaseSpec("head")).toEqual({ kind: "head" })
    expect(parseBaseSpec("commit:abc123")).toEqual({ kind: "commit", sha: "abc123" })
    expect(parseBaseSpec("branch:dev")).toEqual({ kind: "branch", name: "dev" })
  })
})

describe("computeWorkdirDiff base specs", () => {
  test("head base shows only uncommitted changes", async () => {
    commit("a.txt", "two\n", "c2")               // committed change (not in HEAD diff)
    writeFileSync(join(dir, "a.txt"), "three\n")  // uncommitted
    const repos = await computeWorkdirDiff(dir, {}, undefined, "head")
    const diff = repos[0]!.files.find((f) => f.path === "a.txt")!.diff
    expect(diff).toContain("three")
    expect(diff).not.toContain("+two")            // c2 already committed → not shown vs HEAD
  })

  test("branch base uses merge-base (no phantom deletions of mainline commits)", async () => {
    git(dir, "checkout", "-q", "-b", "feature")
    commit("a.txt", "feat\n", "on-feature")
    git(dir, "checkout", "-q", "main")
    commit("b.txt", "main-only\n", "on-main")     // main advances after branch point
    git(dir, "checkout", "-q", "feature")
    const repos = await computeWorkdirDiff(dir, {}, undefined, "branch:main")
    const paths = repos[0]!.files.map((f) => f.path)
    expect(paths).toContain("a.txt")              // feature's own change shows
    expect(paths).not.toContain("b.txt")          // main-only commit is NOT a phantom deletion
  })

  test("invalid commit spec falls back to session-start", async () => {
    const base = git(dir, "rev-parse", "HEAD")
    commit("a.txt", "two\n", "c2")
    const repos = await computeWorkdirDiff(dir, { "": base }, undefined, "commit:deadbeef")
    expect(repos[0]!.files.find((f) => f.path === "a.txt")!.diff).toContain("two")
  })
})

describe("listRepoRefs", () => {
  test("returns branches and recent commits for the repo", () => {
    git(dir, "branch", "dev")
    const refs = listRepoRefs(dir)
    expect(refs[0]!.branches).toEqual(expect.arrayContaining(["main", "dev"]))
    expect(refs[0]!.commits[0]!.subject).toBe("c1")
    expect(refs[0]!.commits[0]!.sha).toMatch(/^[0-9a-f]{7,}$/)
  })
})
