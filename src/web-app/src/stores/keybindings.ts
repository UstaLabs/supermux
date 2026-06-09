import { defineStore } from "pinia"
import { reactive, watch } from "vue"
import {
  findConflict,
  KEYBINDING_COMMANDS,
  resolveChord,
  type KeybindingCommandId,
  type KeyChord,
} from "@/lib/keybindings"

const KEY = "cmux:keybindings"

interface KeybindingsState {
  overrides: Partial<Record<KeybindingCommandId, KeyChord>>
}

function load(): KeybindingsState {
  const base: KeybindingsState = { overrides: {} }
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return base
    const p = JSON.parse(raw)
    if (!p || typeof p !== "object") return base
    const overrides = parseOverrides((p as KeybindingsState).overrides)
    return { overrides }
  } catch {
    return base
  }
}

function parseOverrides(raw: unknown): Partial<Record<KeybindingCommandId, KeyChord>> {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {}
  const validIds = new Set(KEYBINDING_COMMANDS.map((c) => c.id))
  const out: Partial<Record<KeybindingCommandId, KeyChord>> = {}
  for (const [id, chord] of Object.entries(raw)) {
    if (!validIds.has(id as KeybindingCommandId)) continue
    const c = chord as Partial<KeyChord>
    if (c?.mod !== true || typeof c.key !== "string" || !c.key) continue
    out[id as KeybindingCommandId] = { mod: true, key: c.key.toLowerCase() }
  }
  return out
}

export const useKeybindings = defineStore("keybindings", () => {
  const state = reactive<KeybindingsState>(load())

  watch(state, () => {
    try { localStorage.setItem(KEY, JSON.stringify(state)) } catch {}
  }, { deep: true })

  function chordFor(commandId: KeybindingCommandId): KeyChord {
    return resolveChord(commandId, state.overrides)
  }

  function setOverride(commandId: KeybindingCommandId, chord: KeyChord): string | null {
    const conflict = findConflict(commandId, chord, state.overrides)
    if (conflict) {
      const other = KEYBINDING_COMMANDS.find((c) => c.id === conflict)
      return other?.label ?? conflict
    }
    state.overrides[commandId] = chord
    return null
  }

  function clearOverride(commandId: KeybindingCommandId) {
    delete state.overrides[commandId]
  }

  function resetAll() {
    state.overrides = {}
  }

  function isOverridden(commandId: KeybindingCommandId): boolean {
    return state.overrides[commandId] != null
  }

  return {
    state,
    chordFor,
    setOverride,
    clearOverride,
    resetAll,
    isOverridden,
  }
})
