// src/core/worktree/no-sync-exec.test.ts
// The broker is one event loop. These files run on request/spawn paths; a sync
// child process in any of them freezes every session (2026-09-22 incident).
import { test, expect } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"

const ROOT = join(import.meta.dirname, "..", "..")
const FILES = [
  "core/git/exec.ts",
  "core/git/repo-info.ts",
  "core/worktree/manager.ts",
  "core/worktree/finish.ts",
  "core/worktree/inventory.ts",
  "core/worktree/service.ts",
]

for (const f of FILES) {
  const path = join(ROOT, f)
  test(`${f} uses no synchronous child processes`, () => {
    const src = readFileSync(path, "utf-8")
    expect(src).not.toMatch(/\b(execFileSync|execSync|spawnSync)\b/)
  })
}
