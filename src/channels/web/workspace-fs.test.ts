import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync, symlinkSync, readdirSync, rmSync } from "fs"
import { execSync } from "child_process"
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

// ─── containment (restored from the deleted tests/fs-service.test.ts) ─────────

test("listDir refuses ../", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).listDir("../")).rejects.toThrow()
})

test("listDir refuses ../../x", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).listDir("../../x")).rejects.toThrow()
})

test("listDir refuses a symlink pointing out of the workdir", async () => {
  const root = fixture()
  symlinkSync("/etc", join(root, "evil-etc"))
  await expect(new WorkdirFs(fss, root).listDir("evil-etc")).rejects.toThrow(/traversal/)
})

test("readFile refuses a NUL byte in the path", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).readFile("foo\0bar")).rejects.toThrow()
})

test("listDir refuses a NUL byte in the path", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).listDir("foo\0bar")).rejects.toThrow()
})

test("writeFile refuses ../x", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).writeFile("../x", "hack")).rejects.toThrow()
})

test("a symlinked workdir itself works for list/read/write (no false traversal)", async () => {
  const realDir = mkdtempSync(join(tmpdir(), "ws-fs-real-"))
  const symlinkDir = join(tmpdir(), `ws-fs-sym-${Date.now()}`)
  try {
    writeFileSync(join(realDir, "hello.txt"), "hi")
    symlinkSync(realDir, symlinkDir)
    const w = new WorkdirFs(fss, symlinkDir)
    const entries = await w.listDir(".")
    expect(entries.map((e) => e.name)).toContain("hello.txt")
    expect(await w.readFile("hello.txt")).toBe("hi")
    expect(await w.writeFile("new.txt", "content")).toEqual({ ok: true, size: 7 })
  } finally {
    rmSync(symlinkDir, { force: true })
    rmSync(realDir, { recursive: true, force: true })
  }
})

test("writeFile creates nested parent directories", async () => {
  const root = fixture()
  await new WorkdirFs(fss, root).writeFile("deep/nested/file.txt", "content")
  expect(await new WorkdirFs(fss, root).readFile("deep/nested/file.txt")).toBe("content")
})

test("writeFile overwrites an existing file", async () => {
  const root = fixture()
  const w = new WorkdirFs(fss, root)
  await w.writeFile("over.txt", "old content")
  await w.writeFile("over.txt", "new content")
  expect(await w.readFile("over.txt")).toBe("new content")
})

test("writeFile leaves no .tmp files behind", async () => {
  const root = fixture()
  await new WorkdirFs(fss, root).writeFile("clean.txt", "content")
  const files = readdirSync(root)
  expect(files.filter((f) => f.endsWith(".tmp"))).toHaveLength(0)
})

// ─── readFile size/binary limits ────────────────────────────────────────────

test("readFile rejects a missing file", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).readFile("nonexistent.txt")).rejects.toThrow()
})

test("readFile accepts exactly 1 MiB", async () => {
  const root = fixture()
  writeFileSync(join(root, "exact.txt"), Buffer.alloc(1024 * 1024, "A"))
  const content = await new WorkdirFs(fss, root).readFile("exact.txt")
  expect(content).toHaveLength(1024 * 1024)
})

test("readFile rejects 1 MiB + 1 with code TOO_LARGE", async () => {
  const root = fixture()
  writeFileSync(join(root, "big.txt"), Buffer.alloc(1024 * 1024 + 1, "A"))
  await expect(new WorkdirFs(fss, root).readFile("big.txt")).rejects.toMatchObject({ code: "TOO_LARGE" })
})

test("readFile rejects a file with a NUL byte with code BINARY", async () => {
  const root = fixture()
  const bin = Buffer.alloc(100)
  bin[10] = 0x00
  writeFileSync(join(root, "binary.bin"), bin)
  await expect(new WorkdirFs(fss, root).readFile("binary.bin")).rejects.toMatchObject({ code: "BINARY" })
})

// ─── listDir contents/sorting ────────────────────────────────────────────────

test("listDir includes dotfiles, .git and node_modules", async () => {
  const root = fixture()
  mkdirSync(join(root, ".git"))
  mkdirSync(join(root, "node_modules"))
  writeFileSync(join(root, ".env"), "SECRET=1")
  const entries = await new WorkdirFs(fss, root).listDir(".")
  const names = entries.map((e) => e.name)
  expect(names).toContain(".git")
  expect(names).toContain("node_modules")
  expect(names).toContain(".env")
})

test("listDir marks a .gitignore'd entry as ignored inside a git repo", async () => {
  const root = fixture()
  execSync("git init && git config user.email t@t.com && git config user.name t", { cwd: root, stdio: "pipe" })
  writeFileSync(join(root, ".gitignore"), "secret.txt\n")
  writeFileSync(join(root, "secret.txt"), "x")
  writeFileSync(join(root, "tracked.txt"), "x")
  const entries = await new WorkdirFs(fss, root).listDir(".")
  const byName = Object.fromEntries(entries.map((e) => [e.name, e]))
  expect(byName["secret.txt"]!.ignored).toBe(true)
  expect(byName["tracked.txt"]!.ignored).toBe(false)
})

test("listDir outside a git repo marks every entry ignored: false", async () => {
  const root = fixture()
  const entries = await new WorkdirFs(fss, root).listDir(".")
  expect(entries.every((e) => e.ignored === false)).toBe(true)
})

test("listDir on a subfolder lists one level only", async () => {
  const root = fixture()
  mkdirSync(join(root, "src", "child"))
  writeFileSync(join(root, "src", "child", "grandchild.txt"), "x")
  const entries = await new WorkdirFs(fss, root).listDir("src")
  expect(entries.map((e) => e.name).sort()).toEqual(["a.ts", "child"])
})

test("listDir sorts folders before files", async () => {
  const root = fixture()
  writeFileSync(join(root, "aaa.txt"), "")
  mkdirSync(join(root, "zzz"))
  const entries = await new WorkdirFs(fss, root).listDir(".")
  const firstFileIdx = entries.findIndex((e) => e.type === "file")
  const lastDirIdx = entries.map((e) => e.type).lastIndexOf("dir")
  expect(lastDirIdx).toBeLessThan(firstFileIdx)
})

// ─── searchFiles ──────────────────────────────────────────────────────────
// NEW behaviour vs. the deleted tests/fs-service.test.ts: search is now a fuzzy
// subsequence match (fuzzyMatch in src/core/fs/search-index.ts), not a plain
// substring match, and inside a git repo it is sourced from `git ls-files -co
// --exclude-standard`, which EXCLUDES gitignored files entirely (the old
// substring search included them and only flagged `ignored: true`). WorkdirFs
// also always reports `ignored: false` on search hits (legacy.ts:78), even
// inside a git repo — the old code reported the real gitignore status.

test("searchFiles is case-insensitive", async () => {
  const root = fixture()
  writeFileSync(join(root, "Hello.txt"), "")
  const results = await new WorkdirFs(fss, root).searchFiles("hello")
  expect(results.map((r) => r.name)).toContain("Hello.txt")
})

test("searchFiles finds matches in subfolders and returns workdir-relative paths", async () => {
  const root = fixture()
  mkdirSync(join(root, "sub"))
  writeFileSync(join(root, "sub", "target.txt"), "")
  const results = await new WorkdirFs(fss, root).searchFiles("target")
  expect(results.some((r) => r.path === "sub/target.txt")).toBe(true)
})

test("searchFiles finds dotfiles and files inside dot-folders outside git", async () => {
  const root = fixture()
  writeFileSync(join(root, ".env"), "SECRET_KEY=abc")
  mkdirSync(join(root, ".ssh"))
  writeFileSync(join(root, ".ssh", "id_rsa"), "PRIVATE KEY")
  const w = new WorkdirFs(fss, root)
  const envHits = await w.searchFiles(".env")
  expect(envHits.some((r) => r.name === ".env")).toBe(true)
  // Dot-folders are walked outside git: only the fixed heavy-folder skip list
  // (node_modules, .git, build, dist, .next, .nuxt, out, target, .gradle, Pods)
  // is excluded — .ssh is not in it, so id_rsa is still found.
  const idRsaHits = await w.searchFiles("id_rsa")
  expect(idRsaHits.some((r) => r.name === "id_rsa")).toBe(true)
})

test("searchFiles excludes .gitignore'd files inside a git repo (new behaviour)", async () => {
  const root = fixture()
  execSync("git init && git config user.email t@t.com && git config user.name t", { cwd: root, stdio: "pipe" })
  writeFileSync(join(root, ".gitignore"), "secret.txt\n")
  writeFileSync(join(root, "secret.txt"), "x")
  writeFileSync(join(root, "tracked-match.txt"), "x")
  const results = await new WorkdirFs(fss, root).searchFiles("txt")
  const names = results.map((r) => r.name)
  expect(names).toContain("tracked-match.txt")
  expect(names).not.toContain("secret.txt")
})

test("searchFiles reports ignored: false even inside a git repo (new behaviour)", async () => {
  const root = fixture()
  execSync("git init && git config user.email t@t.com && git config user.name t", { cwd: root, stdio: "pipe" })
  writeFileSync(join(root, "tracked-match.txt"), "x")
  const results = await new WorkdirFs(fss, root).searchFiles("tracked-match")
  expect(results[0]!.ignored).toBe(false)
})

test("searchFiles matches folders too", async () => {
  const root = fixture()
  mkdirSync(join(root, "my-project"))
  const results = await new WorkdirFs(fss, root).searchFiles("my-project")
  expect(results.some((r) => r.name === "my-project" && r.type === "dir")).toBe(true)
})

test("searchFiles caps at a default of 20 and respects a custom max", async () => {
  const root = fixture()
  for (let i = 0; i < 25; i++) writeFileSync(join(root, `file${i}.txt`), "")
  const w = new WorkdirFs(fss, root)
  const defaultResults = await w.searchFiles("file")
  expect(defaultResults.length).toBeLessThanOrEqual(20)
  const customResults = await w.searchFiles("file", 3)
  expect(customResults.length).toBeLessThanOrEqual(3)
})

test("searchFiles returns [] for no match", async () => {
  const root = fixture()
  writeFileSync(join(root, "foo.txt"), "")
  const results = await new WorkdirFs(fss, root).searchFiles("zzz-no-match-xyz")
  expect(results).toEqual([])
})
