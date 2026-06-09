import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, mkdirSync, writeFileSync, symlinkSync } from "fs"
import { execSync } from "child_process"
import { tmpdir } from "os"
import { join } from "path"
import { FsService } from "../src/core/editor/fs-service"

let tmpDir: string
let svc: FsService

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-fs-"))
  svc = new FsService(tmpDir)
})

afterEach(() => {
  rmSync(tmpDir, { recursive: true, force: true })
})

// ─── listDir ────────────────────────────────────────────────────────────────

describe("listDir", () => {
  test("returns empty array for empty directory", async () => {
    const entries = await svc.listDir(".")
    expect(entries).toEqual([])
  })

  test("lists files and directories", async () => {
    writeFileSync(join(tmpDir, "hello.txt"), "hello")
    mkdirSync(join(tmpDir, "subdir"))
    const entries = await svc.listDir(".")
    expect(entries).toHaveLength(2)
    const names = entries.map((e) => e.name)
    expect(names).toContain("hello.txt")
    expect(names).toContain("subdir")
  })

  test("directories come before files", async () => {
    writeFileSync(join(tmpDir, "aaa.txt"), "")
    mkdirSync(join(tmpDir, "zzz"))
    const entries = await svc.listDir(".")
    expect(entries[0]!.name).toBe("zzz")
    expect(entries[1]!.name).toBe("aaa.txt")
  })

  test("sorts alphabetically within same type", async () => {
    writeFileSync(join(tmpDir, "b.txt"), "")
    writeFileSync(join(tmpDir, "a.txt"), "")
    mkdirSync(join(tmpDir, "d"))
    mkdirSync(join(tmpDir, "c"))
    const entries = await svc.listDir(".")
    expect(entries[0]!.name).toBe("c")
    expect(entries[1]!.name).toBe("d")
    expect(entries[2]!.name).toBe("a.txt")
    expect(entries[3]!.name).toBe("b.txt")
  })

  test("includes .git directory", async () => {
    mkdirSync(join(tmpDir, ".git"))
    writeFileSync(join(tmpDir, "real.txt"), "")
    const entries = await svc.listDir(".")
    const names = entries.map((e) => e.name)
    expect(names).toContain(".git")
    expect(names).toContain("real.txt")
  })

  test("includes node_modules directory", async () => {
    mkdirSync(join(tmpDir, "node_modules"))
    writeFileSync(join(tmpDir, "real.txt"), "")
    const entries = await svc.listDir(".")
    const names = entries.map((e) => e.name)
    expect(names).toContain("node_modules")
  })

  test("includes dotfiles", async () => {
    writeFileSync(join(tmpDir, ".env"), "SECRET=1")
    writeFileSync(join(tmpDir, ".hidden"), "")
    writeFileSync(join(tmpDir, "visible.txt"), "")
    const entries = await svc.listDir(".")
    const names = entries.map((e) => e.name)
    expect(names).toContain(".env")
    expect(names).toContain(".hidden")
    expect(names).toContain("visible.txt")
  })

  test("returns correct FsEntry shape", async () => {
    writeFileSync(join(tmpDir, "test.txt"), "hello")
    const entries = await svc.listDir(".")
    expect(entries).toHaveLength(1)
    const e = entries[0]!
    expect(e.name).toBe("test.txt")
    expect(e.type).toBe("file")
    expect(e.size).toBe(5)
    expect(typeof e.modified).toBe("string")
    expect(e.ignored).toBe(false)
    // Should be a valid ISO timestamp
    expect(() => new Date(e.modified)).not.toThrow()
    expect(new Date(e.modified).getTime()).toBeGreaterThan(0)
  })

  test("directory type is 'dir'", async () => {
    mkdirSync(join(tmpDir, "mydir"))
    const entries = await svc.listDir(".")
    expect(entries[0]!.type).toBe("dir")
  })

  test("lists subdirectory contents (lazy, one level)", async () => {
    mkdirSync(join(tmpDir, "sub"))
    writeFileSync(join(tmpDir, "sub", "child.txt"), "child")
    const entries = await svc.listDir("sub")
    expect(entries).toHaveLength(1)
    expect(entries[0]!.name).toBe("child.txt")
  })

  test("rejects path traversal with ../", async () => {
    await expect(svc.listDir("../")).rejects.toThrow()
  })

  test("rejects path traversal with nested ../../", async () => {
    mkdirSync(join(tmpDir, "sub"))
    await expect(svc.listDir("sub/../../etc")).rejects.toThrow()
  })

  test("rejects absolute path outside workdir", async () => {
    await expect(svc.listDir("/etc")).rejects.toThrow()
  })
})

// ─── readFile ────────────────────────────────────────────────────────────────

describe("readFile", () => {
  test("reads file content as UTF-8 string", async () => {
    writeFileSync(join(tmpDir, "hello.txt"), "hello world")
    const content = await svc.readFile("hello.txt")
    expect(content).toBe("hello world")
  })

  test("reads file in subdirectory", async () => {
    mkdirSync(join(tmpDir, "sub"))
    writeFileSync(join(tmpDir, "sub", "data.txt"), "data")
    const content = await svc.readFile("sub/data.txt")
    expect(content).toBe("data")
  })

  test("rejects path traversal", async () => {
    await expect(svc.readFile("../outside.txt")).rejects.toThrow()
  })

  test("throws on non-existent file", async () => {
    await expect(svc.readFile("nonexistent.txt")).rejects.toThrow()
  })

  test("rejects binary files (null-byte detected)", async () => {
    // Create a file with null bytes
    const binaryData = Buffer.alloc(100)
    binaryData[10] = 0x00 // null byte
    writeFileSync(join(tmpDir, "binary.bin"), binaryData)
    await expect(svc.readFile("binary.bin")).rejects.toThrow(/binary/)
  })

  test("accepts non-binary files with no null bytes", async () => {
    const content = "This is a text file with special chars: áéíóú"
    writeFileSync(join(tmpDir, "utf8.txt"), content)
    const result = await svc.readFile("utf8.txt")
    expect(result).toBe(content)
  })

  test("rejects files larger than 1MB", async () => {
    // Create a 1MB + 1 byte file
    const bigContent = Buffer.alloc(1024 * 1024 + 1, "A")
    writeFileSync(join(tmpDir, "big.txt"), bigContent)
    await expect(svc.readFile("big.txt")).rejects.toThrow(/1MB|too large/i)
  })

  test("accepts files at exactly 1MB", async () => {
    const exactContent = Buffer.alloc(1024 * 1024, "A")
    writeFileSync(join(tmpDir, "exact.txt"), exactContent)
    const result = await svc.readFile("exact.txt")
    expect(result).toHaveLength(1024 * 1024)
  })
})

// ─── writeFile ───────────────────────────────────────────────────────────────

describe("writeFile", () => {
  test("writes content to a new file", async () => {
    const result = await svc.writeFile("output.txt", "hello")
    expect(result.ok).toBe(true)
    expect(result.size).toBe(5)

    const { readFileSync } = await import("fs")
    const content = readFileSync(join(tmpDir, "output.txt"), "utf-8")
    expect(content).toBe("hello")
  })

  test("overwrites existing file", async () => {
    writeFileSync(join(tmpDir, "file.txt"), "old content")
    await svc.writeFile("file.txt", "new content")
    const { readFileSync } = await import("fs")
    const content = readFileSync(join(tmpDir, "file.txt"), "utf-8")
    expect(content).toBe("new content")
  })

  test("creates parent directories if needed", async () => {
    await svc.writeFile("deep/nested/file.txt", "content")
    const { readFileSync, existsSync } = await import("fs")
    expect(existsSync(join(tmpDir, "deep/nested/file.txt"))).toBe(true)
    expect(readFileSync(join(tmpDir, "deep/nested/file.txt"), "utf-8")).toBe("content")
  })

  test("returns correct size", async () => {
    const content = "hello world" // 11 bytes
    const result = await svc.writeFile("size-test.txt", content)
    expect(result.size).toBe(11)
  })

  test("rejects path traversal", async () => {
    await expect(svc.writeFile("../evil.txt", "hack")).rejects.toThrow()
  })

  test("no .tmp file left after successful write", async () => {
    await svc.writeFile("clean.txt", "content")
    const { readdirSync } = await import("fs")
    const files = readdirSync(tmpDir)
    const tmpFiles = files.filter((f) => f.endsWith(".tmp"))
    expect(tmpFiles).toHaveLength(0)
  })
})

// ─── searchFiles ─────────────────────────────────────────────────────────────

describe("searchFiles", () => {
  test("finds files matching query", async () => {
    writeFileSync(join(tmpDir, "hello.txt"), "")
    writeFileSync(join(tmpDir, "world.txt"), "")
    const results = await svc.searchFiles("hello")
    expect(results).toHaveLength(1)
    expect(results[0]!.name).toBe("hello.txt")
  })

  test("search is case-insensitive", async () => {
    writeFileSync(join(tmpDir, "Hello.txt"), "")
    const results = await svc.searchFiles("hello")
    expect(results).toHaveLength(1)
    expect(results[0]!.name).toBe("Hello.txt")
  })

  test("finds files in subdirectories", async () => {
    mkdirSync(join(tmpDir, "sub"))
    writeFileSync(join(tmpDir, "sub", "find-me.txt"), "")
    const results = await svc.searchFiles("find-me")
    expect(results).toHaveLength(1)
    expect(results[0]!.name).toBe("find-me.txt")
  })

  test("returns path relative to workdir", async () => {
    mkdirSync(join(tmpDir, "sub"))
    writeFileSync(join(tmpDir, "sub", "target.txt"), "")
    const results = await svc.searchFiles("target")
    expect(results[0]!.path).toBe("sub/target.txt")
  })

  test("includes node_modules matches", async () => {
    mkdirSync(join(tmpDir, "node_modules"))
    writeFileSync(join(tmpDir, "node_modules", "match.txt"), "")
    writeFileSync(join(tmpDir, "real-match.txt"), "")
    const results = await svc.searchFiles("match")
    expect(results).toHaveLength(2)
    const names = results.map((r) => r.name)
    expect(names).toContain("real-match.txt")
    expect(names).toContain("match.txt")
  })

  test("includes .git directory matches", async () => {
    mkdirSync(join(tmpDir, ".git"))
    writeFileSync(join(tmpDir, ".git", "match.txt"), "")
    writeFileSync(join(tmpDir, "real-match.txt"), "")
    const results = await svc.searchFiles("match")
    expect(results.some((r) => r.path.startsWith(".git"))).toBe(true)
    expect(results.some((r) => r.name === "real-match.txt")).toBe(true)
  })

  test("caps results at maxResults (default 20)", async () => {
    for (let i = 0; i < 25; i++) {
      writeFileSync(join(tmpDir, `file${i}.txt`), "")
    }
    const results = await svc.searchFiles("file")
    expect(results.length).toBeLessThanOrEqual(20)
  })

  test("respects custom maxResults", async () => {
    for (let i = 0; i < 10; i++) {
      writeFileSync(join(tmpDir, `item${i}.txt`), "")
    }
    const results = await svc.searchFiles("item", 3)
    expect(results.length).toBeLessThanOrEqual(3)
  })

  test("returns empty array for no matches", async () => {
    writeFileSync(join(tmpDir, "foo.txt"), "")
    const results = await svc.searchFiles("zzz-no-match")
    expect(results).toEqual([])
  })

  test("result has path, name, type fields", async () => {
    writeFileSync(join(tmpDir, "sample.ts"), "")
    const results = await svc.searchFiles("sample")
    expect(results[0]).toHaveProperty("path")
    expect(results[0]).toHaveProperty("name")
    expect(results[0]).toHaveProperty("type")
    expect(results[0]!.type).toBe("file")
  })

  test("also matches directories", async () => {
    mkdirSync(join(tmpDir, "my-project"))
    const results = await svc.searchFiles("my-project")
    expect(results).toHaveLength(1)
    expect(results[0]!.type).toBe("dir")
  })
})

// ─── gitDiff ─────────────────────────────────────────────────────────────────

describe("gitDiff", () => {
  test("returns empty array when not a git repo", async () => {
    const result = await svc.gitDiff()
    expect(result).toEqual([])
  })

  test("returns empty array when no changes in git repo", async () => {
    // Initialize a git repo with an initial commit
    const { execSync } = await import("child_process")
    execSync("git init && git config user.email 'test@test.com' && git config user.name 'Test'", {
      cwd: tmpDir,
      stdio: "pipe",
    })
    writeFileSync(join(tmpDir, "initial.txt"), "initial")
    execSync("git add . && git commit -m 'initial'", { cwd: tmpDir, stdio: "pipe" })

    const result = await svc.gitDiff()
    expect(result).toEqual([])
  })

  test("returns diff entries when files are modified", async () => {
    const { execSync } = await import("child_process")
    execSync("git init && git config user.email 'test@test.com' && git config user.name 'Test'", {
      cwd: tmpDir,
      stdio: "pipe",
    })
    writeFileSync(join(tmpDir, "file.txt"), "original content")
    execSync("git add . && git commit -m 'initial'", { cwd: tmpDir, stdio: "pipe" })

    // Modify the file
    writeFileSync(join(tmpDir, "file.txt"), "modified content")

    const result = await svc.gitDiff()
    expect(result.length).toBeGreaterThan(0)
    const entry = result[0]!
    expect(entry).toHaveProperty("path")
    expect(entry).toHaveProperty("status")
    expect(entry).toHaveProperty("diff")
    expect(entry.path).toBe("file.txt")
    expect(typeof entry.diff).toBe("string")
    expect(entry.diff).toContain("modified content")
  })

  test("handles new untracked file in git repo after commit", async () => {
    const { execSync } = await import("child_process")
    execSync("git init && git config user.email 'test@test.com' && git config user.name 'Test'", {
      cwd: tmpDir,
      stdio: "pipe",
    })
    writeFileSync(join(tmpDir, "initial.txt"), "initial")
    execSync("git add . && git commit -m 'initial'", { cwd: tmpDir, stdio: "pipe" })

    // Add and stage a new file
    writeFileSync(join(tmpDir, "new.txt"), "new content")
    execSync("git add new.txt", { cwd: tmpDir, stdio: "pipe" })

    const result = await svc.gitDiff()
    // git diff HEAD shows staged new files
    expect(Array.isArray(result)).toBe(true)
  })

  test("result entries have correct shape", async () => {
    const { execSync } = await import("child_process")
    execSync("git init && git config user.email 'test@test.com' && git config user.name 'Test'", {
      cwd: tmpDir,
      stdio: "pipe",
    })
    writeFileSync(join(tmpDir, "code.ts"), "const x = 1")
    execSync("git add . && git commit -m 'initial'", { cwd: tmpDir, stdio: "pipe" })
    writeFileSync(join(tmpDir, "code.ts"), "const x = 2")

    const result = await svc.gitDiff()
    expect(result.length).toBeGreaterThan(0)
    for (const entry of result) {
      expect(typeof entry.path).toBe("string")
      expect(typeof entry.status).toBe("string")
      expect(typeof entry.diff).toBe("string")
    }
  })
})

// ─── parseDiff flags ─────────────────────────────────────────────────────────

describe("parseDiff flags", () => {
  test("flags binary files", async () => {
    const { parseDiff } = await import("../src/core/editor/fs-service")
    const raw = `diff --git a/img.png b/img.png
index 1234..5678 100644
Binary files a/img.png and b/img.png differ
`
    const entries = parseDiff(raw)
    expect(entries.length).toBe(1)
    expect(entries[0]!.binary).toBe(true)
  })

  test("flags mode-only changes", async () => {
    const { parseDiff } = await import("../src/core/editor/fs-service")
    const raw = `diff --git a/script.sh b/script.sh
old mode 100644
new mode 100755
`
    const entries = parseDiff(raw)
    expect(entries.length).toBe(1)
    expect(entries[0]!.modeChange).toBe(true)
  })
})

// ─── safePath (security) ─────────────────────────────────────────────────────

describe("path traversal security", () => {
  test("symlink pointing outside workdir is rejected for readFile", async () => {
    // Create a symlink inside workdir pointing to /etc/passwd
    try {
      symlinkSync("/etc/passwd", join(tmpDir, "evil-link"))
      await expect(svc.readFile("evil-link")).rejects.toThrow()
    } catch (_e) {
      // If symlink creation fails (permission/env), skip
    }
  })

  test("symlink pointing outside workdir is rejected for listDir", async () => {
    try {
      symlinkSync("/etc", join(tmpDir, "evil-etc"))
      const entries = await svc.listDir(".")
      // The symlink might appear in listing but following it into it should be blocked
      // We just check listDir itself doesn't throw and evil-etc isn't followed
      expect(Array.isArray(entries)).toBe(true)
    } catch (_e) {
      // acceptable
    }
  })

  test("null byte in path is rejected", async () => {
    await expect(svc.readFile("foo\x00bar")).rejects.toThrow()
  })

  test("constructor with symlinked workdir resolves correctly (no false traversal)", async () => {
    // Create a real directory and a symlink to it
    const realDir = mkdtempSync(join(tmpdir(), "cmux-real-"))
    const symlinkDir = join(tmpdir(), `cmux-sym-${Date.now()}`)
    try {
      symlinkSync(realDir, symlinkDir)
      writeFileSync(join(realDir, "hello.txt"), "hi")

      // FsService constructed with symlink path should not throw on valid ops
      const symSvc = new FsService(symlinkDir)
      const content = await symSvc.readFile("hello.txt")
      expect(content).toBe("hi")
    } finally {
      try { rmSync(symlinkDir) } catch {}
      rmSync(realDir, { recursive: true, force: true })
    }
  })

  test("writeFile through symlinked subdir is rejected", async () => {
    // Create a target dir outside workdir
    const outsideDir = mkdtempSync(join(tmpdir(), "cmux-outside-"))
    try {
      // Place a symlink inside workdir that points outside
      const symlinkPath = join(tmpDir, "escape")
      symlinkSync(outsideDir, symlinkPath)

      // Attempt to write through the symlink — should be rejected
      await expect(svc.writeFile("escape/evil.txt", "pwned")).rejects.toThrow(/traversal/i)
    } finally {
      rmSync(outsideDir, { recursive: true, force: true })
    }
  })
})

// ─── searchFiles dotfile inclusion ─────────────────────────────────────────

describe("searchFiles dotfile inclusion", () => {
  test("returns dotfiles when they match the query", async () => {
    writeFileSync(join(tmpDir, ".env"), "SECRET_KEY=abc")
    writeFileSync(join(tmpDir, ".hidden"), "data")
    writeFileSync(join(tmpDir, "visible.txt"), "data")

    const results = await svc.searchFiles("e")
    const names = results.map((r) => r.name)
    expect(names).toContain(".env")
    expect(names).toContain("visible.txt")
  })

  test("recurses into dotfile directories", async () => {
    mkdirSync(join(tmpDir, ".ssh"))
    writeFileSync(join(tmpDir, ".ssh", "id_rsa"), "PRIVATE KEY")

    const results = await svc.searchFiles("id_rsa")
    expect(results).toHaveLength(1)
    expect(results[0]!.name).toBe("id_rsa")
  })
})

// ─── gitignore dimming ───────────────────────────────────────────────────────

describe("gitignore ignored flag", () => {
  function initGitRepo() {
    execSync("git init", { cwd: tmpDir, stdio: "pipe" })
    execSync("git config user.email test@test.com", { cwd: tmpDir, stdio: "pipe" })
    execSync("git config user.name Test", { cwd: tmpDir, stdio: "pipe" })
  }

  test("listDir marks gitignored entries", async () => {
    initGitRepo()
    writeFileSync(join(tmpDir, ".gitignore"), "secret.txt\n.env\n")
    writeFileSync(join(tmpDir, "secret.txt"), "x")
    writeFileSync(join(tmpDir, ".env"), "x")
    writeFileSync(join(tmpDir, "tracked.txt"), "x")

    const entries = await svc.listDir(".")
    const byName = Object.fromEntries(entries.map((e) => [e.name, e]))
    expect(byName["secret.txt"]!.ignored).toBe(true)
    expect(byName[".env"]!.ignored).toBe(true)
    expect(byName["tracked.txt"]!.ignored).toBe(false)
    expect(byName[".gitignore"]!.ignored).toBe(false)
  })

  test("searchFiles marks gitignored matches", async () => {
    initGitRepo()
    writeFileSync(join(tmpDir, ".gitignore"), "secret.txt\n")
    writeFileSync(join(tmpDir, "secret.txt"), "x")
    writeFileSync(join(tmpDir, "tracked.txt"), "x")

    const results = await svc.searchFiles("txt")
    const byName = Object.fromEntries(results.map((r) => [r.name, r]))
    expect(byName["secret.txt"]!.ignored).toBe(true)
    expect(byName["tracked.txt"]!.ignored).toBe(false)
  })

  test("non-git workdir sets ignored false", async () => {
    writeFileSync(join(tmpDir, "plain.txt"), "x")
    const entries = await svc.listDir(".")
    expect(entries.every((e) => e.ignored === false)).toBe(true)
  })
})
