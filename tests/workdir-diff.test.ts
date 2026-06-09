// tests/workdir-diff.test.ts
import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execSync } from "child_process"
import { computeWorkdirDiff } from "../src/core/editor/workdir-diff"

let root: string
beforeEach(() => { root = mkdtempSync(join(tmpdir(), "cmux-wd-")) })
afterEach(() => { rmSync(root, { recursive: true, force: true }) })

function git(cwd: string, cmd: string) {
  execSync(`git ${cmd}`, { cwd, encoding: "utf-8" })
}
function initCommit(dir: string) {
  mkdirSync(dir, { recursive: true })
  git(dir, "init -q")
  git(dir, "config user.email test@test.com")
  git(dir, "config user.name test")
  writeFileSync(join(dir, "seed.txt"), "seed\n")
  git(dir, "add -A")
  git(dir, "commit -q -m init")
}
function head(dir: string): string {
  return execSync("git rev-parse HEAD", { cwd: dir, encoding: "utf-8" }).trim()
}

describe("computeWorkdirDiff", () => {
  test("single repo: shows modified tracked file", async () => {
    initCommit(root)
    const base = head(root)
    writeFileSync(join(root, "seed.txt"), "seed\nmore\n")
    const result = await computeWorkdirDiff(root, { "": base })
    expect(result.length).toBe(1)
    expect(result[0]!.repo).toBe("")
    expect(result[0]!.files.some((f) => f.path === "seed.txt")).toBe(true)
  })

  test("single repo: shows untracked new file as added", async () => {
    initCommit(root)
    const base = head(root)
    writeFileSync(join(root, "brand-new.ts"), "export const x = 1\n")
    const result = await computeWorkdirDiff(root, { "": base })
    const f = result[0]!.files.find((x) => x.path === "brand-new.ts")
    expect(f).toBeDefined()
    expect(f!.status).toBe("added")
  })

  test("does not mutate git index (untracked stays untracked)", async () => {
    initCommit(root)
    const base = head(root)
    writeFileSync(join(root, "untracked.ts"), "x\n")
    await computeWorkdirDiff(root, { "": base })
    const status = execSync("git status --porcelain", { cwd: root, encoding: "utf-8" })
    expect(status).toContain("?? untracked.ts")
  })

  test("multi repo: groups changes per repo", async () => {
    initCommit(join(root, "app1"))
    initCommit(join(root, "app2"))
    const base1 = head(join(root, "app1"))
    const base2 = head(join(root, "app2"))
    writeFileSync(join(root, "app1", "a.txt"), "changed\n")
    writeFileSync(join(root, "app2", "b.txt"), "new\n")
    const result = await computeWorkdirDiff(root, { app1: base1, app2: base2 })
    const repos = result.map((r) => r.repo).sort()
    expect(repos).toEqual(["app1", "app2"])
  })

  test("repo created mid-session (no base) shows all content as added", async () => {
    initCommit(root)
    const base = head(root)
    initCommit(join(root, "newrepo"))
    const result = await computeWorkdirDiff(root, { "": base })
    const newRepoGroup = result.find((r) => r.repo === "newrepo")
    expect(newRepoGroup).toBeDefined()
    expect(newRepoGroup!.files.some((f) => f.path === "seed.txt" && f.status === "added")).toBe(true)
  })

  test("repos with no changes are omitted", async () => {
    initCommit(root)
    const base = head(root)
    const result = await computeWorkdirDiff(root, { "": base })
    expect(result).toEqual([])
  })

  test("untracked filename with shell metacharacters does not execute code", async () => {
    initCommit(root)
    const base = head(root)
    // A filename containing shell-significant chars. If the implementation
    // builds a shell string, this would execute `touch INJECTED`.
    const evil = 'a"$(touch INJECTED).txt'
    writeFileSync(join(root, evil), "content\n")
    const result = await computeWorkdirDiff(root, { "": base })
    // The malicious side-effect file must NOT have been created.
    const { existsSync } = await import("fs")
    expect(existsSync(join(root, "INJECTED"))).toBe(false)
    // And the evil file should still show up as an added file.
    const f = result[0]!.files.find((x) => x.path === evil)
    expect(f).toBeDefined()
  })
})
