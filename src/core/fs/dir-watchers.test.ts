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
