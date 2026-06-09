import { beforeEach, expect, test } from "bun:test"
import {
  chordMatchesEvent,
  chordsEqual,
  eventToChord,
  findConflict,
  formatChord,
  matchKeydown,
  resolveChord,
} from "@/lib/keybindings"

function keydown(init: {
  key: string
  ctrlKey?: boolean
  metaKey?: boolean
  shiftKey?: boolean
  altKey?: boolean
}): KeyboardEvent {
  return {
    key: init.key,
    ctrlKey: init.ctrlKey ?? false,
    metaKey: init.metaKey ?? false,
    shiftKey: init.shiftKey ?? false,
    altKey: init.altKey ?? false,
  } as KeyboardEvent
}

test("eventToChord accepts mod+letter and rejects shift layers", () => {
  expect(eventToChord(keydown({ key: "b", ctrlKey: true }))).toEqual({ mod: true, key: "b" })
  expect(eventToChord(keydown({ key: "B", metaKey: true }))).toEqual({ mod: true, key: "b" })
  expect(eventToChord(keydown({ key: "`", ctrlKey: true }))).toEqual({ mod: true, key: "`" })
  expect(eventToChord(keydown({ key: "b", ctrlKey: true, shiftKey: true }))).toBeNull()
  expect(eventToChord(keydown({ key: "b" }))).toBeNull()
})

test("chordMatchesEvent compares normalized keys", () => {
  const chord = { mod: true, key: "l" }
  expect(chordMatchesEvent(chord, keydown({ key: "L", metaKey: true }))).toBe(true)
  expect(chordMatchesEvent(chord, keydown({ key: "l", ctrlKey: true }))).toBe(true)
  expect(chordMatchesEvent(chord, keydown({ key: "k", ctrlKey: true }))).toBe(false)
})

test("resolveChord uses override when present", () => {
  const overrides = { "workspace.toggleChat": { mod: true, key: "j" } }
  expect(resolveChord("workspace.toggleChat", overrides)).toEqual({ mod: true, key: "j" })
  expect(resolveChord("workspace.toggleSidebar", overrides)).toEqual({ mod: true, key: "b" })
})

test("matchKeydown resolves command from defaults and overrides", () => {
  expect(matchKeydown(keydown({ key: "b", ctrlKey: true }), {})).toBe("workspace.toggleSidebar")
  const overrides = { "workspace.toggleSidebar": { mod: true, key: "k" } }
  expect(matchKeydown(keydown({ key: "k", ctrlKey: true }), overrides)).toBe("workspace.toggleSidebar")
  expect(matchKeydown(keydown({ key: "b", ctrlKey: true }), overrides)).toBeNull()
})

test("findConflict detects duplicate bindings", () => {
  const overrides = { "workspace.toggleChat": { mod: true, key: "e" } }
  expect(findConflict("workspace.toggleEditor", { mod: true, key: "e" }, overrides)).toBe("workspace.toggleChat")
  expect(findConflict("workspace.toggleEditor", { mod: true, key: "x" }, overrides)).toBeNull()
})

test("formatChord renders ctrl label on non-mac", () => {
  const prev = (globalThis as any).navigator
  ;(globalThis as any).navigator = { platform: "Linux x86_64" }
  expect(formatChord({ mod: true, key: "b" })).toBe("Ctrl+B")
  ;(globalThis as any).navigator = prev
})

test("chordsEqual is case-insensitive on key", () => {
  expect(chordsEqual({ mod: true, key: "b" }, { mod: true, key: "b" })).toBe(true)
})
