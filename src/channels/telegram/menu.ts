import { Registry } from "../../core/session-manager/registry"

export type MenuEntry = { command: string; description: string }

const BASE: MenuEntry[] = [
  { command: "sessions",          description: "List all sessions" },
  { command: "active",            description: "Show active session" },
  { command: "spawn",             description: "Spawn a new session (workdir [as name])" },
  { command: "spawn_codex",      description: "Spawn a Codex session: /spawn_codex <workdir>" },
  { command: "spawn_cursor",     description: "Spawn a Cursor session: /spawn_cursor <workdir>" },
  { command: "kill",              description: "Kill a session (append 'yes' to confirm)" },
  { command: "rename",            description: "Rename a session: old new" },
  { command: "mute",              description: "Silence a session's notifications" },
  { command: "unmute",            description: "Restore a session's notifications" },
  { command: "show",              description: "Show recent activity from a session" },
  { command: "grant_orchestrate", description: "Allow a session to orchestrate others" },
  { command: "model",             description: "List or switch model: /model [name]" },
  { command: "effort",            description: "List or switch reasoning effort: /effort [level]" },
  { command: "usage",             description: "Show usage across Claude, Codex, Cursor" },
  { command: "archive",           description: "List archived (killed) sessions" },
  { command: "resume",            description: "Resume an archived session: /resume <name>" },
]

export function buildMenuEntries(registry: Registry): MenuEntry[] {
  const out: MenuEntry[] = [...BASE]
  for (const s of registry.listVisible()) {
    const safe = s.name.replace(/-/g, "_")
    const cmd = `switch_to_${safe}`.slice(0, 32)
    out.push({ command: cmd, description: `→ ${s.name}` })
  }
  return out
}

export async function setMenu(api: any, entries: MenuEntry[]): Promise<void> {
  await api.setMyCommands(entries, { scope: { type: "all_private_chats" } })
}
