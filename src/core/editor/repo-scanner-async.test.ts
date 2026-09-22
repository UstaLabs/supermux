import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { scanRepos, scanReposAsync } from "./repo-scanner"

test("scanReposAsync finds the same repos as scanRepos", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-scan-"))
  for (const r of ["a", "b/c"]) { mkdirSync(join(dir, r), { recursive: true }); execFileSync("git", ["init", "-q", join(dir, r)]) }
  mkdirSync(join(dir, "node_modules", "x"), { recursive: true }); execFileSync("git", ["init", "-q", join(dir, "node_modules", "x")])
  const sync = scanRepos(dir).map((r) => r.relPath).sort()
  expect((await scanReposAsync(dir)).map((r) => r.relPath).sort()).toEqual(sync)
  expect(sync).toEqual(["a", "b/c"])
})

test("scanReposAsync is async (never blocks the broker)", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-scan-async-"))
  expect(scanReposAsync(dir)).toBeInstanceOf(Promise)
})
