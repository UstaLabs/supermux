// src/core/git/clone.test.ts
import { test, expect, afterAll } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, existsSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { projectDir, gitClone } from "./clone"

const work = mkdtempSync(join(tmpdir(), "forge-clone-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

// Build a local bare repo with one commit to act as "origin".
function makeOrigin(): string {
  const src = join(work, "src"); const bare = join(work, "origin.git")
  execFileSync("git", ["init", "-q", src]); writeFileSync(join(src, "README.md"), "hi")
  execFileSync("git", ["-C", src, "add", "."])
  execFileSync("git", ["-C", src, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init"])
  execFileSync("git", ["clone", "-q", "--bare", src, bare])
  return bare
}

test("projectDir composes host/owner/repo under the root", () => {
  expect(projectDir("/root", "github.com", "ahmet", "supermux")).toBe("/root/github.com/ahmet/supermux")
})

test("gitClone clones to the target and is idempotent on reuse", async () => {
  const origin = makeOrigin()
  const target = join(work, "out", "repo")
  const a = await gitClone({ url: `file://${origin}`, targetDir: target })
  expect(a.reused).toBe(false)
  expect(existsSync(join(target, "README.md"))).toBe(true)
  const b = await gitClone({ url: `file://${origin}`, targetDir: target })
  expect(b.reused).toBe(true) // already present → no re-clone
})
