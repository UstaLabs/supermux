import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { computed } from "vue"

const mem = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (k: string) => (mem.has(k) ? mem.get(k)! : null),
  setItem: (k: string, v: string) => { mem.set(k, v) },
  removeItem: (k: string) => { mem.delete(k) },
  clear: () => { mem.clear() },
}

import { useMessages } from "@/stores/messages"
import { useSessions, type Session } from "@/stores/sessions"
import { useSortedSessions } from "./useSortedSessions"
import { PA_GROUP_KEY, usePathGroups } from "@/composables/usePathGroups"

function makeSession(id: string, over: Partial<Session> = {}): Session {
  return { id, name: id, workdir: "/projects/app", mute: false, connected: true, ...over }
}

beforeEach(() => {
  mem.clear()
  setActivePinia(createPinia())
})

test("PAs are partitioned out of path groups into paGroup", () => {
  const sessions = useSessions()
  sessions.replace([
    makeSession("pa-1", { role: "personal_assistant", workdir: "/home/u/assistant" }),
    makeSession("worker-1", { role: "worker" }),
    makeSession("legacy-1"), // no role field (older broker) — stays in path groups
  ])

  const { groups, paGroup } = usePathGroups(useSortedSessions())

  expect(paGroup.value.workdir).toBe(PA_GROUP_KEY)
  expect(paGroup.value.label).toBe("Personal Assistants")
  expect(paGroup.value.sessions.map((s) => s.id)).toEqual(["pa-1"])
  expect(groups.value).toHaveLength(1)
  expect(groups.value[0]?.sessions.map((s) => s.id).sort()).toEqual(["legacy-1", "worker-1"])
})

test("paGroup sessions sort by message recency, newest first", () => {
  const sessions = useSessions()
  const messages = useMessages()
  sessions.replace([
    makeSession("pa-old", { role: "personal_assistant" }),
    makeSession("pa-new", { role: "personal_assistant" }),
  ])
  messages.replace("pa-old", [{ id: "1", ts: "2026-06-01T00:00:00Z", direction: "inbound", channel: "web" }])
  messages.replace("pa-new", [{ id: "2", ts: "2026-06-02T00:00:00Z", direction: "inbound", channel: "web" }])

  const { paGroup } = usePathGroups(useSortedSessions())

  expect(paGroup.value.sessions.map((s) => s.id)).toEqual(["pa-new", "pa-old"])
})

test("toggle(PA_GROUP_KEY) collapses the PA group and persists", () => {
  const sessions = useSessions()
  sessions.replace([makeSession("pa-1", { role: "personal_assistant" })])

  const { paGroup, toggle } = usePathGroups(useSortedSessions())
  expect(paGroup.value.collapsed).toBe(false)

  toggle(PA_GROUP_KEY)
  expect(paGroup.value.collapsed).toBe(true)
  expect(JSON.parse(localStorage.getItem("cmux:collapsed-paths") ?? "[]")).toContain(PA_GROUP_KEY)

  // A fresh composable instance (i.e. next page load) reads the persisted state.
  const fresh = usePathGroups(useSortedSessions())
  expect(fresh.paGroup.value.collapsed).toBe(true)

  toggle(PA_GROUP_KEY)
  expect(paGroup.value.collapsed).toBe(false)
})

test("zero PAs yields an empty paGroup and untouched path groups", () => {
  const sessions = useSessions()
  sessions.replace([makeSession("worker-1", { role: "worker" }), makeSession("worker-2")])

  const { groups, paGroup } = usePathGroups(useSortedSessions())

  expect(paGroup.value.sessions).toHaveLength(0)
  expect(groups.value).toHaveLength(1)
  expect(groups.value[0]?.sessions).toHaveLength(2)
})

test("groups split into in_progress/draft/settled sections ordered by sortOrder", () => {
  const sessions = useSessions()
  sessions.replace([
    { id: "p1", name: "second", workdir: "/w", mute: false, connected: false, userStatus: "in_progress", sortOrder: 1 },
    { id: "p2", name: "first",  workdir: "/w", mute: false, connected: false, userStatus: "in_progress", sortOrder: 0 },
    { id: "d1", name: "draft",  workdir: "/w", mute: false, connected: false, userStatus: "draft", sortOrder: 0 },
  ])
  const sorted = computed(() => sessions.list)
  const { groups } = usePathGroups(sorted as any)
  const g = groups.value[0]!
  const ip = g.sections.find((s) => s.key === "in_progress")!
  expect(ip.sessions.map((s) => s.id)).toEqual(["p2", "p1"])
  expect(g.sections.find((s) => s.key === "draft")!.sessions.map((s) => s.id)).toEqual(["d1"])
})

test("archived sessions appear under the project's Settled section", () => {
  const sessions = useSessions()
  sessions.replace([{ id: "p1", name: "live", workdir: "/w", mute: false, connected: false, userStatus: "in_progress", sortOrder: 0 }])
  sessions.archivedSessions = [{ id: "s1", name: "done", workdir: "/w", agent: "claude" } as any]
  const sorted = computed(() => sessions.list)
  const { groups } = usePathGroups(sorted as any)
  const g = groups.value.find((x) => x.sessions.some((s) => s.id === "p1"))!
  expect(g.sections.find((s) => s.key === "settled")!.sessions.map((s) => s.id)).toContain("s1")
})
