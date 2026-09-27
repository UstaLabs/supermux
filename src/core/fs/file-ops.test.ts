import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, realpathSync } from "fs"
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
  expect(info).toContain(`Path=${encodeURI(join(d, "folder"))}`)
  // a second item with the same name gets a unique name
  mkdirSync(join(d, "folder"))
  await applyOp({ op: "delete", path: join(d, "folder") }, { trashDir: trash, platform: "linux" })
  expect(existsSync(join(trash, "files", "folder.2"))).toBe(true)
})
