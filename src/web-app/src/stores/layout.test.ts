import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { nextTick } from "vue"
import { useLayout } from "./layout"

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

test("panel visibility is scoped per session", () => {
  const layout = useLayout()
  const alpha = layout.panelsFor("alpha")
  const beta = layout.panelsFor("beta")

  layout.toggleTerminal("alpha")
  layout.toggleDisplay("beta")

  expect(alpha.terminalOpen).toBe(true)
  expect(alpha.displayOpen).toBe(false)
  expect(beta.terminalOpen).toBe(false)
  expect(beta.displayOpen).toBe(true)
})

test("blank-screen guard repairs only the affected session", async () => {
  const layout = useLayout()
  const alpha = layout.panelsFor("alpha")
  const beta = layout.panelsFor("beta")

  alpha.chatOpen = false
  alpha.displayOpen = true
  beta.terminalOpen = true
  beta.chatOpen = false

  alpha.displayOpen = false
  await nextTick()

  expect(alpha.chatOpen).toBe(true)
  expect(beta.chatOpen).toBe(false)
})

test("toggleChat is allowed when display is the only open side pane", () => {
  const layout = useLayout()
  const alpha = layout.panelsFor("alpha")

  layout.toggleChat("alpha")
  expect(alpha.chatOpen).toBe(true)

  alpha.displayOpen = true
  layout.toggleChat("alpha")
  expect(alpha.chatOpen).toBe(false)
})

test("active tab is scoped per session and repaired when its pane closes", async () => {
  const layout = useLayout()
  const alpha = layout.panelsFor("alpha")
  const beta = layout.panelsFor("beta")

  alpha.displayOpen = true
  alpha.activeTab = "display"
  beta.editorOpen = true
  beta.activeTab = "editor"

  alpha.displayOpen = false
  await nextTick()

  expect(alpha.activeTab as string).toBe("chat")
  expect(beta.activeTab as string).toBe("editor")
})
