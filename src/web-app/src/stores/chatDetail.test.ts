import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { nextTick } from "vue"
import { useChatDetail } from "./chatDetail"

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

test("defaults to medium", () => {
  const s = useChatDetail()
  expect(s.state.level).toBe("medium")
  expect(s.renderMode).toBe("medium")
  expect(s.levelLabel).toBe("Medium")
})

test("setLevel low/medium persists and restores", async () => {
  const s = useChatDetail()
  s.setLevel("low")
  await nextTick()
  expect(mem.get("cmux:chat-detail")).toContain("\"level\":\"low\"")
  expect(s.renderMode).toBe("low")

  setActivePinia(createPinia())
  const restored = useChatDetail()
  expect(restored.state.level).toBe("low")
  expect(restored.renderMode).toBe("low")
})

test("setLevel rejects high", async () => {
  const s = useChatDetail()
  s.setLevel("high")
  expect(s.state.level).toBe("medium")
  await nextTick()
  // should not persist high
  const raw = mem.get("cmux:chat-detail")
  if (raw) expect(raw).not.toContain("\"level\":\"high\"")
})

test("load clamps stored high to medium", () => {
  mem.set("cmux:chat-detail", JSON.stringify({ level: "high" }))
  const s = useChatDetail()
  expect(s.state.level).toBe("medium")
  expect(s.renderMode).toBe("medium")
})

test("cycleImplemented toggles low ↔ medium", () => {
  const s = useChatDetail()
  s.cycleImplemented()
  expect(s.state.level).toBe("low")
  s.cycleImplemented()
  expect(s.state.level).toBe("medium")
})
