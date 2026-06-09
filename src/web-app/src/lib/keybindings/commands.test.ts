import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { KEYBINDING_COMMAND_MAP } from "@/lib/keybindings"
import { useLayout } from "@/stores/layout"

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

test("toggleTerminal sets activeTab when opening", () => {
  const layout = useLayout()
  const panels = layout.panelsFor("alpha")
  expect(panels.terminalOpen).toBe(false)

  const cmd = KEYBINDING_COMMAND_MAP.get("workspace.toggleTerminal")!
  cmd.handler({
    layout,
    router: { push: () => {} } as any,
    route: { path: "/s/alpha" } as any,
    sessionId: "alpha",
    isSessionArchived: false,
  })

  expect(panels.terminalOpen).toBe(true)
  expect(panels.activeTab).toBe("terminal")
})

test("toggleChat is no-op when it is the only panel", () => {
  const layout = useLayout()
  const panels = layout.panelsFor("alpha")
  panels.chatOpen = true

  const cmd = KEYBINDING_COMMAND_MAP.get("workspace.toggleChat")!
  cmd.handler({
    layout,
    router: { push: () => {} } as any,
    route: { path: "/s/alpha" } as any,
    sessionId: "alpha",
    isSessionArchived: false,
  })

  expect(panels.chatOpen).toBe(true)
})

test("panel toggles skip archived sessions", () => {
  const layout = useLayout()
  const cmd = KEYBINDING_COMMAND_MAP.get("workspace.toggleEditor")!
  cmd.handler({
    layout,
    router: { push: () => {} } as any,
    route: { path: "/s/old" } as any,
    sessionId: "old",
    isSessionArchived: true,
  })
  expect(layout.panelsFor("old").editorOpen).toBe(false)
})
