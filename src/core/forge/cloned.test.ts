// src/core/forge/cloned.test.ts
import { test, expect, afterAll } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, mkdirSync, existsSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { scanCloned, isInsideRoot } from "./cloned"

const work = mkdtempSync(join(tmpdir(), "forge-cloned-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

function gitRepo(dir: string) {
  mkdirSync(dir, { recursive: true }); execFileSync("git", ["init", "-q", dir])
  writeFileSync(join(dir, "f.txt"), "x")
}

test("scanCloned finds host/owner/repo git dirs under the root", () => {
  const root = join(work, "projects")
  gitRepo(join(root, "github.com", "ahmet", "supermux"))
  gitRepo(join(root, "gitlab.com", "acme", "web"))
  mkdirSync(join(root, "github.com", "ahmet", "not-a-repo"), { recursive: true })
  const found = scanCloned(root).map((c) => c.fullName).sort()
  expect(found).toEqual(["acme/web", "ahmet/supermux"])
  const sm = scanCloned(root).find((c) => c.name === "supermux")!
  expect(sm).toMatchObject({ host: "github.com", owner: "ahmet", name: "supermux" })
  expect(sm.path).toBe(join(root, "github.com", "ahmet", "supermux"))
})

test("scanCloned reports a locally-created repo (local/<name>) with host=local", () => {
  const root = join(work, "localproj")
  gitRepo(join(root, "local", "scratch"))
  const found = scanCloned(root)
  expect(found).toHaveLength(1)
  expect(found[0]).toMatchObject({ host: "local", owner: "local", name: "scratch", fullName: "scratch" })
})

test("isInsideRoot rejects traversal", () => {
  const root = join(work, "projects")
  expect(isInsideRoot(root, join(root, "github.com", "a", "b"))).toBe(true)
  expect(isInsideRoot(root, join(work, "evil"))).toBe(false)
  expect(isInsideRoot(root, join(root, "..", "evil"))).toBe(false)
})
