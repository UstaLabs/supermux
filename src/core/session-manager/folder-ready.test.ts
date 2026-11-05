import { expect, test } from "bun:test"
import { folderReady, type FolderReadyDeps } from "./folder-ready"

/** A box where folders appear when the test says so, and time is the test's. */
function box(opts: { sessions: Array<{ id: string; workdir: string }>; waking: string[] }) {
  const made = new Set<string>()
  const waking = new Set(opts.waking)
  const checkingOut = new Set<string>()
  let slept = 0
  const deps: FolderReadyDeps = {
    sessions: () => opts.sessions,
    pending: (id) => waking.has(id),
    exists: (path) => made.has(path),
    checkoutsInFlight: () => [...checkingOut],
    sleep: async () => { slept++ },
    now: () => slept * 50,
  }
  return { deps, made, waking, checkingOut, slept: () => slept }
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

test("a folder git is still checking files out into: waits until the checkout is done, though the folder exists", async () => {
  const b = box({ sessions: [{ id: "s", workdir: "/wt/s" }], waking: ["s"] })
  b.made.add("/wt/s")
  b.checkingOut.add("/wt/s")
  const ready = folderReady(b.deps, "/wt/s")
  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(b.slept()).toBeGreaterThan(0)
  b.checkingOut.delete("/wt/s")
  await ready
  // Another folder is not held up by it.
  b.checkingOut.add("/wt/s")
  const before = b.slept()
  b.made.add("/home/x")
  await folderReady(b.deps, "/home/x")
  expect(b.slept()).toBe(before)
})

// One rule compares the path with a session's folder and with a checkout's (the host's, through relative()): the same
// folder spelled another way still holds it. Here a trailing separator; on Windows also `/` for `\` and another letter
// case, which left every waking session's files pane unheld there.
test("a folder spelled another way (a trailing separator) still holds the path", async () => {
  const b = box({ sessions: [{ id: "s", workdir: "/wt/s/" }], waking: ["s"] })
  const ready = folderReady(b.deps, "/wt/s/src")
  for (let i = 0; i < 5; i++) await Promise.resolve()
  b.made.add("/wt/s/")
  await ready
  expect(b.slept()).toBeGreaterThan(0)
  const c = box({ sessions: [], waking: [] })
  c.made.add("/wt/t")
  c.checkingOut.add("/wt/t/")
  const checkout = folderReady(c.deps, "/wt/t")
  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(c.slept()).toBeGreaterThan(0)
  c.checkingOut.clear()
  await checkout
})
