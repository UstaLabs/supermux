import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { DirWatchers } from "./dir-watchers"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fs-watch-")))

test("a burst of changes produces one flush after the debounce", async () => {
  const d = tmp()
  const flushed: string[] = []
  const w = new DirWatchers((dir) => flushed.push(dir), { debounceMs: 60, maxWaitMs: 1_000 })
  w.watch(d)
  for (let i = 0; i < 5; i++) writeFileSync(join(d, `f${i}`), "")
  await sleep(250)
  expect(flushed).toEqual([d])
  w.closeAll()
})

test("constant churn still flushes at least every maxWait", async () => {
  const d = tmp()
  const flushed: number[] = []
  const w = new DirWatchers(() => flushed.push(Date.now()), { debounceMs: 100, maxWaitMs: 150 })
  w.watch(d)
  const end = Date.now() + 500
  let i = 0
  while (Date.now() < end) { writeFileSync(join(d, `c${i++ % 3}`), String(i)); await sleep(20) }
  await sleep(200)
  expect(flushed.length).toBeGreaterThanOrEqual(3)
  w.closeAll()
})

test("unwatch stops flushes; watch is idempotent", async () => {
  const d = tmp()
  let n = 0
  const w = new DirWatchers(() => n++, { debounceMs: 30 })
  w.watch(d); w.watch(d)
  expect(w.size).toBe(1)
  w.unwatch(d)
  writeFileSync(join(d, "x"), "")
  await sleep(120)
  expect(n).toBe(0)
})

test("when watching fails, the folder is polled instead", async () => {
  const d = tmp()
  const flushed: string[] = []
  const fallbacks: string[] = []
  const w = new DirWatchers((dir) => flushed.push(dir), {
    pollMs: 40,
    watchFn: () => { throw Object.assign(new Error("no watches"), { code: "ENOSPC" }) },
    onFallback: (dir) => fallbacks.push(dir),
  })
  w.watch(d)
  await sleep(130)
  expect(fallbacks).toEqual([d])
  expect(flushed.length).toBeGreaterThanOrEqual(2)
  w.closeAll()
})

test("a throwing flush callback does not break later flushes", async () => {
  const d = tmp()
  let calls = 0
  const w = new DirWatchers(() => { calls++; throw new Error("boom") }, { debounceMs: 30 })
  w.watch(d)
  writeFileSync(join(d, "a"), "")
  await sleep(120)
  writeFileSync(join(d, "b"), "")
  await sleep(120)
  expect(calls).toBe(2)
  w.closeAll()
})

import { EventEmitter } from "events"
import type { FSWatcher } from "fs"

function fakeWatch() {
  const listeners = new Map<string, (event: string, filename?: string | null) => void>()
  const watchFn = (dir: string, listener: (event: string, filename?: string | null) => void) => {
    listeners.set(dir, listener)
    return Object.assign(new EventEmitter(), { close() {} }) as unknown as FSWatcher
  }
  return { listeners, watchFn }
}

test("a filter drops events by file name", async () => {
  const { listeners, watchFn } = fakeWatch()
  const flushed: string[] = []
  const w = new DirWatchers((dir) => flushed.push(dir), {
    debounceMs: 10, watchFn, filter: (_dir, name) => !name?.endsWith(".lock"),
  })
  w.watch("/g")
  listeners.get("/g")!("rename", "index.lock")
  listeners.get("/g")!("change", "index.lock")
  await sleep(50)
  expect(flushed).toEqual([])
  listeners.get("/g")!("rename", "index")
  await sleep(50)
  expect(flushed).toEqual(["/g"])
  w.closeAll()
})

test("an event without a file name (the folder itself went away) marks the watch dead", async () => {
  const { listeners, watchFn } = fakeWatch()
  const flushed: string[] = []
  const w = new DirWatchers((dir) => flushed.push(dir), { debounceMs: 10, watchFn })
  w.watch("/d")
  listeners.get("/d")!("change", "a.txt")
  expect(w.isDead("/d")).toBe(false)
  listeners.get("/d")!("rename", undefined)
  expect(w.isDead("/d")).toBe(true)
  await sleep(50)
  expect(flushed).toEqual(["/d"])
  w.unwatch("/d"); w.watch("/d")
  expect(w.isDead("/d")).toBe(false)
  w.closeAll()
})

test("a watch that errored is dead", async () => {
  let em: EventEmitter | undefined
  const w = new DirWatchers(() => {}, {
    debounceMs: 10,
    watchFn: () => (em = Object.assign(new EventEmitter(), { close() {} })) as unknown as FSWatcher,
  })
  w.watch("/e")
  em!.emit("error", new Error("gone"))
  expect(w.isDead("/e")).toBe(true)
  w.closeAll()
})
