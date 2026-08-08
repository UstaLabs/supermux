import { Registry } from "../../core/session-manager/registry"
import { AGENT_KINDS, AgentKind, agentDisplayName, spawnCommandForAgent } from "../../shared/agents"

export type MenuEntry = { command: string; description: string }

// Telegram rejects the whole setMyCommands payload if one command name is
// invalid, so every generated command must satisfy the Bot API format.
const TELEGRAM_COMMAND_RE = /^[a-z0-9_]{1,32}$/

// One spawn entry per agent kind, generated from AGENT_KINDS so a new kind
// can never be forgotten here. Claude is the default agent and keeps the
// bare /spawn command. All current kind names pass the format check; the
// filter only protects the rest of the menu from a future invalid name.
const SPAWN_ENTRIES: MenuEntry[] = AGENT_KINDS
  .map((kind): MenuEntry => {
    const command = spawnCommandForAgent(kind)
    const description = kind === AgentKind.Claude
      ? "Spawn a new session (workdir [as name])"
      : `Spawn a ${agentDisplayName(kind)} session: /${command} <workdir>`
    return { command, description }
  })
  .filter(e => TELEGRAM_COMMAND_RE.test(e.command))

const BASE: MenuEntry[] = [
  { command: "sessions",          description: "List all sessions" },
  { command: "active",            description: "Show active session" },
  ...SPAWN_ENTRIES,
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
