import { expect, test } from "bun:test"
import { folderReady, type FolderReadyDeps } from "./folder-ready"

/** A box where folders appear when the test says so, and time is the test's. */
function box(opts: { sessions: Array<{ id: string; workdir: string }>; waking: string[] }) {
  const made = new Set<string>()
  const waking = new Set(opts.waking)
  let slept = 0
  const deps: FolderReadyDeps = {
    sessions: () => opts.sessions,
    pending: (id) => waking.has(id),
    exists: (path) => made.has(path),
    sleep: async () => { slept++ },
    now: () => slept * 50,
  }
  return { deps, made, waking, slept: () => slept }
}

test("a session's folder being made by its wake: waits until it exists", async () => {
  const b = box({ sessions: [{ id: "s", workdir: "/wt/s" }], waking: ["s"] })
  const ready = folderReady(b.deps, "/wt/s/src")
  for (let i = 0; i < 5; i++) await Promise.resolve()
  b.made.add("/wt/s")
  await ready
  expect(b.slept()).toBeGreaterThan(0)
})

test("answers at once: a folder that exists, one no session owns, or one whose session is not being woken", async () => {
  const b = box({ sessions: [{ id: "s", workdir: "/wt/s" }, { id: "idle", workdir: "/wt/idle" }], waking: ["s"] })
  b.made.add("/home/x")
  await folderReady(b.deps, "/home/x")
  await folderReady(b.deps, "/elsewhere")
  await folderReady(b.deps, "/wt/idle")
  await folderReady(b.deps, "/wt/s-other")   // a sibling that only shares the prefix
  expect(b.slept()).toBe(0)
})

test("stops waiting when the wake ends without the folder, or after the timeout", async () => {
  const b = box({ sessions: [{ id: "s", workdir: "/wt/s" }], waking: ["s"] })
  const ready = folderReady(b.deps, "/wt/s")
  for (let i = 0; i < 5; i++) await Promise.resolve()
  b.waking.delete("s")
  await ready
  const c = box({ sessions: [{ id: "s", workdir: "/wt/s" }], waking: ["s"] })
  await folderReady(c.deps, "/wt/s", 1_000)
  expect(c.slept()).toBe(20)
})
