import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useMessages } from "@/stores/messages"
import { useSessions } from "@/stores/sessions"
import { useSessionsByRecency, useSortedSessions } from "./useSortedSessions"

beforeEach(() => {
  setActivePinia(createPinia())
})

test("sorts sessions by sortOrder, not message recency", () => {
  const sessions = useSessions()
  const messages = useMessages()
  sessions.replace([
    { id: "old-id", name: "old-name", workdir: "/old", mute: false, connected: true, sortOrder: 0 },
    { id: "new-id", name: "new-name", workdir: "/new", mute: false, connected: true, sortOrder: 1 },
  ])
  // Newer message on the second session must NOT jump it above sortOrder 0.
  messages.replace("old-id", [{ id: "1", ts: "2026-06-01T00:00:00Z", direction: "inbound", channel: "web" }])
  messages.replace("new-id", [{ id: "2", ts: "2026-06-02T00:00:00Z", direction: "inbound", channel: "web" }])

  expect(useSortedSessions().value.map((session) => session.id)).toEqual(["old-id", "new-id"])
})

test("does not reshuffle when a later message arrives", () => {
  const sessions = useSessions()
  const messages = useMessages()
  sessions.replace([
    { id: "first-id", name: "first", workdir: "/first", mute: false, connected: true, sortOrder: 0 },
    { id: "second-id", name: "second", workdir: "/second", mute: false, connected: true, sortOrder: 1 },
  ])
  const sorted = useSortedSessions()

  expect(sorted.value[0]?.id).toBe("first-id")

  messages.replace("second-id", [{ id: "1", ts: "2026-06-02T00:00:00Z", direction: "inbound", channel: "web" }])

  expect(sorted.value[0]?.id).toBe("first-id")
  expect(sorted.value.map((s) => s.id)).toEqual(["first-id", "second-id"])
})

test("useSessionsByRecency still orders by newest message for the launcher", () => {
  const sessions = useSessions()
  const messages = useMessages()
  sessions.replace([
    { id: "old-id", name: "old", workdir: "/old", mute: false, connected: true, sortOrder: 0 },
    { id: "new-id", name: "new", workdir: "/new", mute: false, connected: true, sortOrder: 1 },
  ])
  messages.replace("old-id", [{ id: "1", ts: "2026-06-01T00:00:00Z", direction: "inbound", channel: "web" }])
  messages.replace("new-id", [{ id: "2", ts: "2026-06-02T00:00:00Z", direction: "inbound", channel: "web" }])

  expect(useSessionsByRecency().value.map((s) => s.id)).toEqual(["new-id", "old-id"])
})

test("lower sortOrder (new session at top) sorts first", () => {
  const sessions = useSessions()
  sessions.replace([
    { id: "older", name: "older", workdir: "/a", mute: false, connected: true, sortOrder: 0 },
    { id: "brand-new", name: "brand-new", workdir: "/b", mute: false, connected: true, sortOrder: -1 },
  ])
  expect(useSortedSessions().value.map((s) => s.id)).toEqual(["brand-new", "older"])
})
