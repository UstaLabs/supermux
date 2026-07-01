import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { nextTick } from "vue"
import { useLauncherDraft } from "./launcherDraft"

const mem = new Map<string, string>()

;(globalThis as any).localStorage = {
  getItem: (k: string) => (mem.has(k) ? mem.get(k)! : null),
  setItem: (k: string, v: string) => { mem.set(k, v) },
  removeItem: (k: string) => { mem.delete(k) },
  clear: () => { mem.clear() },
}

beforeEach(() => {
  mem.clear()
  setActivePinia(createPinia())
})

test("defaults to an empty draft when storage is empty", () => {
  const draft = useLauncherDraft()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.useWorktree).toBe(true)
  expect(draft.state.baseBranch).toBe("")
  expect(draft.state.text).toBe("")
})

test("persists and restores workdir", async () => {
  const draft = useLauncherDraft()
  draft.setWorkdir("/home/user/project")
  await nextTick()
  expect(mem.get("cmux:launcher-draft")).toContain("\"workdir\":\"/home/user/project\"")

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.workdir).toBe("/home/user/project")
})

test("persists and restores worktree + base branch", async () => {
  const draft = useLauncherDraft()
  draft.setWorktree(false)
  draft.setBaseBranch("feature/x")
  await nextTick()

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.useWorktree).toBe(false)
  expect(restored.state.baseBranch).toBe("feature/x")
})

test("persists and restores typed text", async () => {
  const draft = useLauncherDraft()
  draft.setText("fix the flaky test")
  await nextTick()

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.text).toBe("fix the flaky test")
})

test("clear resets every field to defaults", async () => {
  const draft = useLauncherDraft()
  draft.setWorkdir("/home/user/project")
  draft.setWorktree(false)
  draft.setBaseBranch("feature/x")
  draft.setText("hello")
  await nextTick()

  draft.clear()
  await nextTick()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.useWorktree).toBe(true)
  expect(draft.state.baseBranch).toBe("")
  expect(draft.state.text).toBe("")

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.workdir).toBeNull()
  expect(restored.state.text).toBe("")
})

test("falls back to defaults on malformed JSON", () => {
  mem.set("cmux:launcher-draft", "{not valid json")
  const draft = useLauncherDraft()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.text).toBe("")
})

test("falls back to defaults when storage holds a non-object", () => {
  mem.set("cmux:launcher-draft", "\"just a string\"")
  const draft = useLauncherDraft()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.useWorktree).toBe(true)
})
