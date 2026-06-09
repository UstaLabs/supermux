import type { KeyChord } from "./types"

export function isMacPlatform(): boolean {
  if (typeof navigator === "undefined") return false
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform)
}

export function chordsEqual(a: KeyChord, b: KeyChord): boolean {
  return a.mod === b.mod && a.key === b.key
}

function normalizeKey(key: string): string {
  if (key === "`" || key === "Backquote") return "`"
  if (key.length === 1) return key.toLowerCase()
  return key
}

/** Map a keydown event to a chord, or null if no modifier key is held. */
export function eventToChord(e: KeyboardEvent): KeyChord | null {
  const mod = e.metaKey || e.ctrlKey
  if (!mod) return null
  if (e.altKey || e.shiftKey) return null
  const key = normalizeKey(e.key)
  if (!key) return null
  return { mod: true, key }
}

export function chordMatchesEvent(chord: KeyChord, e: KeyboardEvent): boolean {
  const eventChord = eventToChord(e)
  if (!eventChord) return false
  return chordsEqual(chord, eventChord)
}

export function formatChord(chord: KeyChord): string {
  const mod = isMacPlatform() ? "⌘" : "Ctrl"
  const keyLabel = chord.key === "`" ? "`" : chord.key.toUpperCase()
  return `${mod}+${keyLabel}`
}

/** Recorder: accept Ctrl/Cmd combos only; reject bare keys and Shift/Alt layers. */
export function parseRecordedKeydown(e: KeyboardEvent): KeyChord | null {
  if (e.key === "Escape") return null
  return eventToChord(e)
}

export function isValidOverride(chord: KeyChord | null): chord is KeyChord {
  return !!chord?.mod && !!chord.key
}
