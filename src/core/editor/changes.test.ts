import { describe, test, expect } from "bun:test"
import { parseRawNumstat, listChanges, MAX_LIST_FILES, readBaseBlob, BLOB_LIMIT } from "./changes"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync, mkdirSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

const Z = "0000000000000000000000000000000000000000"
const A = "a".repeat(40)
const B = "b".repeat(40)

describe("parseRawNumstat", () => {
  test("modify, add, delete, rename, binary, typechange", () => {
    const raw = [
      `:100644 100644 ${A} ${Z} M`, "src/a.ts",
      `:000000 100644 ${Z} ${Z} A`, "src/new.ts",
      `:100644 000000 ${B} ${Z} D`, "gone.txt",
      `:100644 100644 ${A} ${Z} R087`, "old name.ts", "new name.ts",
      `:100644 100644 ${B} ${Z} M`, "img/logo.png",
      `:100644 120000 ${A} ${Z} T`, "link",
    ].join("\0") + "\0"
    const numstat = [
      "12\t3\tsrc/a.ts",
      "40\t0\tsrc/new.ts",
      "0\t7\tgone.txt",
      "1\t1\t", "old name.ts", "new name.ts",
      "-\t-\timg/logo.png",
      "1\t1\tlink",
    ].join("\0") + "\0"

    expect(parseRawNumstat(raw, numstat)).toEqual([
      { path: "src/a.ts", status: "modified", added: 12, removed: 3, binary: false, oldPath: null, baseBlob: A },
      { path: "src/new.ts", status: "added", added: 40, removed: 0, binary: false, oldPath: null, baseBlob: null },
      { path: "gone.txt", status: "deleted", added: 0, removed: 7, binary: false, oldPath: null, baseBlob: B },
      { path: "new name.ts", status: "renamed", added: 1, removed: 1, binary: false, oldPath: "old name.ts", baseBlob: A },
      { path: "img/logo.png", status: "modified", added: null, removed: null, binary: true, oldPath: null, baseBlob: B },
      { path: "link", status: "typechange", added: 1, removed: 1, binary: false, oldPath: null, baseBlob: A },
    ])
  })

  test("empty output is an empty list", () => {
    expect(parseRawNumstat("", "")).toEqual([])
  })

  test("a path missing from numstat keeps null counts", () => {
    const raw = `:100644 100644 ${A} ${Z} M\0x.ts\0`
    expect(parseRawNumstat(raw, "")[0]).toMatchObject({ path: "x.ts", added: null, removed: null, binary: false })
  })

  test("a path containing a tab keeps its counts", () => {
    const raw = `:100644 100644 ${A} ${Z} M\0we\tird.ts\0`
    const numstat = "5\t2\twe\tird.ts\0"
    expect(parseRawNumstat(raw, numstat)[0]).toMatchObject({ path: "we\tird.ts", added: 5, removed: 2 })
  })

  test("a copy line is a plain add", () => {
    const raw = `:100644 100644 ${A} ${Z} C100\0src.ts\0dup.ts\0`
    const numstat = "0\t0\t\0src.ts\0dup.ts\0"
    expect(parseRawNumstat(raw, numstat)).toEqual([
      { path: "dup.ts", status: "added", added: 0, removed: 0, binary: false, oldPath: null, baseBlob: null },
    ])
  })

  test("a binary rename is binary with null counts and oldPath", () => {
    const raw = `:100644 100644 ${A} ${Z} R100\0old.png\0new.png\0`
    const numstat = "-\t-\t\0old.png\0new.png\0"
    expect(parseRawNumstat(raw, numstat)).toEqual([
      { path: "new.png", status: "renamed", added: null, removed: null, binary: true, oldPath: "old.png", baseBlob: A },
    ])
  })
})

function git(cwd: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
}

function repo(): { dir: string; base: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-changes-"))
  git(dir, "init", "-q", "-b", "main")
  git(dir, "config", "user.email", "t@t")
  git(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "a.txt"), "one\ntwo\n")
  writeFileSync(join(dir, "gone.txt"), "bye\n")
  git(dir, "add", ".")
  git(dir, "commit", "-qm", "c1")
  return { dir, base: git(dir, "rev-parse", "HEAD") }
}

describe("listChanges", () => {
  test("tracked edits, deletes and untracked text/binary files, with base blobs and counts", async () => {
    const { dir, base } = repo()
    const aBlob = git(dir, "rev-parse", `${base}:a.txt`)
    writeFileSync(join(dir, "a.txt"), "one\nTWO\nthree\n")
    execFileSync("rm", [join(dir, "gone.txt")])
    writeFileSync(join(dir, "new.txt"), "x\ny\n")
    writeFileSync(join(dir, "bin.dat"), Buffer.from([1, 0, 2, 3]))

    const res = await listChanges(dir, { "": base })
    expect(res.repos).toHaveLength(1)
    const r = res.repos[0]!
    expect(r.repo).toBe("")
    expect(r.baseSha).toBe(base)
    expect(r.truncated).toBe(false)
    expect(r.total).toBe(4)
    const by = Object.fromEntries(r.files.map((f) => [f.path, f]))
    expect(by["a.txt"]).toMatchObject({ status: "modified", added: 2, removed: 1, baseBlob: aBlob, binary: false, size: 14 })
    expect(by["gone.txt"]).toMatchObject({ status: "deleted", added: 0, removed: 1, size: null })
    expect(by["new.txt"]).toMatchObject({ status: "added", added: 2, removed: 0, baseBlob: null, binary: false, size: 4 })
    expect(by["bin.dat"]).toMatchObject({ status: "added", added: null, removed: null, binary: true, baseBlob: null, size: 4 })
  })

  test("a clean repo is omitted", async () => {
    const { dir, base } = repo()
    expect((await listChanges(dir, { "": base })).repos).toEqual([])
  })

  test("the head base spec diffs against HEAD", async () => {
    const { dir } = repo()
    writeFileSync(join(dir, "a.txt"), "changed\n")
    git(dir, "commit", "-qam", "c2")
    writeFileSync(join(dir, "a.txt"), "changed again\n")
    const res = await listChanges(dir, {}, undefined, "head")
    expect(res.repos[0]!.baseSha).toBe(git(dir, "rev-parse", "HEAD"))
    expect(res.repos[0]!.files.map((f) => f.path)).toEqual(["a.txt"])
  })

  test("the list is capped and says so", async () => {
    const { dir, base } = repo()
    mkdirSync(join(dir, "many"))
    for (let i = 0; i < MAX_LIST_FILES + 5; i++) writeFileSync(join(dir, "many", `f${i}.txt`), "x\n")
    const r = (await listChanges(dir, { "": base })).repos[0]!
    expect(r.files).toHaveLength(MAX_LIST_FILES)
    expect(r.truncated).toBe(true)
    expect(r.total).toBe(MAX_LIST_FILES + 5)
  }, 30_000)

  test("a tracked edit survives a huge untracked listing", async () => {
    const { dir, base } = repo()
    writeFileSync(join(dir, "a.txt"), "edited\n")
    mkdirSync(join(dir, "many"))
    for (let i = 0; i < MAX_LIST_FILES + 100; i++) writeFileSync(join(dir, "many", `f${i}.txt`), "x\n")
    const r = (await listChanges(dir, { "": base })).repos[0]!
    expect(r.files.some((f) => f.path === "a.txt")).toBe(true)
    expect(r.files).toHaveLength(MAX_LIST_FILES)
    expect(r.total).toBe(MAX_LIST_FILES + 101)
    expect(r.truncated).toBe(true)
  }, 30_000)

  test("untracked symlinks are never followed", async () => {
    const { dir, base } = repo()
    symlinkSync("/dev/zero", join(dir, "z"))
    writeFileSync(join(dir, "target.txt"), "a\nb\nc\n")
    symlinkSync("target.txt", join(dir, "link"))
    const r = (await listChanges(dir, { "": base })).repos[0]!
    const by = Object.fromEntries(r.files.map((f) => [f.path, f]))
    expect(by["z"]).toMatchObject({ status: "added", added: 1, removed: 0, binary: false, size: "/dev/zero".length })
    expect(by["link"]).toMatchObject({ added: 1, removed: 0, binary: false, size: "target.txt".length })
    expect(by["target.txt"]).toMatchObject({ added: 3 })
  }, 10_000)

  test("a missing workdir lists nothing", async () => {
    expect((await listChanges("/nonexistent/mux-changes", {})).repos).toEqual([])
  })
})

describe("readBaseBlob", () => {
  test("returns the blob text", async () => {
    const { dir, base } = repo()
    const sha = git(dir, "rev-parse", `${base}:a.txt`)
    expect(await readBaseBlob(dir, sha)).toEqual({ ok: true, text: "one\ntwo\n" })
  })

  test("rejects a bad sha without running git", async () => {
    const { dir } = repo()
    expect(await readBaseBlob(dir, "HEAD")).toEqual({ ok: false, code: "BAD_SHA" })
  })

  test("a missing blob is MISSING", async () => {
    const { dir } = repo()
    expect(await readBaseBlob(dir, "c".repeat(40))).toEqual({ ok: false, code: "MISSING" })
  })

  test("binary blobs are BINARY", async () => {
    const { dir } = repo()
    writeFileSync(join(dir, "b.dat"), Buffer.from([1, 0, 2]))
    git(dir, "add", "b.dat")
    git(dir, "commit", "-qm", "bin")
    const sha = git(dir, "rev-parse", "HEAD:b.dat")
    expect(await readBaseBlob(dir, sha)).toEqual({ ok: false, code: "BINARY" })
  })

  test("large blobs are TOO_LARGE unless forced", async () => {
    const { dir } = repo()
    writeFileSync(join(dir, "big.txt"), "x".repeat(BLOB_LIMIT + 10))
    git(dir, "add", "big.txt")
    git(dir, "commit", "-qm", "big")
    const sha = git(dir, "rev-parse", "HEAD:big.txt")
    expect(await readBaseBlob(dir, sha)).toEqual({ ok: false, code: "TOO_LARGE", size: BLOB_LIMIT + 10 })
    const forced = await readBaseBlob(dir, sha, { force: true })
    expect(forced.ok && forced.text.length).toBe(BLOB_LIMIT + 10)
  })
})

describe("readBaseBlob edge", () => {
  test("a non-numeric size is MISSING", async () => {
    const { dir } = repo()
    // an all-zero sha is a valid 40-hex string git cannot resolve
    expect(await readBaseBlob(dir, "0".repeat(40))).toEqual({ ok: false, code: "MISSING" })
  })
})
