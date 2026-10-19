// The session store never reaches a file through a symbolic link planted in the state directory: records and subagent
// lists are written where an agent that can write the state directory could otherwise redirect them.
import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { SessionStore } from "../src/store.js"
import type { SubagentSnapshot } from "../src/types.js"

const roots: string[] = []
const stores: SessionStore[] = []
afterEach(async () => {
  for (const store of stores.splice(0)) await store.close()
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

/** `state` (the state directory) and `outside` (where a planted link points), side by side. */
function scratch() {
  const root = mkdtempSync(join(tmpdir(), "store-links-"))
  roots.push(root)
  const outside = join(root, "outside")
  mkdirSync(outside)
  return { state: join(root, "state"), outside }
}

async function opened() {
  const paths = scratch()
  const store = new SessionStore(paths.state)
  stores.push(store)
  await store.open()
  return { ...paths, store }
}

const record = (id: string) => ({ version: 1 as const, id, agent: "test", agentSessionId: "native", cwd: tmpdir(), createdAt: new Date().toISOString() })

/** Replaces `<state>/<name>` with a link to `outside`, as an agent able to write the state directory could. */
function relink(state: string, name: string, outside: string) {
  renameSync(join(state, name), join(state, `${name}.moved`))
  symlinkSync(outside, join(state, name))
}

test("a sessions directory replaced by a link: put is refused and nothing is written where it points", async () => {
  const { state, outside, store } = await opened()
  relink(state, "sessions", outside)
  await expect(store.put(record("one"))).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readdirSync(outside)).toEqual([])
})

test("a subagents directory replaced by a link: putSubagents is refused and nothing is written where it points", async () => {
  const { state, outside, store } = await opened()
  relink(state, "subagents", outside)
  const subagents = [{ subagentId: "a", status: "running" }] as unknown as SubagentSnapshot[]
  await expect(store.putSubagents("one", subagents)).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readdirSync(outside)).toEqual([])
})

test("a record that is a link is never read through", async () => {
  const { state, outside, store } = await opened()
  writeFileSync(join(outside, "one.json"), JSON.stringify(record("one")))
  symlinkSync(join(outside, "one.json"), join(state, "sessions", "one.json"))
  await expect(store.get("one")).rejects.toMatchObject({ code: "state_unsafe", message: expect.stringContaining("one.json") })
  await expect(store.list()).rejects.toMatchObject({ code: "state_unsafe" })
})

test("a sessions directory replaced by a link is never read through", async () => {
  const { state, outside, store } = await opened()
  writeFileSync(join(outside, "one.json"), JSON.stringify(record("one")))
  relink(state, "sessions", outside)
  await expect(store.get("one")).rejects.toMatchObject({ code: "state_unsafe" })
})

test("remove never deletes through a sessions or subagents directory replaced by a link", async () => {
  for (const name of ["sessions", "subagents"]) {
    const { state, outside, store } = await opened()
    writeFileSync(join(outside, "one.json"), "keep")
    relink(state, name, outside)
    await expect(store.remove("one")).rejects.toMatchObject({ code: "state_unsafe" })
    expect(readFileSync(join(outside, "one.json"), "utf8")).toBe("keep")
  }
})

test("a state directory whose sessions directory is a link is not opened", async () => {
  const { state, outside } = scratch()
  mkdirSync(state)
  symlinkSync(outside, join(state, "sessions"))
  const store = new SessionStore(state)
  stores.push(store)
  await expect(store.open()).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readdirSync(outside)).toEqual([])
})

test("remove never deletes through a context directory that is a link", async () => {
  const { state, outside, store } = await opened()
  mkdirSync(join(outside, "one"))
  writeFileSync(join(outside, "one", "precious"), "keep")
  symlinkSync(outside, join(state, "context"))
  await expect(store.remove("one")).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readFileSync(join(outside, "one", "precious"), "utf8")).toBe("keep")
})

test("a .core.lock that is a link is refused, never read through or reclaimed", async () => {
  const { state, outside } = scratch()
  mkdirSync(state)
  // What a lock left by a process that is gone looks like (it would be reclaimed if it were a file).
  const lock = JSON.stringify({ pid: 2 ** 22 + 12345, startedAt: new Date(0).toISOString() })
  writeFileSync(join(outside, "lock"), lock)
  symlinkSync(join(outside, "lock"), join(state, ".core.lock"))
  const store = new SessionStore(state)
  stores.push(store)
  await expect(store.open()).rejects.toMatchObject({ code: "state_unsafe", message: expect.stringContaining(".core.lock") })
  expect(readFileSync(join(outside, "lock"), "utf8")).toBe(lock)
  expect(readdirSync(state)).toEqual([".core.lock"])
})
