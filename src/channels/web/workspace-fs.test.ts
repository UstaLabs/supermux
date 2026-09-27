import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FileSystemService } from "../../core/fs/file-system-service"
import { WorkdirFs } from "../../core/fs/legacy"

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "ws-fs-"))
  mkdirSync(join(root, "src"))
  writeFileSync(join(root, "src", "a.ts"), "export const a = 1\n")
  writeFileSync(join(root, "README.md"), "# hi\n")
  return root
}
const fss = new FileSystemService({ bootId: "t" })

test("a workspace fs lists its own directory in the old shape", async () => {
  const root = fixture()
  const entries = await new WorkdirFs(fss, root).listDir(".")
  expect(entries.map((e) => e.name)).toEqual(["src", "README.md"])
  expect(entries[0]).toMatchObject({ name: "src", type: "dir", ignored: false })
  expect(typeof entries[1]!.modified).toBe("string")
  expect(entries[1]!.size).toBe(5)
})

test("a workspace fs reads a file under its root", async () => {
  const root = fixture()
  expect(await new WorkdirFs(fss, root).readFile("src/a.ts")).toBe("export const a = 1\n")
})

test("a path that escapes the workspace root is refused", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).readFile("../../etc/passwd")).rejects.toThrow()
})

test("an absolute path outside the root is refused", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).readFile("/etc/passwd")).rejects.toThrow()
})

test("a symlink that points outside the root is refused", async () => {
  const root = fixture()
  symlinkSync("/etc", join(root, "out"))
  await expect(new WorkdirFs(fss, root).readFile("out/passwd")).rejects.toThrow(/traversal/)
  await expect(new WorkdirFs(fss, root).writeFile("out/x", "y")).rejects.toThrow(/traversal/)
})

test("write and search keep their old response shapes", async () => {
  const root = fixture()
  const w = new WorkdirFs(fss, root)
  expect(await w.writeFile("src/b.ts", "b")).toEqual({ ok: true, size: 1 })
  const hits = await w.searchFiles("b.ts")
  expect(hits[0]).toEqual({ path: "src/b.ts", name: "b.ts", type: "file", ignored: false })
})

test("two workspaces on the same repo see the same files", async () => {
  const root = fixture()
  const a = await new WorkdirFs(fss, root).listDir(".")
  const b = await new WorkdirFs(fss, root).listDir(".")
  expect(a.map((e) => e.name)).toEqual(b.map((e) => e.name))
})
