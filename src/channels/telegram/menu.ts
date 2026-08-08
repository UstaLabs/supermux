import { makeLogger } from "../../shared/log"

const log = makeLogger("telegram/menu")

export type MenuEntry = { command: string; description: string }

/** Minimal view of the Registry the menu builder needs (eases unit testing). */
export type SessionLister = { listVisible(): Array<{ name: string }> }

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

/** Telegram accepts bot commands matching this; one bad entry 400s the WHOLE setMyCommands call. */
const TG_COMMAND_RE = /^[a-z0-9_]{1,32}$/
const TG_COMMAND_MAX = 32

/**
 * Force a string into Telegram's bot-command alphabet (^[a-z0-9_]{1,32}$):
 * lowercase, map every invalid char to "_", collapse "_" runs, trim edge "_",
 * cut to 32 (re-trimming any "_" the cut exposes). Returns "" when nothing
 * survives (e.g. an all-emoji name) — callers must skip such entries.
 */
export function sanitizeCommand(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, "_")
    .replace(/_{2,}/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, TG_COMMAND_MAX)
    .replace(/_+$/, "")
}

/** Resolve a collision by suffixing _2, _3, … while staying within 32 chars. */
function dedupeCommand(base: string, taken: Set<string>): string {
  if (!taken.has(base)) return base
  for (let n = 2; ; n++) {
    const suffix = `_${n}`
    const head = base.slice(0, TG_COMMAND_MAX - suffix.length).replace(/_+$/, "")
    const candidate = `${head}${suffix}`
    if (!taken.has(candidate)) return candidate
  }
}

export function buildMenuEntries(registry: SessionLister): MenuEntry[] {
  const out: MenuEntry[] = []
  const taken = new Set<string>()
  const push = (command: string, description: string) => {
    const cmd = dedupeCommand(command, taken)
    taken.add(cmd)
    out.push({ command: cmd, description })
  }
  // BASE is static and already valid, but every emitted command goes through
  // the sanitizer anyway so a future edit cannot reintroduce BOT_COMMAND_INVALID.
  for (const e of BASE) push(sanitizeCommand(e.command), e.description)
  for (const s of registry.listVisible()) {
    const safeName = sanitizeCommand(s.name)
    if (safeName === "") {
      // Nothing of the name survives Telegram's alphabet — sending it would 400
      // the whole menu, so drop just this entry. The session stays reachable
      // via /sessions; only the one-tap menu shortcut is skipped.
      log.debug("telegram_menu_skip_unsanitizable_name", { name: s.name })
      continue
    }
    // The visible label keeps the original pretty name; only `command` is sanitized.
    push(sanitizeCommand(`switch_to_${safeName}`), `→ ${s.name}`)
  }
  return out.filter(e => TG_COMMAND_RE.test(e.command))
}

export async function setMenu(api: any, entries: MenuEntry[]): Promise<void> {
  await api.setMyCommands(entries, { scope: { type: "all_private_chats" } })
}
