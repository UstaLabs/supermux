// src/core/fs/file-system-service.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FileSystemService } from "./file-system-service"

const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fss-")))

test("list returns a snapshot addressed by the path the caller used", async () => {
  const d = tmp()
  mkdirSync(join(d, "real"))
  writeFileSync(join(d, "real", "a"), "")
  symlinkSync(join(d, "real"), join(d, "link"))
  const fss = new FileSystemService({ bootId: "t" })
  const snap = await fss.list(join(d, "link") + "/")
  expect(snap.path).toBe(join(d, "link"))
  expect(snap.real).toBe(join(d, "real"))
  expect(snap.entries.map((e) => e.name)).toEqual(["a"])
})

test("list of an unwatched folder always re-reads it", async () => {
  const d = tmp()
  const fss = new FileSystemService({ bootId: "t" })
  expect((await fss.list(d)).entries).toHaveLength(0)
  writeFileSync(join(d, "new"), "")
  expect((await fss.list(d)).entries.map((e) => e.name)).toEqual(["new"])
})

test("relative paths are rejected with EINVAL", async () => {
  const fss = new FileSystemService({ bootId: "t" })
  await expect(fss.list("relative/path")).rejects.toMatchObject({ code: "EINVAL" })
})

test("read, write, stat, search and op go through one service", async () => {
  const d = tmp()
  const fss = new FileSystemService({ bootId: "t", trashDir: tmp() })
  await fss.write(join(d, "src", "FileTree.kt"), "hello")
  expect(await fss.read(join(d, "src", "FileTree.kt"))).toBe("hello")
  expect((await fss.stat(join(d, "src"))).type).toBe("dir")
  expect((await fss.search(d, "ftree", 10))[0]!.path).toBe(join(d, "src", "FileTree.kt"))
  await fss.op({ op: "rename", path: join(d, "src", "FileTree.kt"), to: join(d, "src", "Tree.kt") })
  expect((await fss.list(join(d, "src"))).entries.map((e) => e.name)).toEqual(["Tree.kt"])
  await expect(fss.op({ op: "rename", path: "src/x", to: join(d, "y") })).rejects.toMatchObject({ code: "EINVAL" })
})

test("listing a big folder does not block the event loop", async () => {
  const d = tmp()
  for (let i = 0; i < 5_000; i++) writeFileSync(join(d, `f${i}.txt`), "")
  const fss = new FileSystemService({ bootId: "t" })
  let maxGap = 0
  let last = performance.now()
  const timer = setInterval(() => { const now = performance.now(); maxGap = Math.max(maxGap, now - last); last = now }, 5)
  await fss.list(d)
  clearInterval(timer)
  // Generous bound for a loaded CI box; the old sync implementation blocked for the whole listing.
  expect(maxGap).toBeLessThan(250)
})
