import { inferHomeDir, normalizeWorkdirKey } from "./workdir-display"

/** Minimal shape needed to derive a project — a subset of the store's ArchivedSession. */
export interface ArchivedLike {
  workdir: string
  repo_root?: string
  killed_at?: string
}

export interface ArchivedProject {
  /** normalizeWorkdirKey result — identity for dedupe + filtering. */
  key: string
  /** Display label: shortened path with parent folder. */
  label: string
  /** Number of archived sessions in this project. */
  count: number
}

/** A session's project path: its repo (for worktrees) else its workdir. */
function projectPath(s: ArchivedLike): string {
  return s.repo_root ?? s.workdir
}

/**
 * Shortened path with parent folder: `parent/leaf`, prefixed with `…/` when
 * deeper, `~/leaf` directly under home, `~` for home itself.
 */
export function projectLabel(workdir: string, homeDir?: string | null): string {
  const key = normalizeWorkdirKey(workdir, homeDir)
  const home = homeDir ? normalizeWorkdirKey(homeDir) : inferHomeDir(key)
  if (home && key === home) return "~"
  const segments = key.split("/").filter(Boolean)
  if (segments.length <= 1) return key
  const leaf = segments[segments.length - 1]!
  const parent = segments[segments.length - 2]!
  const parentPath = "/" + segments.slice(0, -1).join("/")
  if (home && parentPath === home) return `~/${leaf}`
  const base = `${parent}/${leaf}`
  return segments.length > 2 ? `…/${base}` : base
}

/** Distinct projects across archived sessions, most-recently-archived first. */
export function archivedProjects(sessions: ArchivedLike[], homeDir?: string | null): ArchivedProject[] {
  const byKey = new Map<string, { key: string; label: string; count: number; latest: string }>()
  for (const s of sessions) {
    const path = projectPath(s)
    const key = normalizeWorkdirKey(path, homeDir)
    const killed = s.killed_at ?? ""
    const existing = byKey.get(key)
    if (existing) {
      existing.count += 1
      if (killed > existing.latest) existing.latest = killed
    } else {
      byKey.set(key, { key, label: projectLabel(path, homeDir), count: 1, latest: killed })
    }
  }
  return [...byKey.values()]
    .sort((a, b) => (a.latest === b.latest ? a.label.localeCompare(b.label) : b.latest.localeCompare(a.latest)))
    .map(({ key, label, count }) => ({ key, label, count }))
}

/** Sessions in the given project (by key). A null/empty key returns all sessions. */
export function filterByProject<T extends ArchivedLike>(
  sessions: T[],
  key: string | null,
  homeDir?: string | null,
): T[] {
  if (!key) return sessions
  return sessions.filter((s) => normalizeWorkdirKey(projectPath(s), homeDir) === key)
}
