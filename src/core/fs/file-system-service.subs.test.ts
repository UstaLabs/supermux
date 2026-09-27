// src/core/fs/file-system-service.subs.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { FileSystemService } from "./file-system-service"
import type { FsFrame } from "./types"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fss-sub-")))

function harness(extra: Partial<ConstructorParameters<typeof FileSystemService<string>>[0]> = {}) {
  const frames: Array<{ sock: string; f: FsFrame }> = []
  const fss = new FileSystemService<string>({
    bootId: "b", emit: (sock, f) => frames.push({ sock, f }),
    debounceMs: 30, graceMs: 50, ...extra,
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
  expect(await fss.search(d, "yak")).toEqual([])
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
