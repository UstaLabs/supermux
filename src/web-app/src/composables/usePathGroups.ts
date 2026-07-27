import { computed, ref, type ComputedRef } from "vue"
import { useMessages } from "@/stores/messages"
import { useSessions, type Session } from "@/stores/sessions"
import { workdirDisplay } from "@/lib/workdir-display"

const COLLAPSED_KEY = "cmux:collapsed-paths"

// Sentinel group key for the pinned Personal Assistants group. Cannot collide
// with real group keys, which are absolute filesystem paths.
export const PA_GROUP_KEY = "__pas__"

export type SectionKey = "in_progress" | "draft" | "settled"

export interface PathGroupSection {
  key: SectionKey
  label: string
  sessions: Session[]
}

const SECTION_ORDER: SectionKey[] = ["in_progress", "draft", "settled"]
const SECTION_LABELS: Record<SectionKey, string> = {
  in_progress: "In Progress",
  draft: "Drafts",
  settled: "Settled",
}

export interface PathGroup {
  workdir: string
  label: string
  sessions: Session[]
  sections: PathGroupSection[]
  collapsed: boolean
}

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY)
    if (!raw) return new Set()
    const arr = JSON.parse(raw)
    return new Set(Array.isArray(arr) ? arr.filter((x) => typeof x === "string") : [])
  } catch {
    return new Set()
  }
}

function persistCollapsed(set: Set<string>) {
  try {
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...set]))
  } catch {}
}

function lastMessageTs(session: Session, messages: ReturnType<typeof useMessages>): string {
  return messages.bySession[session.id]?.slice(-1)[0]?.ts ?? ""
}

/** User-controlled order: sortOrder only (no message-recency reshuffle). */
function bySortOrder(a: Session, b: Session): number {
  const av = a.sortOrder ?? 0
  const bv = b.sortOrder ?? 0
  if (av !== bv) return av - bv
  return a.id.localeCompare(b.id)
}

/** Short repo label for a session's project tag (leaf folder of repo_root/workdir). */
export function projectLabel(session: Session, homeDir?: string | null): string {
  const label = workdirDisplay(session.repo_root ?? session.workdir, homeDir).label
  const leaf = label.split("/").filter(Boolean).pop() ?? label
  return leaf === "~" ? "home" : leaf
}

function buildSections(list: Session[], messages: ReturnType<typeof useMessages>): PathGroupSection[] {
  const byKey: Record<SectionKey, Session[]> = { in_progress: [], draft: [], settled: [] }
  for (const s of list) {
    const key: SectionKey = s.userStatus === "draft" ? "draft" : s.userStatus === "settled" ? "settled" : "in_progress"
    byKey[key].push(s)
  }
  // In Progress / Drafts: only user drag-reorder (sortOrder). Settled is not
  // user-reorderable and stays newest-message-first for findability.
  const byRecency = (a: Session, b: Session) => lastMessageTs(b, messages).localeCompare(lastMessageTs(a, messages))
  byKey.in_progress.sort(bySortOrder)
  byKey.draft.sort(bySortOrder)
  byKey.settled.sort(byRecency)
  return SECTION_ORDER
    .filter((k) => byKey[k].length > 0)
    .map((k) => ({ key: k, label: SECTION_LABELS[k], sessions: byKey[k] }))
}

export function usePathGroups(sortedSessions: ComputedRef<Session[]>) {
  const sessionsStore = useSessions()
  const messages = useMessages()
  const collapsedSet = ref(loadCollapsed())

  function toggle(workdir: string) {
    if (collapsedSet.value.has(workdir)) collapsedSet.value.delete(workdir)
    else collapsedSet.value.add(workdir)
    persistCollapsed(collapsedSet.value)
  }

  // Personal assistants are not project work: they get a dedicated pinned
  // group instead of joining their workdir's path group. Order is stable
  // (sortOrder) — new messages must not jump rows.
  const paGroup = computed<PathGroup>(() => {
    const list = sortedSessions.value
      .filter((s) => s.role === "personal_assistant")
      .slice()
      .sort(bySortOrder)
    return {
      workdir: PA_GROUP_KEY,
      label: "Personal Assistants",
      sessions: list,
      sections: [],
      collapsed: collapsedSet.value.has(PA_GROUP_KEY),
    }
  })

  // All non-PA sessions (live + archived-as-settled), the source for both the
  // grouped view and the flat view.
  const combinedSessions = computed<Session[]>(() => {
    const archivedAsSessions: Session[] = sessionsStore.archivedSessions.map((a) => ({
      id: a.id,
      name: a.name,
      workdir: a.workdir,
      mute: false,
      connected: false,
      agent: a.agent,
      repo_root: a.repo_root,
      status: "archived",
      userStatus: "settled",
      sortOrder: 0,
    }))
    return [...sortedSessions.value, ...archivedAsSessions].filter((s) => s.role !== "personal_assistant")
  })

  // Flat view: the three task sections built across every project at once.
  const flatSections = computed<PathGroupSection[]>(() => buildSections(combinedSessions.value, messages))

  const groups = computed<PathGroup[]>(() => {
    const byPath = new Map<string, Session[]>()
    const homeDir = sessionsStore.homeDir
    for (const s of combinedSessions.value) {
      // Worktree-backed sessions group under their project (repo_root), not the
      // internal worktree path.
      const key = workdirDisplay(s.repo_root ?? s.workdir, homeDir).key
      const list = byPath.get(key) ?? []
      list.push(s)
      byPath.set(key, list)
    }

    const result: PathGroup[] = []

    for (const [workdir, list] of byPath) {
      // Within a project, row order comes from buildSections (sortOrder). Keep
      // the raw list stable too so message arrival never reshuffles.
      list.sort(bySortOrder)
      const display = workdirDisplay(workdir, homeDir)
      result.push({
        workdir: display.key,
        label: display.label,
        sessions: list,
        sections: buildSections(list, messages),
        collapsed: collapsedSet.value.has(display.key),
      })
    }

    // Project cards stay in a stable order (label). Floating a project to the
    // top on new messages would be automatic reorder without a user gesture.
    result.sort((a, b) => a.label.localeCompare(b.label))

    return result
  })

  return { groups, paGroup, flatSections, toggle }
}
