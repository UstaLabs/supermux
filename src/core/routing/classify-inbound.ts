import { Registry } from "../session-manager/registry"

export type InboundInput = {
  chat_id: string
  text: string
  reply_to: string | undefined
}

export type RouteDecision =
  | { kind: "slash"; command: string; rest: string }
  | { kind: "session"; name: string; id: string; text: string; change_active: boolean; suspended: boolean }
  | { kind: "error"; reason: string }

const SLASH = /^\/([a-z][a-z0-9_]*)(?:\s+(.*))?$/i
const AT_PREFIX = /^@(\S+?)(?:\s+(.*))?$/

export function classifyInbound(
  input: InboundInput,
  registry: Registry,
  replyToLookup: (chat_id: string, message_id: string) => string | undefined,
): RouteDecision {
  const text = input.text.trim()

  // 1. Slash commands
  const slashMatch = text.match(SLASH)
  if (slashMatch) {
    let cmd = slashMatch[1]!.toLowerCase()
    const rest = (slashMatch[2] ?? "").trim()
    // /switch_to_<name> → /switch <name>
    if (cmd.startsWith("switch_to_")) {
      const target = cmd.slice("switch_to_".length)
      return { kind: "slash", command: "switch", rest: target }
    }
    return { kind: "slash", command: cmd, rest }
  }

  // 2. Quote-reply to a tagged message
  if (input.reply_to) {
    const ownerId = replyToLookup(input.chat_id, input.reply_to)
    const ownerSession = ownerId ? registry.get(ownerId) : undefined
    if (ownerSession) {
      return { kind: "session", name: ownerSession.name, id: ownerSession.id, text, change_active: false, suspended: ownerSession.status === "suspended" }
    }
  }

  // 3. @name inline prefix — fuzzy match against display names
  const atMatch = text.match(AT_PREFIX)
  if (atMatch) {
    const query = atMatch[1]!
    const session = registry.fuzzyResolve(query)
    if (session) {
      return { kind: "session", name: session.name, id: session.id, text: (atMatch[2] ?? "").trim(), change_active: false, suspended: session.status === "suspended" }
    }
    // Fall through — unknown @name goes to active session
  }

  // 4. Active session
  const activeId = registry.getActive(input.chat_id)
  if (activeId) {
    const activeSession = registry.get(activeId)
    if (activeSession) return { kind: "session", name: activeSession.name, id: activeSession.id, text, change_active: false, suspended: activeSession.status === "suspended" }
  }

  return { kind: "error", reason: "no_active_session" }
}
