import { normalizeWorkdirKey, workdirDisplay } from "./workdir-display"

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
 * Shortened path with parent folder — delegates to the shared workdir label so
 * archived projects, session groups, and chat headers all format identically.
 */
export function projectLabel(workdir: string, homeDir?: string | null): string {
  return workdirDisplay(workdir, homeDir).label
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
