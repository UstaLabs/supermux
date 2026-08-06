import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FsService } from "../../core/editor/fs-service"

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "ws-fs-"))
  mkdirSync(join(root, "src"))
  writeFileSync(join(root, "src", "a.ts"), "export const a = 1\n")
  writeFileSync(join(root, "README.md"), "# hi\n")
  return root
}

test("a workspace fs service lists its own directory", async () => {
  const root = fixture()
  const entries = await new FsService(root).listDir(".")
  expect(entries.map((e) => e.name).sort()).toEqual(["README.md", "src"])
})

test("a workspace fs service reads a file under its root", async () => {
  const root = fixture()
  expect(await new FsService(root).readFile("src/a.ts")).toBe("export const a = 1\n")
})

test("a path that escapes the workspace root is refused", async () => {
  const root = fixture()
  const fs = new FsService(root)
  await expect(fs.readFile("../../etc/passwd")).rejects.toThrow()
})

test("an absolute path outside the root is refused", async () => {
  const root = fixture()
  const fs = new FsService(root)
  await expect(fs.readFile("/etc/passwd")).rejects.toThrow()
})

test("two workspaces on the same repo see the same files", async () => {
  // The interesting case: two workspaces sharing one work tree (spec §10).
  const root = fixture()
  const a = await new FsService(root).listDir(".")
  const b = await new FsService(root).listDir(".")
  expect(a.map((e) => e.name)).toEqual(b.map((e) => e.name))
})
