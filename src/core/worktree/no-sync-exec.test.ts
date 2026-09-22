// src/core/worktree/no-sync-exec.test.ts
// The broker is one event loop. These files run on request/spawn paths; a sync
// child process in any of them freezes every session (2026-09-22 incident).
import { test, expect } from "bun:test"
import { existsSync, readFileSync } from "fs"
import { join } from "path"

const ROOT = join(import.meta.dirname, "..", "..")
const FILES = [
  "core/git/exec.ts",
  "core/git/repo-info.ts",
  "core/worktree/manager.ts",
  "core/worktree/finish.ts",
  // inventory.ts and service.ts don't exist yet (created in later tasks of the
  // explicit-worktree-cleanup plan). Skip them until then so this guard stays
  // green; remove the skip once the file exists.
  "core/worktree/inventory.ts",
  "core/worktree/service.ts",
]

for (const f of FILES) {
  const path = join(ROOT, f)
  test.skipIf(!existsSync(path))(`${f} uses no synchronous child processes`, () => {
    const src = readFileSync(path, "utf-8")
    expect(src).not.toMatch(/\b(execFileSync|execSync|spawnSync)\b/)
  })
}
