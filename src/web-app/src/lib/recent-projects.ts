export interface RecencySession {
  workdir?: string
  repo_root?: string
}

export interface KnownProject {
  path: string
}

/**
 * Distinct project working directories drawn from sessions already ordered
 * newest-first, preserving that order. The first entry is the most-recently
 * active project — the natural default for a new session.
 */
export function recentWorkdirs(sessionsNewestFirst: RecencySession[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const s of sessionsNewestFirst) {
    const w = (s.repo_root ?? s.workdir)?.trim()
    if (!w || seen.has(w)) continue
    seen.add(w)
    out.push(w)
  }
  return out
}

/**
 * Project options for the picker: recently-used projects first (most recent at
 * the top), followed by any other known projects not used recently.
 */
export function orderProjectsByRecency(recent: string[], known: KnownProject[]): KnownProject[] {
  const seen = new Set<string>()
  const out: KnownProject[] = []
  for (const path of recent) {
    if (!path || seen.has(path)) continue
    seen.add(path)
    out.push({ path })
  }
  for (const p of known) {
    if (!p.path || seen.has(p.path)) continue
    seen.add(p.path)
    out.push({ path: p.path })
  }
  return out
}
