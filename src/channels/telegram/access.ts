import { readFileSync } from "fs"

// Shape matches the telegram plugin's access.json so migrated files work
// as-is. See ~/.claude/plugins/cache/claude-plugins-official/telegram/0.0.6/
// server.ts for the canonical definition.
export type GroupPolicy = {
  requireMention?: boolean
  allowFrom?: string[]
}

export type Access = {
  dmPolicy: "pairing" | "allowlist" | "disabled"
  allowFrom: string[]
  groups: Record<string, GroupPolicy>
}

export type InboundCtx = {
  chatType: "private" | "group" | "supergroup" | "channel" | undefined
  chatId: string
  senderId: string
}

// Deny-by-default. Missing or malformed file → empty allowlist, dmPolicy
// downgraded to "allowlist" (no auto-pairing — this broker has no pairing
// flow yet). That prevents probing if the file goes missing.
export function loadAccess(path: string): Access {
  try {
    const raw = readFileSync(path, "utf8")
    const parsed = JSON.parse(raw) as Partial<Access>
    return {
      dmPolicy: parsed.dmPolicy ?? "allowlist",
      allowFrom: Array.isArray(parsed.allowFrom) ? parsed.allowFrom.map(String) : [],
      groups: (parsed.groups && typeof parsed.groups === "object") ? parsed.groups : {},
    }
  } catch {
    return { dmPolicy: "allowlist", allowFrom: [], groups: {} }
  }
}

// True iff the inbound message should be delivered. DMs need the sender on
// allowFrom. Groups need an entry in `groups` and (if allowFrom non-empty
// for that group) the sender on the group allowFrom too.
export function isAllowed(access: Access, ctx: InboundCtx): boolean {
  if (access.dmPolicy === "disabled") return false

  if (ctx.chatType === "private") {
    return access.allowFrom.includes(ctx.senderId)
  }

  if (ctx.chatType === "group" || ctx.chatType === "supergroup") {
    const policy = access.groups[ctx.chatId]
    if (!policy) return false
    const groupAllowFrom = policy.allowFrom ?? []
    if (groupAllowFrom.length > 0 && !groupAllowFrom.includes(ctx.senderId)) {
      return false
    }
    return true
  }

  // channel / unknown — never deliver.
  return false
}
