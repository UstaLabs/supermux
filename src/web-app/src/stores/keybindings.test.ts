import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { nextTick } from "vue"
import { useKeybindings } from "./keybindings"

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

test("defaults to empty overrides", () => {
  const kb = useKeybindings()
  expect(kb.state.overrides).toEqual({})
  expect(kb.chordFor("workspace.toggleSidebar")).toEqual({ mod: true, key: "b" })
})

test("persists override and blocks conflicts", async () => {
  const kb = useKeybindings()
  const err = kb.setOverride("workspace.toggleChat", { mod: true, key: "e" })
  expect(err).toBe("Toggle editor")
  expect(kb.state.overrides["workspace.toggleChat"]).toBeUndefined()

  expect(kb.setOverride("workspace.toggleChat", { mod: true, key: "j" })).toBeNull()
  await nextTick()
  expect(mem.get("cmux:keybindings")).toContain("\"key\":\"j\"")

  setActivePinia(createPinia())
  expect(useKeybindings().chordFor("workspace.toggleChat")).toEqual({ mod: true, key: "j" })
})

test("resetAll clears overrides", () => {
  const kb = useKeybindings()
  kb.setOverride("workspace.newSession", { mod: true, key: "o" })
  kb.resetAll()
  expect(kb.state.overrides).toEqual({})
})

test("ignores malformed stored overrides", () => {
  mem.set("cmux:keybindings", JSON.stringify({ overrides: { "workspace.toggleChat": { mod: false } } }))
  const kb = useKeybindings()
  expect(kb.chordFor("workspace.toggleChat")).toEqual({ mod: true, key: "l" })
})
