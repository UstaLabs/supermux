import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useMessages } from "@/stores/messages"
import { useSessions } from "@/stores/sessions"
import { useSortedSessions } from "./useSortedSessions"

beforeEach(() => {
  setActivePinia(createPinia())
})

test("sorts sessions by UUID-keyed message recency", () => {
  const sessions = useSessions()
  const messages = useMessages()
  sessions.replace([
    { id: "old-id", name: "old-name", workdir: "/old", mute: false, connected: true },
    { id: "new-id", name: "new-name", workdir: "/new", mute: false, connected: true },
  ])
  messages.replace("old-id", [{ id: "1", ts: "2026-06-01T00:00:00Z", direction: "inbound", channel: "web" }])
  messages.replace("new-id", [{ id: "2", ts: "2026-06-02T00:00:00Z", direction: "inbound", channel: "web" }])

  expect(useSortedSessions().value.map((session) => session.id)).toEqual(["new-id", "old-id"])
})

test("reacts when message recency arrives after sessions", () => {
  const sessions = useSessions()
  const messages = useMessages()
  sessions.replace([
    { id: "first-id", name: "first", workdir: "/first", mute: false, connected: true },
    { id: "second-id", name: "second", workdir: "/second", mute: false, connected: true },
  ])
  const sorted = useSortedSessions()

  expect(sorted.value[0]?.id).toBe("first-id")

  messages.replace("second-id", [{ id: "1", ts: "2026-06-02T00:00:00Z", direction: "inbound", channel: "web" }])

  expect(sorted.value[0]?.id).toBe("second-id")
})
