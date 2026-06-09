// tests/repo-scanner.test.ts
import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execSync } from "child_process"
import { scanRepos } from "../src/core/editor/repo-scanner"

let root: string
beforeEach(() => { root = mkdtempSync(join(tmpdir(), "cmux-scan-")) })
afterEach(() => { rmSync(root, { recursive: true, force: true }) })

function initRepo(dir: string) {
  mkdirSync(dir, { recursive: true })
  execSync("git init -q", { cwd: dir })
}

describe("scanRepos", () => {
  test("workdir itself is a repo → relPath empty string", () => {
    initRepo(root)
    const repos = scanRepos(root)
    expect(repos.length).toBe(1)
    expect(repos[0]!.relPath).toBe("")
  })

  test("finds repos one level deep", () => {
    initRepo(join(root, "app1"))
    initRepo(join(root, "app2"))
    const repos = scanRepos(root)
    const rels = repos.map((r) => r.relPath).sort()
    expect(rels).toEqual(["app1", "app2"])
  })

  test("finds nested repos up to depth 5", () => {
    initRepo(join(root, "a/b/c/d/e"))
    const repos = scanRepos(root)
    expect(repos.map((r) => r.relPath)).toEqual(["a/b/c/d/e"])
  })

  test("does not descend past depth 5", () => {
    initRepo(join(root, "a/b/c/d/e/f"))
    const repos = scanRepos(root)
    expect(repos.length).toBe(0)
  })

  test("stops descending once a repo root is found (no nested non-submodule repos within)", () => {
    initRepo(join(root, "outer"))
    mkdirSync(join(root, "outer", "inner", ".git"), { recursive: true })
    const repos = scanRepos(root)
    expect(repos.map((r) => r.relPath)).toEqual(["outer"])
  })

  test("skips node_modules and other heavy dirs", () => {
    initRepo(join(root, "node_modules", "pkg"))
    initRepo(join(root, "dist", "x"))
    initRepo(join(root, "real"))
    const repos = scanRepos(root)
    expect(repos.map((r) => r.relPath)).toEqual(["real"])
  })

  test("returns empty array when no repos", () => {
    writeFileSync(join(root, "file.txt"), "hi")
    expect(scanRepos(root)).toEqual([])
  })
})
