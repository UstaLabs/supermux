import { basename } from "path"
import { normalizeName } from "../../shared/slug"
import { paths } from "@supermux/platform"

const MAX_LEN = 80

export function deriveName(workdir: string): string {
  return normalizeName(basename(workdir)) || "session"
}

/**
 * How a name compares with the taken ones: as the folder it becomes (a session's home, paths.safeFolderName), in either
 * letter case, since the file systems of macOS and Windows ignore it. Uppercased: ordinal and locale-neutral (Q12).
 */
const folderKey = (name: string) => paths.safeFolderName(name).toUpperCase()

/** Whether [name] would be the folder of one of [taken] (folderKey). */
export function folderTaken(name: string, taken: Iterable<string>): boolean {
  const key = folderKey(name)
  for (const other of taken) if (folderKey(other) === key) return true
  return false
}

export function ensureUnique(base: string, taken: Set<string>): string {
  const keys = new Set([...taken].map(folderKey))
  if (!keys.has(folderKey(base))) return base
  for (let i = 2; ; i++) {
    const candidate = `${base}-${i}`
    if (!keys.has(folderKey(candidate))) return candidate
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
    `name that stays accurate for the whole session; a second rename will be rejected. ` +
    `This is the main agent's job only: if you are a subagent (started by another agent's ` +
    `task/spawn tool), never call \`rename_session\` or \`spawn_session\`.`
  )
}
