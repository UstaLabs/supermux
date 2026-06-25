import { readFileSync } from "fs"

// Deny-by-default WhatsApp DM allowlist. Reads the `whatsapp` section of the
// shared access.json: { "whatsapp": { "allowFrom": ["628123456789", ...] } }.
// Entries are bare phone numbers; we compare the numeric local-part of the
// sender JID so "628..." matches "628...@s.whatsapp.net" and device-suffixed
// JIDs like "628...:12@s.whatsapp.net".
export type WhatsAppAccess = { allowFrom: string[] }

export function loadWhatsAppAccess(path: string): WhatsAppAccess {
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as { whatsapp?: { allowFrom?: unknown } }
    const wa = parsed.whatsapp ?? {}
    return { allowFrom: Array.isArray(wa.allowFrom) ? wa.allowFrom.map(String) : [] }
  } catch {
    return { allowFrom: [] }
  }
}

function numberOf(jid: string): string {
  return (jid.split("@")[0] ?? "").split(":")[0] ?? ""
}

export function isWhatsAppAllowed(access: WhatsAppAccess, fromJid: string): boolean {
  if (access.allowFrom.length === 0) return false
  const num = numberOf(fromJid)
  return num.length > 0 && access.allowFrom.some((a) => numberOf(a) === num)
}
