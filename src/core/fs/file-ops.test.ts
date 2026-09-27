import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, realpathSync, chmodSync } from "fs"
import { execSync } from "child_process"
import { tmpdir } from "os"
import { join } from "path"
import { readText, writeText, statEntry, applyOp, MAX_READ_BYTES } from "./file-ops"

const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fs-ops-")))

test("readText returns utf-8 text", async () => {
  const d = tmp()
  writeFileSync(join(d, "a.txt"), "héllo")
  expect(await readText(join(d, "a.txt"))).toBe("héllo")
})

test("readText refuses large and binary files with TOO_LARGE / BINARY", async () => {
  const d = tmp()
  writeFileSync(join(d, "big"), Buffer.alloc(MAX_READ_BYTES + 1, 65))
  writeFileSync(join(d, "bin"), Buffer.from([1, 0, 2]))
  await expect(readText(join(d, "big"))).rejects.toMatchObject({ code: "TOO_LARGE" })
  await expect(readText(join(d, "bin"))).rejects.toMatchObject({ code: "BINARY" })
})

test("writeText is atomic, creates parents and returns size + mtime", async () => {
  const d = tmp()
  const r = await writeText(join(d, "x", "y", "z.txt"), "abc")
  expect(r.size).toBe(3)
  expect(typeof r.mtime).toBe("number")
  expect(readFileSync(join(d, "x", "y", "z.txt"), "utf-8")).toBe("abc")
  expect(readdirSync(join(d, "x", "y"))).toEqual(["z.txt"])
})

test("readText refuses non-regular files (FIFO) with EINVAL", async () => {
  const d = tmp()
  const fifo = join(d, "myfifo")
  try {
    execSync(`mkfifo ${fifo}`)
  } catch {
    return // mkfifo unavailable; skip
  }
  await expect(readText(fifo)).rejects.toMatchObject({ code: "EINVAL" })
})

test("writeText uses a unique temp name so concurrent writes to the same path don't collide, and leaves no .tmp files", async () => {
  const d = tmp()
  const p = join(d, "shared.txt")
  const [r1, r2] = await Promise.all([writeText(p, "AAAA"), writeText(p, "BBBBBB")])
  expect([r1.size, r2.size].sort()).toEqual([4, 6])
  const finalContent = readFileSync(p, "utf-8")
  expect(["AAAA", "BBBBBB"]).toContain(finalContent)
  const leftovers = readdirSync(d).filter((f) => f.includes(".tmp"))
  expect(leftovers).toEqual([])
})

test("statEntry describes one path with its real path", async () => {
  const d = tmp()
  writeFileSync(join(d, "f"), "12")
  expect(await statEntry(join(d, "f"))).toMatchObject({ name: "f", type: "file", size: 2, real: join(d, "f") })
  await expect(statEntry(join(d, "missing"))).rejects.toMatchObject({ code: "ENOENT" })
})

test("rename, move, mkdir and touch", async () => {
  const d = tmp()
  writeFileSync(join(d, "a"), "1")
  await applyOp({ op: "rename", path: join(d, "a"), to: join(d, "b") })
  expect(existsSync(join(d, "b"))).toBe(true)
  await applyOp({ op: "mkdir", path: join(d, "p", "q") })
  await applyOp({ op: "move", path: join(d, "b"), to: join(d, "p", "q", "b") })
  expect(existsSync(join(d, "p", "q", "b"))).toBe(true)
  await applyOp({ op: "touch", path: join(d, "t") })
  expect(readFileSync(join(d, "t"), "utf-8")).toBe("")
  await expect(applyOp({ op: "touch", path: join(d, "t") })).rejects.toMatchObject({ code: "EEXIST" })
  writeFileSync(join(d, "c"), "")
  await expect(applyOp({ op: "rename", path: join(d, "c"), to: join(d, "t") })).rejects.toMatchObject({ code: "EEXIST" })
})

test("delete moves the entry to the trash with a .trashinfo record", async () => {
  const d = tmp()
  const trash = tmp()
  mkdirSync(join(d, "folder"))
  writeFileSync(join(d, "folder", "x"), "1")
  await applyOp({ op: "delete", path: join(d, "folder") }, { trashDir: trash, platform: "linux" })
  expect(existsSync(join(d, "folder"))).toBe(false)
  expect(existsSync(join(trash, "files", "folder", "x"))).toBe(true)
  const info = readFileSync(join(trash, "info", "folder.trashinfo"), "utf-8")
  expect(info).toContain("[Trash Info]")
  const expectedPath = encodeURI(join(d, "folder")).replace(/#/g, "%23").replace(/\?/g, "%3F")
  expect(info).toContain(`Path=${expectedPath}`)
  expect(info).toMatch(/DeletionDate=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\n/)
  // a second item with the same name gets a unique name
  mkdirSync(join(d, "folder"))
  await applyOp({ op: "delete", path: join(d, "folder") }, { trashDir: trash, platform: "linux" })
  expect(existsSync(join(trash, "files", "folder.2"))).toBe(true)
})

test("delete percent-encodes # and ? in the trashinfo Path", async () => {
  const d = tmp()
  const trash = tmp()
  writeFileSync(join(d, "na#me?.txt"), "1")
  await applyOp({ op: "delete", path: join(d, "na#me?.txt") }, { trashDir: trash, platform: "linux" })
  const info = readFileSync(join(trash, "info", "na#me?.txt.trashinfo"), "utf-8")
  const expectedPath = encodeURI(join(d, "na#me?.txt")).replace(/#/g, "%23").replace(/\?/g, "%3F")
  expect(info).toContain(`Path=${expectedPath}`)
  expect(expectedPath).not.toContain("#")
  expect(expectedPath).not.toMatch(/\?/)
})

test("delete removes the .trashinfo file if the move fails for a reason other than EXDEV", async () => {
  if (process.getuid && process.getuid() === 0) return // skip when running as root
  const d = tmp()
  const trash = tmp()
  const roDir = join(d, "ro")
  mkdirSync(roDir)
  writeFileSync(join(roDir, "victim"), "1")
  chmodSync(roDir, 0o555)
  try {
    await expect(applyOp({ op: "delete", path: join(roDir, "victim") }, { trashDir: trash, platform: "linux" })).rejects.toMatchObject({
      code: "EACCES",
    })
    expect(existsSync(join(trash, "info", "victim.trashinfo"))).toBe(false)
  } finally {
    chmodSync(roDir, 0o755)
  }
})
