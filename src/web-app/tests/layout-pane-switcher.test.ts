import { test, expect, beforeEach } from "bun:test"
import { setActivePinia, createPinia } from "pinia"

// localStorage shim must exist before the layout store module evaluates.
const mem = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (k: string) => (mem.has(k) ? mem.get(k)! : null),
  setItem: (k: string, v: string) => { mem.set(k, v) },
  removeItem: (k: string) => { mem.delete(k) },
  clear: () => { mem.clear() },
}

import { useLayout } from "../src/stores/layout"

beforeEach(() => {
  mem.clear()
  setActivePinia(createPinia())
})

test("selectTab('terminal') opens the terminal pane and activates it", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  expect(panel.terminalOpen).toBe(false)
  expect(panel.activeTab).toBe("chat")
  layout.selectTab("alpha", "terminal")
  expect(panel.terminalOpen).toBe(true)
  expect(panel.activeTab).toBe("terminal")
})

test("selectTab('editor') opens the editor pane and activates it", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  layout.selectTab("alpha", "editor")
  expect(panel.editorOpen).toBe(true)
  expect(panel.activeTab).toBe("editor")
})

test("selectTab('display') opens the display pane and activates it", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  layout.selectTab("alpha", "display")
  expect(panel.displayOpen).toBe(true)
  expect(panel.activeTab).toBe("display")
})

test("selectTab('chat') activates chat and preserves other open panes", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  layout.selectTab("alpha", "terminal")
  layout.selectTab("alpha", "chat")
  expect(panel.activeTab).toBe("chat")
  expect(panel.terminalOpen).toBe(true) // state preserved, just not active
})

test("selectTab survives the blank-screen guard (activeTab not bounced to chat)", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  layout.selectTab("alpha", "terminal")
  // The deep guard watcher runs synchronously (flush: "sync").
  expect(panel.activeTab).toBe("terminal")
})
