import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, realpathSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { DirCache } from "./dir-cache"
import { RepoInfoCache } from "./repo-info"

function fixture() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-cache-")))
  mkdirSync(join(root, "b-dir"))
  mkdirSync(join(root, "A-dir"))
  writeFileSync(join(root, "file10.txt"), "1234")
  writeFileSync(join(root, "file2.txt"), "12")
  symlinkSync(join(root, "A-dir"), join(root, "link-to-dir"))
  symlinkSync(join(root, "nope"), join(root, "broken"))
  return root
}

const cache = (opts: Partial<ConstructorParameters<typeof DirCache>[0]> = {}) =>
  new DirCache({ repo: new RepoInfoCache(), bootId: "b1", ...opts })

test("load lists dirs first, natural order, with sizes and symlink targets", async () => {
  const root = fixture()
  const snap = (await cache().load(root)).snap
  expect(snap.entries.map((e) => e.name)).toEqual(["A-dir", "b-dir", "link-to-dir", "broken", "file2.txt", "file10.txt"])
  const byName = Object.fromEntries(snap.entries.map((e) => [e.name, e]))
  expect(byName["file10.txt"]!.size).toBe(4)
  expect(byName["link-to-dir"]).toMatchObject({ type: "symlink", target: "dir" })
  expect(byName["broken"]!.type).toBe("symlink")
  expect(byName["broken"]!.target).toBeUndefined()
  expect(snap.version).toBe("b1:1")
})

test("concurrent loads of one folder share a single read", async () => {
  const root = fixture()
  const c = cache()
  const [a, b, d] = await Promise.all([c.load(root), c.load(root), c.load(root)])
  expect(a.snap).toBe(b.snap)
  expect(b.snap).toBe(d.snap)
  // b and d arrived while a's read was in flight, so they trigger exactly one
  // extra read (see the dirty-while-loading tests below); nothing changed on
  // disk, so that extra read reports "same" and hands back a's own snapshot.
  expect(c.readCount).toBe(2)
})

test("a load that arrives mid-read re-reads once for all joiners; the first caller keeps its own result", async () => {
  const root = fixture()
  let notifyReady!: () => void
  const ready = new Promise<void>((r) => (notifyReady = r))
  let release!: () => void
  const gate = new Promise<void>((r) => (release = r))
  // A's read gathers its data (pre-write) and then blocks right before finalizing, so the write
  // below races against load B/D, not against A's own disk read.
  const c = cache({ beforeRead: async () => { notifyReady(); await gate } })
  const a = c.load(root)
  await ready
  writeFileSync(join(root, "mid.txt"), "x")
  const b = c.load(root)
  const d = c.load(root)
  release()
  const [ra, rb, rd] = await Promise.all([a, b, d])
  expect(ra.snap.entries.some((e) => e.name === "mid.txt")).toBe(false)
  expect(rb.snap.entries.some((e) => e.name === "mid.txt")).toBe(true)
  expect(rb.snap).toBe(rd.snap)
  expect(c.readCount).toBe(2)
})

test("a joined read that fails rejects its callers without an unhandled rejection", async () => {
  const root = fixture()
  const sub = join(root, "b-dir")
  let notifyReady!: () => void
  const ready = new Promise<void>((r) => (notifyReady = r))
  let release!: () => void
  const gate = new Promise<void>((r) => (release = r))
  let first = true
  const c = cache({ beforeRead: async () => { if (first) { first = false; notifyReady(); await gate } } })
  const unhandled: unknown[] = []
  const onUnhandled = (e: unknown) => { unhandled.push(e) }
  process.on("unhandledRejection", onUnhandled)
  try {
    const a = c.load(sub)
    await ready
    rmSync(sub, { recursive: true })
    const b = c.load(sub)
    release()
    await a
    await expect(b).rejects.toMatchObject({ code: "ENOENT" })
    await new Promise((r) => setTimeout(r, 20))
    expect(unhandled).toEqual([])
  } finally {
    process.off("unhandledRejection", onUnhandled)
  }
})

test("forget during an in-flight read keeps that read from re-inserting into the cache", async () => {
  const root = fixture()
  let release!: () => void
  const gate = new Promise<void>((r) => (release = r))
  const c = cache({ beforeRead: () => gate })
  const p = c.load(root)
  c.forget(root)
  release()
  await p
  expect(c.get(root)).toBeUndefined()
})

test("a subfolder's own mtime tick alone does not count as a change", async () => {
  const root = fixture()
  const c = cache()
  const first = await c.load(root)
  expect(first.changed).toBe(true)
  writeFileSync(join(root, "A-dir", "x.txt"), "x") // bumps A-dir's mtime; root's own listing is otherwise identical
  const second = await c.load(root)
  expect(second.changed).toBe(false)
  expect(second.snap).toBe(first.snap)
  expect(second.snap.version).toBe(first.snap.version)
})

test("a file's changed size and mtime do count as a change", async () => {
  const root = fixture()
  const c = cache()
  const first = await c.load(root)
  writeFileSync(join(root, "file2.txt"), "12345678")
  const second = await c.load(root)
  expect(second.changed).toBe(true)
  expect(second.snap.version).not.toBe(first.snap.version)
})

test("an unchanged reload keeps the version and reports changed=false", async () => {
  const root = fixture()
  const c = cache()
  const first = await c.load(root)
  const again = await c.load(root)
  expect(first.changed).toBe(true)
  expect(again.changed).toBe(false)
  expect(again.snap.version).toBe(first.snap.version)
  writeFileSync(join(root, "new.txt"), "n")
  const third = await c.load(root)
  expect(third.changed).toBe(true)
  expect(third.snap.version).toBe("b1:2")
})

test("large folders are truncated and report the total", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-cache-big-")))
  for (let i = 0; i < 30; i++) writeFileSync(join(root, `f${i}`), "")
  const snap = (await cache({ maxEntries: 10 }).load(root)).snap
  expect(snap.entries).toHaveLength(10)
  expect(snap.truncated).toEqual({ total: 30 })
})

test("loading a missing folder rejects with ENOENT and forgets it", async () => {
  const root = fixture()
  const c = cache()
  await c.load(join(root, "b-dir"))
  rmSync(join(root, "b-dir"), { recursive: true })
  await expect(c.load(join(root, "b-dir"))).rejects.toMatchObject({ code: "ENOENT" })
  expect(c.get(join(root, "b-dir"))).toBeUndefined()
})

test("unpinned folders are evicted least-recently-used; pinned ones stay", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-cache-lru-")))
  for (const n of ["a", "b", "c"]) mkdirSync(join(root, n))
  const c = cache({ maxDirs: 2 })
  c.pin(join(root, "a"))
  await c.load(join(root, "a"))
  await c.load(join(root, "b"))
  await c.load(join(root, "c"))
  expect(c.get(join(root, "a"))).toBeDefined()
  expect(c.get(join(root, "b"))).toBeUndefined()
  expect(c.get(join(root, "c"))).toBeDefined()
})
