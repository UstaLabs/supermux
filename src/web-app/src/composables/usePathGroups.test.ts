import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"

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
