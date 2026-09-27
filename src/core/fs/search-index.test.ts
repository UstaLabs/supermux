import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { fuzzyMatch, SearchIndexes } from "./search-index"
import { RepoInfoCache } from "./repo-info"

test("fuzzyMatch prefers the file name, consecutive runs and word starts", () => {
  const a = fuzzyMatch("ftree", "apps/ui/editor/FileTree.kt")!
  const b = fuzzyMatch("ftree", "apps/fixtures/tree/data.json")!
  expect(a.score).toBeGreaterThan(b.score)
  expect(fuzzyMatch("xyz", "apps/ui/editor/FileTree.kt")).toBeNull()
  const hits = fuzzyMatch("ft", "a/FileTree.kt")!.hits
  expect(hits.map((i) => "a/FileTree.kt"[i])).toEqual(["F", "T"])
})

test("fuzzyMatch tries every occurrence of the query's first character and keeps the best alignment", () => {
  expect(fuzzyMatch("kt", "kit/testkit.kt")!.hits).toEqual([12, 13])
})

test("fuzzyMatch returns null for an empty or whitespace query", () => {
  expect(fuzzyMatch("", "apps/ui/editor/FileTree.kt")).toBeNull()
  expect(fuzzyMatch("   ", "apps/ui/editor/FileTree.kt")).toBeNull()
})

function repo() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-search-")))
  execFileSync("git", ["init", "-q"], { cwd: root })
  writeFileSync(join(root, ".gitignore"), "node_modules/\n")
  mkdirSync(join(root, "src", "editor"), { recursive: true })
  writeFileSync(join(root, "src", "editor", "FileTree.kt"), "")
  writeFileSync(join(root, "src", "editor", "EditorPanes.kt"), "")
  writeFileSync(join(root, "README.md"), "")
  mkdirSync(join(root, "node_modules", "filetree"), { recursive: true })
  writeFileSync(join(root, "node_modules", "filetree", "index.js"), "")
  return root
}

test("a repo scope searches tracked + untracked files, never ignored ones", async () => {
  const root = repo()
  const idx = new SearchIndexes(new RepoInfoCache())
  const hits = await idx.query(root, "filetree", 50)
  expect(hits[0]!.path).toBe(join(root, "src", "editor", "FileTree.kt"))
  expect(hits.some((h) => h.path.includes("node_modules"))).toBe(false)
  expect(hits[0]!.hits.every((i) => i >= root.length + 1)).toBe(true) // indexes into the absolute path
})

test("folders are searchable and results respect the limit", async () => {
  const root = repo()
  const idx = new SearchIndexes(new RepoInfoCache())
  const hits = await idx.query(root, "editor", 1)
  expect(hits).toHaveLength(1)
  expect(hits[0]).toMatchObject({ path: join(root, "src", "editor"), type: "dir" })
})

test("outside git the walk skips heavy folders", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-search-plain-")))
  mkdirSync(join(root, "node_modules", "x"), { recursive: true })
  writeFileSync(join(root, "node_modules", "x", "target.txt"), "")
  writeFileSync(join(root, "target.txt"), "")
  const hits = await new SearchIndexes(new RepoInfoCache()).query(root, "target", 50)
  expect(hits.map((h) => h.path)).toEqual([join(root, "target.txt")])
})

test("an empty or whitespace query returns no results", async () => {
  const root = repo()
  const idx = new SearchIndexes(new RepoInfoCache())
  expect(await idx.query(root, "", 50)).toEqual([])
  expect(await idx.query(root, "   ", 50)).toEqual([])
})

test("fuzzyMatch scores 100,000 paths well under 1500ms", () => {
  const dirs = ["apps/ui/editor", "apps/ui/panes", "src/core/fs", "src/core/git", "libs/util/text", "packages/server/routes"]
  const names = ["FileTree", "EditorPanes", "SearchIndex", "RepoInfo", "DirCache", "FileOps", "Types", "Pool", "Errors", "Paths"]
  const exts = [".kt", ".ts", ".tsx", ".java", ".md"]
  const paths: string[] = []
  for (let i = 0; i < 100_000; i++) {
    const d = dirs[i % dirs.length]!
    const n = names[(i * 7) % names.length]!
    const e = exts[(i * 3) % exts.length]!
    paths.push(`${d}/${n}${i}${e}`)
  }
  const start = performance.now()
  let matches = 0
  for (const p of paths) if (fuzzyMatch("ftree", p)) matches++
  const elapsed = performance.now() - start
  expect(matches).toBeGreaterThan(0)
  expect(elapsed).toBeLessThan(1500)
})

test("invalidateContaining drops indexes whose scope contains the changed folder, and only those", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-search-inv-")))
  mkdirSync(join(root, "a", "deep"), { recursive: true })
  mkdirSync(join(root, "b"))
  const idx = new SearchIndexes(new RepoInfoCache())
  expect(await idx.query(root, "okapi", 50)).toEqual([])
  expect(await idx.query(join(root, "a"), "okapi", 50)).toEqual([])
  expect(await idx.query(join(root, "b"), "okapi", 50)).toEqual([])
  writeFileSync(join(root, "a", "deep", "okapi.txt"), "")
  writeFileSync(join(root, "b", "okapi.txt"), "")
  idx.invalidateContaining(join(root, "a", "deep"))
  expect((await idx.query(root, "okapi", 50)).length).toBe(2)
  expect((await idx.query(join(root, "a"), "okapi", 50)).length).toBe(1)
  expect(await idx.query(join(root, "b"), "okapi", 50)).toEqual([]) // unrelated scope kept its index
})

test("an index invalidated while it is being built is not stored", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-search-inv-")))
  const idx = new SearchIndexes(new RepoInfoCache())
  const first = idx.query(root, "okapi", 50)
  idx.invalidateContaining(root)
  expect(await first).toEqual([])
  writeFileSync(join(root, "okapi.txt"), "")
  // Had the invalidated build been stored, this would reuse it (fresh for 30 s) and miss the file.
  expect((await idx.query(root, "okapi", 50)).length).toBe(1)
})
