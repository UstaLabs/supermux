import { test, expect } from "bun:test"
import { ensureWindowId } from "./window-id"

const baseDeps = (resolved: string | null, calls: Array<[string, string]>) => ({
  tmuxSession: "mux",
  resolve: async (_s: string, _n: string) => resolved,
  persist: (id: string, wid: string) => { calls.push([id, wid]) },
})

test("returns the stored window id without resolving", async () => {
  const calls: Array<[string, string]> = []
  const deps = baseDeps("@9", calls)
  const wid = await ensureWindowId({ id: "s1", name: "x", tmux_window_id: "@3" }, deps)
  expect(wid).toBe("@3")
  expect(calls).toEqual([])
})

test("heals a missing id via name lookup and persists it", async () => {
  const calls: Array<[string, string]> = []
  const wid = await ensureWindowId({ id: "s1", name: "My Session", tmux_window_id: undefined }, baseDeps("@7", calls))
  expect(wid).toBe("@7")
  expect(calls).toEqual([["s1", "@7"]])
})

test("returns null and does not persist when no window matches", async () => {
  const calls: Array<[string, string]> = []
  const wid = await ensureWindowId({ id: "s1", name: "gone", tmux_window_id: undefined }, baseDeps(null, calls))
  expect(wid).toBeNull()
  expect(calls).toEqual([])
})

test("heals a missing id via slug lookup when display name has spaces (e.g. PA window named by slug)", async () => {
  const calls: Array<[string, string]> = []
  const resolve = async (_s: string, n: string) => (n === "my-assistant" ? "@7" : null)
  const deps = {
    tmuxSession: "mux",
    resolve,
    persist: (id: string, wid: string) => { calls.push([id, wid]) },
  }
  const wid = await ensureWindowId({ id: "s1", name: "My Assistant", tmux_window_id: undefined }, deps)
  expect(wid).toBe("@7")
  expect(calls).toEqual([["s1", "@7"]])
})

test("heals via raw display name on first try without calling resolve a second time", async () => {
  let callCount = 0
  const resolve = async (_s: string, n: string) => { callCount++; return n === "My Session" ? "@5" : null }
  const calls: Array<[string, string]> = []
  const deps = {
    tmuxSession: "mux",
    resolve,
    persist: (id: string, wid: string) => { calls.push([id, wid]) },
  }
  const wid = await ensureWindowId({ id: "s1", name: "My Session", tmux_window_id: undefined }, deps)
  expect(wid).toBe("@5")
  expect(calls).toEqual([["s1", "@5"]])
  expect(callCount).toBe(1)
})
