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

function buildSections(list: Session[], messages: ReturnType<typeof useMessages>): PathGroupSection[] {
  const byKey: Record<SectionKey, Session[]> = { in_progress: [], draft: [], settled: [] }
  for (const s of list) {
    const key: SectionKey = s.userStatus === "draft" ? "draft" : s.userStatus === "settled" ? "settled" : "in_progress"
    byKey[key].push(s)
  }
  const byRecency = (a: Session, b: Session) => lastMessageTs(b, messages).localeCompare(lastMessageTs(a, messages))
  const bySort = (a: Session, b: Session) => {
    const av = a.sortOrder ?? 0, bv = b.sortOrder ?? 0
    return av !== bv ? av - bv : byRecency(a, b)
  }
  byKey.in_progress.sort(bySort)
  byKey.draft.sort(bySort)
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
  // group instead of joining their workdir's path group.
  const paGroup = computed<PathGroup>(() => {
    const list = sortedSessions.value.filter((s) => s.role === "personal_assistant")
    list.sort((a, b) => lastMessageTs(b, messages).localeCompare(lastMessageTs(a, messages)))
    return {
      workdir: PA_GROUP_KEY,
      label: "Personal Assistants",
      sessions: list,
      sections: [],
      collapsed: collapsedSet.value.has(PA_GROUP_KEY),
    }
  })

  const groups = computed<PathGroup[]>(() => {
    const byPath = new Map<string, Session[]>()
    const homeDir = sessionsStore.homeDir
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
    const combined = [...sortedSessions.value, ...archivedAsSessions]
    for (const s of combined) {
      if (s.role === "personal_assistant") continue
      // Worktree-backed sessions group under their project (repo_root), not the
      // internal worktree path.
      const key = workdirDisplay(s.repo_root ?? s.workdir, homeDir).key
      const list = byPath.get(key) ?? []
      list.push(s)
      byPath.set(key, list)
    }

    const result: PathGroup[] = []

    for (const [workdir, list] of byPath) {
      list.sort((a, b) => lastMessageTs(b, messages).localeCompare(lastMessageTs(a, messages)))
      const display = workdirDisplay(workdir, homeDir)
      result.push({
        workdir: display.key,
        label: display.label,
        sessions: list,
        sections: buildSections(list, messages),
        collapsed: collapsedSet.value.has(display.key),
      })
    }

    result.sort((a, b) => {
      const aTs = a.sessions.map((s) => lastMessageTs(s, messages)).sort().reverse()[0] ?? ""
      const bTs = b.sessions.map((s) => lastMessageTs(s, messages)).sort().reverse()[0] ?? ""
      return bTs.localeCompare(aTs)
    })

    return result
  })

  return { groups, paGroup, toggle }
}
