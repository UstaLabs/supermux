import { chordsEqual, eventToChord } from "./chord"
import { KEYBINDING_COMMANDS, KEYBINDING_COMMAND_MAP } from "./commands"
import type { KeyChord, KeybindingCommandId } from "./types"

export * from "./types"
export * from "./chord"
export { KEYBINDING_COMMANDS, KEYBINDING_COMMAND_MAP } from "./commands"

export function resolveChord(
  commandId: KeybindingCommandId,
  overrides: Partial<Record<KeybindingCommandId, KeyChord>>,
): KeyChord {
  const cmd = KEYBINDING_COMMAND_MAP.get(commandId)
  if (!cmd) throw new Error(`Unknown command: ${commandId}`)
  return overrides[commandId] ?? cmd.defaultChord
}

export function buildBindingMap(
  overrides: Partial<Record<KeybindingCommandId, KeyChord>>,
): Map<string, KeybindingCommandId> {
  const map = new Map<string, KeybindingCommandId>()
  for (const cmd of KEYBINDING_COMMANDS) {
    const chord = resolveChord(cmd.id, overrides)
    map.set(chordKey(chord), cmd.id)
  }
  return map
}

function chordKey(chord: KeyChord): string {
  return `${chord.mod}:${chord.key}`
}

export function matchKeydown(
  e: KeyboardEvent,
  overrides: Partial<Record<KeybindingCommandId, KeyChord>>,
): KeybindingCommandId | null {
  const eventChord = eventToChord(e)
  if (!eventChord) return null
  const bindings = buildBindingMap(overrides)
  return bindings.get(chordKey(eventChord)) ?? null
}

export function findConflict(
  commandId: KeybindingCommandId,
  chord: KeyChord,
  overrides: Partial<Record<KeybindingCommandId, KeyChord>>,
): KeybindingCommandId | null {
  for (const cmd of KEYBINDING_COMMANDS) {
    if (cmd.id === commandId) continue
    const other = resolveChord(cmd.id, overrides)
    if (chordsEqual(other, chord)) return cmd.id
  }
  return null
}

export function hasOverride(
  commandId: KeybindingCommandId,
  overrides: Partial<Record<KeybindingCommandId, KeyChord>>,
): boolean {
  return overrides[commandId] != null
}
