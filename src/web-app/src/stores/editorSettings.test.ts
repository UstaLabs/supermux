import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { nextTick } from "vue"
import { useEditorSettings } from "./editorSettings"

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

test("defaults to lineWrap enabled when storage is empty", () => {
  const settings = useEditorSettings()
  expect(settings.state.lineWrap).toBe(true)
})

test("persists and restores a changed value", async () => {
  const settings = useEditorSettings()
  settings.setLineWrap(false)
  await nextTick()
  expect(mem.get("cmux:editor-settings")).toContain("\"lineWrap\":false")

  setActivePinia(createPinia())
  const restored = useEditorSettings()
  expect(restored.state.lineWrap).toBe(false)
})

test("toggleLineWrap flips the value", () => {
  const settings = useEditorSettings()
  expect(settings.state.lineWrap).toBe(true)
  settings.toggleLineWrap()
  expect(settings.state.lineWrap).toBe(false)
})

test("falls back to defaults on malformed JSON", () => {
  mem.set("cmux:editor-settings", "{not valid json")
  const settings = useEditorSettings()
  expect(settings.state.lineWrap).toBe(true)
  expect(settings.state.fontSize).toBe(13)
})

test("fontSize defaults to 13 and persists", async () => {
  const settings = useEditorSettings()
  expect(settings.state.fontSize).toBe(13)
  settings.setFontSize(16)
  await nextTick()
  setActivePinia(createPinia())
  expect(useEditorSettings().state.fontSize).toBe(16)
})

test("fontSize is clamped to the allowed range", () => {
  const settings = useEditorSettings()
  settings.setFontSize(99)
  expect(settings.state.fontSize).toBe(24)
  settings.setFontSize(2)
  expect(settings.state.fontSize).toBe(10)
})

test("fontSize falls back to default when stored value is invalid", () => {
  mem.set("cmux:editor-settings", JSON.stringify({ lineWrap: false, fontSize: "big" }))
  const settings = useEditorSettings()
  expect(settings.state.fontSize).toBe(13)
  expect(settings.state.lineWrap).toBe(false)
})

test("treeWidth defaults to 192, clamps, and persists", async () => {
  const settings = useEditorSettings()
  expect(settings.state.treeWidth).toBe(192)
  settings.setTreeWidth(9999)
  expect(settings.state.treeWidth).toBe(600)
  settings.setTreeWidth(260)
  await nextTick()
  setActivePinia(createPinia())
  expect(useEditorSettings().state.treeWidth).toBe(260)
})
