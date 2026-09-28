// src/core/fs/file-system-service.subs.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { EventEmitter } from "events"
import type { FSWatcher } from "fs"
import { FileSystemService } from "./file-system-service"
import type { FsFrame } from "./types"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fss-sub-")))

function harness(extra: Partial<ConstructorParameters<typeof FileSystemService<string>>[0]> = {}) {
  const frames: Array<{ sock: string; f: FsFrame }> = []
  const fss = new FileSystemService<string>({
    bootId: "b", emit: (sock, f) => frames.push({ sock, f }),
    debounceMs: 30, graceMs: 50, repoDebounceMs: 30, ...extra,
  })
  const waitFor = async (pred: (f: FsFrame, sock: string) => boolean, ms = 2_000) => {
    const end = Date.now() + ms
    while (Date.now() < end) {
      const hit = frames.find((x) => pred(x.f, x.sock))
      if (hit) return hit
      await sleep(10)
    }
    throw new Error("timed out; frames: " + JSON.stringify(frames.map((x) => x.f.type)))
  }
  return { fss, frames, waitFor }
}

test("subscribe replies with a snapshot, then pushes a new one when the folder changes", async () => {
  const d = tmp()
  const { fss, frames, waitFor } = harness()
  await fss.subscribe("s1", d)
  expect(frames[0]!.f).toMatchObject({ type: "fs_dir", path: d, version: "b:1", entries: [] })
  writeFileSync(join(d, "new.txt"), "")
  const next = await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.length === 1)
  expect(next.f).toMatchObject({ version: "b:2" })
  fss.close()
})

test("since equal to the current version gets unchanged:true", async () => {
  const d = tmp()
  const { fss, frames } = harness()
  await fss.subscribe("s1", d)
  await fss.subscribe("s2", d, "b:1")
  expect(frames[1]!.f).toEqual({ type: "fs_dir", path: d, version: "b:1", unchanged: true })
  fss.close()
})

test("a folder shared by two sockets under two paths is watched once and pushed to both", async () => {
  const d = tmp()
  mkdirSync(join(d, "real"))
  execFileSync("ln", ["-s", join(d, "real"), join(d, "link")])
  const { fss, frames, waitFor } = harness()
  await fss.subscribe("s1", join(d, "real"))
  await fss.subscribe("s2", join(d, "link"))
  expect(fss.watcherCount).toBe(1)
  writeFileSync(join(d, "real", "x"), "")
  await waitFor((f, s) => s === "s1" && f.type === "fs_dir" && "entries" in f && f.entries.length === 1)
  const two = await waitFor((f, s) => s === "s2" && f.type === "fs_dir" && "entries" in f && f.entries.length === 1)
  expect(two.f).toMatchObject({ path: join(d, "link") })
  expect(frames.filter((x) => x.f.type === "fs_dir").length).toBe(4)
  fss.close()
})

test("deleting a subscribed folder sends fs_gone and drops the subscription", async () => {
  const d = tmp()
  mkdirSync(join(d, "sub"))
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", join(d, "sub"))
  rmSync(join(d, "sub"), { recursive: true })
  await waitFor((f) => f.type === "fs_gone")
  expect(fss.watcherCount).toBe(0)
  fss.close()
})

test("subscribing to a missing folder sends fs_err and holds nothing", async () => {
  const { fss, frames } = harness()
  await fss.subscribe("s1", "/no/such/dir")
  expect(frames[0]!.f).toMatchObject({ type: "fs_err", path: "/no/such/dir", code: "ENOENT" })
  expect(fss.watcherCount).toBe(0)
  await fss.subscribe("s1", "relative")
  expect(frames[1]!.f).toMatchObject({ type: "fs_err", code: "EINVAL" })
  fss.close()
})

test("the last unsubscribe stops the watcher after the grace period; dropSocket does too", async () => {
  const d = tmp()
  const { fss } = harness()
  await fss.subscribe("s1", d)
  fss.unsubscribe("s1", d)
  expect(fss.watcherCount).toBe(1)
  await sleep(120)
  expect(fss.watcherCount).toBe(0)
  await fss.subscribe("s2", d)
  fss.dropSocket("s2")
  await sleep(120)
  expect(fss.watcherCount).toBe(0)
  fss.close()
})

test("the socket cap answers TOO_MANY_SUBS", async () => {
  const a = tmp(), b = tmp()
  const { fss, frames } = harness({ maxSubsPerSocket: 1 })
  await fss.subscribe("s1", a)
  await fss.subscribe("s1", b)
  expect(frames[1]!.f).toMatchObject({ type: "fs_err", code: "TOO_MANY_SUBS" })
  fss.close()
})

test("git add pushes the new status of a subscribed folder (git dir watch)", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  writeFileSync(join(d, "a.txt"), "a")
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", d)
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "a.txt" && e.git === "?"))
  execFileSync("git", ["add", "a.txt"], { cwd: d })
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "a.txt" && e.git === "A"), 4_000)
  fss.close()
})

test("a deleted folder recreated at the same path can be subscribed again and is watched afresh", async () => {
  const d = tmp()
  mkdirSync(join(d, "sub"))
  const { fss, frames, waitFor } = harness()
  await fss.subscribe("s1", join(d, "sub"))
  rmSync(join(d, "sub"), { recursive: true })
  await waitFor((f) => f.type === "fs_gone")
  mkdirSync(join(d, "sub"))
  const before = frames.length
  await fss.subscribe("s1", join(d, "sub"))
  expect(frames[before]!.f).toMatchObject({ type: "fs_dir", path: join(d, "sub"), entries: [] })
  expect(fss.watcherCount).toBe(1)
  writeFileSync(join(d, "sub", "again.txt"), "")
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "again.txt"))
  fss.close()
})

test("a folder replaced (deleted and recreated) before the flush is re-watched", async () => {
  const d = tmp()
  mkdirSync(join(d, "sub"))
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", join(d, "sub"))
  await sleep(50) // let the inode be recorded
  rmSync(join(d, "sub"), { recursive: true })
  mkdirSync(join(d, "sub"))
  writeFileSync(join(d, "sub", "first.txt"), "")
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "first.txt"))
  writeFileSync(join(d, "sub", "second.txt"), "")
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "second.txt"))
  expect(fss.watcherCount).toBe(1)
  fss.close()
})

test("search sees files written through the service and files appearing in a watched folder", async () => {
  const d = tmp()
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", d)
  expect(await fss.search(d, "zebra")).toEqual([])
  await fss.write(join(d, "zebra.txt"), "z")
  expect((await fss.search(d, "zebra")).map((h) => h.name)).toEqual(["zebra.txt"])
  writeFileSync(join(d, "yak.txt"), "")
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "yak.txt"))
  expect((await fss.search(d, "yak")).map((h) => h.name)).toEqual(["yak.txt"])
  fss.close()
})

test("search in an unwatched scope sees a file written through the service", async () => {
  const d = tmp()
  mkdirSync(join(d, "deep"))
  const { fss } = harness()
  expect(await fss.search(d, "walrus")).toEqual([])
  await fss.write(join(d, "deep", "walrus.md"), "w")
  expect((await fss.search(d, "walrus")).map((h) => h.path)).toEqual([join(d, "deep", "walrus.md")])
  fss.close()
})

test("git init in a watched folder is noticed: entries get git status and the git dir is watched", async () => {
  const d = tmp()
  writeFileSync(join(d, "a.txt"), "a")
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", d)
  execFileSync("git", ["init", "-q"], { cwd: d })
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "a.txt" && e.git === "?"), 4_000)
  execFileSync("git", ["add", "a.txt"], { cwd: d })
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "a.txt" && e.git === "A"), 4_000)
  fss.close()
})

const entriesOf = (f: FsFrame) => (f.type === "fs_dir" && "entries" in f ? f.entries : undefined)

test("an idle repo settles: git's own reads never feed the git-dir watcher (no status loop)", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  writeFileSync(join(d, "a.txt"), "a")
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", d)
  writeFileSync(join(d, "b.txt"), "b")
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "b.txt"))
  await sleep(600)
  const settled = fss.gitFlushCount
  const loads = fss.repo.loadCount
  await sleep(2_000)
  expect(fss.gitFlushCount).toBe(settled)
  expect(fss.repo.loadCount).toBe(loads)
  fss.close()
}, 10_000)

test("a socket dropped while its subscribe is resolving holds nothing and gets nothing", async () => {
  const d = tmp()
  const { fss, frames } = harness()
  const p = fss.subscribe("s1", d)
  fss.dropSocket("s1")
  await p
  await sleep(150)
  expect(fss.watcherCount).toBe(0)
  expect(fss.cache.isPinned(d)).toBe(false)
  expect(frames).toEqual([])
  fss.close()
})

test("a watch reporting the folder itself went away (no file name) is re-watched even when the inode is reused", async () => {
  const d = tmp()
  const calls: Array<{ dir: string; listener: (event: string, filename?: string | null) => void }> = []
  const watchFn = (dir: string, listener: (event: string, filename?: string | null) => void) => {
    calls.push({ dir, listener })
    return Object.assign(new EventEmitter(), { close() {} }) as unknown as FSWatcher
  }
  const { fss, waitFor } = harness({ watchFn })
  await fss.subscribe("s1", d)
  await sleep(50)
  calls[0]!.listener("rename", undefined) // the inode is the same: only the dead flag can tell
  const end = Date.now() + 1_000
  while (calls.filter((c) => c.dir === d).length < 2 && Date.now() < end) await sleep(10)
  expect(calls.filter((c) => c.dir === d).length).toBe(2)
  expect(fss.watcherCount).toBe(1)
  writeFileSync(join(d, "x.txt"), "")
  calls.at(-1)!.listener("rename", "x.txt")
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "x.txt"))
  fss.close()
})

test("changes across watched folders of one repo are coalesced into few git reads, and every folder ends right", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  const subs = [0, 1, 2, 3, 4].map((i) => join(d, `f${i}`))
  for (const s of subs) mkdirSync(s)
  const { fss } = harness({ repoDebounceMs: 150 })
  await fss.subscribe("s1", d)
  for (const s of subs) await fss.subscribe("s1", s)
  await sleep(700)
  const before = fss.repo.loadCount
  for (let i = 0; i < 20; i++) writeFileSync(join(subs[i % 5]!, `n${i}.txt`), String(i))
  await sleep(1_500)
  expect(fss.repo.loadCount - before).toBeLessThanOrEqual(3)
  for (const [i, s] of subs.entries()) {
    const snap = await fss.list(s)
    expect(snap.entries.map((e) => e.name).sort()).toEqual([0, 1, 2, 3].map((k) => `n${i + k * 5}.txt`).sort())
    expect(snap.entries.every((e) => e.git === "?")).toBe(true)
  }
  const root = await fss.list(d)
  expect(root.entries.filter((e) => e.name.startsWith("f")).every((e) => e.git === "*")).toBe(true)
  fss.close()
}, 10_000)

test("an emit that throws for one socket neither starves the others nor rejects unhandled", async () => {
  const d = tmp()
  const rejections: unknown[] = []
  const onRej = (e: unknown) => { rejections.push(e) }
  process.on("unhandledRejection", onRej)
  try {
    const good: FsFrame[] = []
    const fss = new FileSystemService<string>({
      bootId: "b", debounceMs: 30, graceMs: 50, repoDebounceMs: 30,
      emit: (sock, f) => { if (sock === "bad") throw new Error("socket closed"); good.push(f) },
    })
    await fss.subscribe("bad", d)
    await fss.subscribe("good", d)
    writeFileSync(join(d, "n.txt"), "")
    const end = Date.now() + 2_000
    while (!good.some((f) => entriesOf(f)?.some((e) => e.name === "n.txt")) && Date.now() < end) await sleep(10)
    expect(good.some((f) => entriesOf(f)?.some((e) => e.name === "n.txt"))).toBe(true)
    await sleep(100)
    expect(rejections).toEqual([])
    fss.close()
  } finally {
    process.off("unhandledRejection", onRej)
  }
})

test("after close no watcher (folder or git dir) is started", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  const { fss } = harness()
  const p = fss.subscribe("s1", d)
  fss.close()
  await p
  await sleep(200)
  expect(fss.watcherCount).toBe(0)
  expect(fss.gitWatcherCount).toBe(0)

  const e = tmp()
  execFileSync("git", ["init", "-q"], { cwd: e })
  const h = harness()
  await h.fss.subscribe("s1", e) // folder watched; git dir watch still resolving
  h.fss.close()
  await sleep(300)
  expect(h.fss.gitWatcherCount).toBe(0)
})

test("a git dir watch that died (.git removed) is re-established on the next folder flush", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  writeFileSync(join(d, "a.txt"), "a")
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", d)
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "a.txt" && e.git === "?"))
  await sleep(200)
  rmSync(join(d, ".git"), { recursive: true })
  await waitFor((f) => !!entriesOf(f) && !entriesOf(f)!.some((e) => e.name === ".git"))
  await sleep(200)
  execFileSync("git", ["init", "-q"], { cwd: d })
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === ".git"))
  await sleep(300)
  execFileSync("git", ["add", "a.txt"], { cwd: d })
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "a.txt" && e.git === "A"), 4_000)
  fss.close()
}, 10_000)

test("a folder event that changes nothing runs no git status; a real change does", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  writeFileSync(join(d, "a.txt"), "a")
  const calls: Array<{ dir: string; listener: (event: string, filename?: string | null) => void }> = []
  const watchFn = (dir: string, listener: (event: string, filename?: string | null) => void) => {
    calls.push({ dir, listener })
    return Object.assign(new EventEmitter(), { close() {} }) as unknown as FSWatcher
  }
  const { fss, frames, waitFor } = harness({ watchFn })
  await fss.subscribe("s1", d)
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "a.txt" && e.git === "?"))
  await sleep(300)
  const loads = fss.repo.loadCount
  const nFrames = frames.length
  const folder = () => calls.filter((c) => c.dir === d).at(-1)!
  for (let i = 0; i < 3; i++) { folder().listener("change", "a.txt"); await sleep(80) }
  await sleep(300)
  expect(fss.repo.loadCount).toBe(loads)
  expect(frames.length).toBe(nFrames)
  writeFileSync(join(d, "b.txt"), "b")
  folder().listener("rename", "b.txt")
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "b.txt" && e.git === "?"))
  expect(fss.repo.loadCount).toBeGreaterThan(loads)
  fss.close()
})

test("the polling fallback runs git status only when a poll finds a change", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  writeFileSync(join(d, "a.txt"), "a")
  const watchFn = (dir: string, listener: (event: string, filename?: string | null) => void): FSWatcher => {
    if (dir === d) throw Object.assign(new Error("ENOSPC"), { code: "ENOSPC" })
    return Object.assign(new EventEmitter(), { close() {} }) as unknown as FSWatcher
  }
  const { fss, waitFor } = harness({ watchFn, pollMs: 40 })
  await fss.subscribe("s1", d)
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "a.txt" && e.git === "?"))
  await sleep(300)
  const loads = fss.repo.loadCount
  await sleep(500) // ~12 polls of an idle folder
  expect(fss.repo.loadCount).toBe(loads)
  writeFileSync(join(d, "c.txt"), "c")
  await waitFor((f) => !!entriesOf(f)?.some((e) => e.name === "c.txt" && e.git === "?"))
  fss.close()
})
