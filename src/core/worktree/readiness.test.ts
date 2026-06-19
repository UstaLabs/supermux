import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"; import { join } from "path"
import { createWorktree } from "./manager"
import { computeReadiness } from "./readiness"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }
function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-rdy-")); execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "config", "user.email", "t@t.t"); g(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "f.txt"), "1\n"); g(dir, "add", "."); g(dir, "commit", "-m", "init"); return dir
}

test("recommends merge for a local-only repo with commits ahead", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  const r = computeReadiness({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })
  expect(r.hasRemote).toBe(false)
  expect(r.recommended).toBe("merge")
  expect(r.nothingToLand).toBe(false)
  expect(r.ahead).toBe(1)
})

test("nothingToLand when branch has no commits beyond base", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  const r = computeReadiness({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" })
  expect(r.nothingToLand).toBe(true)
})

test("defaultAction override forces the recommendation", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  const r = computeReadiness({ repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main", defaultAction: "pr" })
  expect(r.recommended).toBe("pr")
})
