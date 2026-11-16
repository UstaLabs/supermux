import { makeLogger } from "../../shared/log"
import { AGENT_KINDS, AgentKind, agentDisplayName, spawnCommandForAgent } from "../../shared/agents"
import { t, type BrokerLang } from "../../core/i18n/messages"

const log = makeLogger("telegram/menu")

export type MenuEntry = { command: string; description: string }

/** Minimal view of the Registry the menu builder needs (eases unit testing). */
export type SessionLister = { listVisible(): Array<{ name: string }> }

// Telegram rejects the whole setMyCommands payload if one command name is
// invalid, so every generated command must satisfy the Bot API format.
// (Defined before SPAWN_ENTRIES on purpose: const TDZ at module init.)
const TELEGRAM_COMMAND_RE = /^[a-z0-9_]{1,32}$/

// One spawn entry per agent kind, generated from AGENT_KINDS so a new kind
// can never be forgotten here. Claude is the default agent and keeps the
// bare /spawn command. All current kind names pass the format check; the
// filter only protects the rest of the menu from a future invalid name.
const spawnEntries = (lang: BrokerLang): MenuEntry[] => AGENT_KINDS
  .map((kind): MenuEntry => {
    const command = spawnCommandForAgent(kind)
    const description = kind === AgentKind.Claude
      ? t(lang, "menu.spawn")
      : t(lang, "menu.spawn_agent", { agent: agentDisplayName(kind), command })
    return { command, description }
  })
  .filter(e => TELEGRAM_COMMAND_RE.test(e.command))

/** The fixed commands, described in [lang] (#123: Telegram shows the list matching the user's language). */
const base = (lang: BrokerLang): MenuEntry[] => [
  { command: "sessions",          description: t(lang, "menu.sessions") },
  { command: "active",            description: t(lang, "menu.active") },
  ...spawnEntries(lang),
  { command: "agent",             description: t(lang, "menu.agent") },
  { command: "agents",            description: t(lang, "menu.agents") },
  { command: "default",           description: t(lang, "menu.default") },
  { command: "kill",              description: t(lang, "menu.kill") },
  { command: "rename",            description: t(lang, "menu.rename") },
  { command: "mute",              description: t(lang, "menu.mute") },
  { command: "unmute",            description: t(lang, "menu.unmute") },
  { command: "show",              description: t(lang, "menu.show") },
  { command: "grant_orchestrate", description: t(lang, "menu.grant_orchestrate") },
  { command: "model",             description: t(lang, "menu.model") },
  { command: "effort",            description: t(lang, "menu.effort") },
  { command: "fast",              description: t(lang, "menu.fast") },
  { command: "permissions",       description: t(lang, "menu.permissions") },
  { command: "usage",             description: t(lang, "menu.usage") },
  { command: "archive",           description: t(lang, "menu.archive") },
  { command: "resume",            description: t(lang, "menu.resume") },
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

export function buildMenuEntries(registry: SessionLister, lang: BrokerLang = "en"): MenuEntry[] {
  const out: MenuEntry[] = []
  const taken = new Set<string>()
  const push = (command: string, description: string) => {
    const cmd = dedupeCommand(command, taken)
    taken.add(cmd)
    out.push({ command: cmd, description })
  }
  // The fixed commands are already valid, but every emitted command goes through
  // the sanitizer anyway so a future edit cannot reintroduce BOT_COMMAND_INVALID.
  for (const e of base(lang)) push(sanitizeCommand(e.command), e.description)
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
  // Telegram takes at most 100 commands and rejects the WHOLE list beyond that (BOT_COMMANDS_TOO_MUCH): the fixed
  // commands come first, so only the last session shortcuts drop off (/sessions still reaches every session).
  return out.filter(e => TG_COMMAND_RE.test(e.command)).slice(0, TG_MENU_MAX)
}

/** Telegram's limit on a bot's command list. */
const TG_MENU_MAX = 100

/**
 * Pushes the default list (English), then each [localized] list under its language: Telegram shows a user the list
 * for their app's language_code and the default to everyone else (#123).
 */
export async function setMenu(api: any, entries: MenuEntry[], localized: Partial<Record<BrokerLang, MenuEntry[]>> = {}): Promise<void> {
  await api.setMyCommands(entries, { scope: { type: "all_private_chats" } })
  for (const [lang, list] of Object.entries(localized)) {
    if (list) await api.setMyCommands(list, { scope: { type: "all_private_chats" }, language_code: lang })
  }
}

/**
 * The menu refresh, two ways (card #75): `refresh` for a caller that waits for it, `inBackground` for a
 * session start, which never does. Neither throws. A failure is logged once: the same error again stays
 * quiet until a refresh succeeds.
 */
export function menuRefresher(refresh: () => Promise<void>, warn: (err: string) => void): { refresh(): Promise<void>; inBackground(): void } {
  let failing: string | undefined
  const run = async (): Promise<void> => {
    try {
      await refresh()
      failing = undefined
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      if (message !== failing) warn(message)
      failing = message
    }
  }
  return { refresh: run, inBackground: () => { void run() } }
}
