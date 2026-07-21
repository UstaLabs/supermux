import { basename } from "path"
import { normalizeName } from "../../shared/slug"

const MAX_LEN = 80

export function deriveName(workdir: string): string {
  return normalizeName(basename(workdir)) || "session"
}

export function ensureUnique(base: string, taken: Set<string>): string {
  if (!taken.has(base)) return base
  for (let i = 2; ; i++) {
    const candidate = `${base}-${i}`
    if (!taken.has(candidate)) return candidate
  }
}

export type SelfRenameResult = { ok: true; name: string } | { ok: false; error: string }

export function resolveSelfRename(
  requested: string,
  currentName: string,
  taken: Iterable<string>,
  alreadyRenamed = false,
): SelfRenameResult {
  const trimmed = requested.trim().slice(0, MAX_LEN)
  if (!trimmed) return { ok: false, error: "name must not be empty" }
  if (trimmed === currentName) return { ok: true, name: currentName }
  if (alreadyRenamed) return { ok: false, error: "this session was already renamed once; names can only be changed once" }
  const takenSet = new Set(taken)
  takenSet.delete(currentName)
  if (takenSet.has(trimmed)) return { ok: false, error: `name already in use: ${trimmed}` }
  return { ok: true, name: trimmed }
}

export function buildNamingRule(name: string): string {
  return (
    `Your session is named \`${name}\`. ` +
    `On your FIRST substantive turn, as soon as you know what this session is for, ` +
    `rename it to something descriptive by calling the \`rename_session\` tool. ` +
    `Don't keep working under the placeholder name. You may rename only ONCE — pick a ` +
    `name that stays accurate for the whole session; a second rename will be rejected.`
  )
}
