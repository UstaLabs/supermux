import { test, expect, beforeEach } from "bun:test"
import { setActivePinia, createPinia } from "pinia"
import { useDisplays, type DisplayStream } from "../src/stores/displays"

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

test("displayOpen defaults to false and toggles", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  expect(panel.displayOpen).toBe(false)
  layout.toggleDisplay("alpha")
  expect(panel.displayOpen).toBe(true)
})

test("blank-screen guard: closing display when no other right pane re-opens chat", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  panel.chatOpen = false
  panel.displayOpen = true
  panel.displayOpen = false
  expect(panel.chatOpen).toBe(true)
})

test("toggleChat is a no-op when nothing else open, allowed when display open", () => {
  const layout = useLayout()
  const panel = layout.panelsFor("alpha")
  layout.toggleChat("alpha")
  expect(panel.chatOpen).toBe(true)
  panel.displayOpen = true
  layout.toggleChat("alpha")
  expect(panel.chatOpen).toBe(false)
})

function stream(over: Partial<DisplayStream>): DisplayStream {
  return { id: "d1", sessionName: "s", provider: "linux-xvfb", display: ":99", status: "running", createdAt: "2026-05-29T00:00:00.000Z", ...over }
}

test("runningForSession returns the most-recent running stream for a session", () => {
  const d = useDisplays()
  d.replace([
    stream({ id: "a", sessionName: "s1", createdAt: "2026-05-29T00:00:00.000Z" }),
    stream({ id: "b", sessionName: "s1", createdAt: "2026-05-29T01:00:00.000Z" }),
    stream({ id: "c", sessionName: "s2" }),
    stream({ id: "e", sessionName: "s1", status: "errored", createdAt: "2026-05-29T02:00:00.000Z" }),
  ])
  expect(d.runningForSession("s1")?.id).toBe("b")
  expect(d.runningForSession("nope")).toBeUndefined()
})
