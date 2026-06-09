import { defineStore } from "pinia"
import { ref } from "vue"

export type CommandFamily = "agent" | "control"
export interface ControlAction { kind: "spawn" | "model" | "rename" | "mute" | "stop" | "kill"; muted?: boolean }
export interface SlashCommand {
  id: string
  family: CommandFamily
  name: string
  sigil: "/" | "$"
  description?: string
  insertText?: string
  action?: ControlAction
}

// Per-session slash-command lists, pushed by the broker (control commands +
// agent commands tapped from each CLI's native protocol).
export const useCommandsStore = defineStore("commands", () => {
  const bySession = ref<Record<string, SlashCommand[]>>({})
  // True once the broker has finished agent-command discovery for a session
  // (even if it found none) — lets the UI distinguish "loading" from "empty".
  const resolved = ref<Record<string, boolean>>({})

  function hydrate(map: Record<string, SlashCommand[]>, resolvedMap?: Record<string, boolean>) {
    bySession.value = { ...map }
    resolved.value = { ...(resolvedMap ?? {}) }
  }
  function set(session: string, cmds: SlashCommand[], isResolved = true) {
    bySession.value = { ...bySession.value, [session]: cmds }
    resolved.value = { ...resolved.value, [session]: isResolved }
  }
  function remove(session: string) {
    const next = { ...bySession.value }
    delete next[session]
    bySession.value = next
    const r = { ...resolved.value }
    delete r[session]
    resolved.value = r
  }
  function commandsFor(session: string): SlashCommand[] { return bySession.value[session] ?? [] }
  function isResolved(session: string): boolean { return !!resolved.value[session] }

  return { bySession, resolved, hydrate, set, remove, commandsFor, isResolved }
})
