import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { MessageStore } from "./messages"
import { SessionStore } from "./session-store"

// Messages carry a FK to sessions(id), so the referenced sessions must exist.
function setup(sessionIds: string[] = []): MessageStore {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const sessions = new SessionStore(db)
  for (const id of sessionIds) sessions.register({ id, name: id, workdir: "/wt", pid: 1, agent: "claude" })
  return new MessageStore(db)
}

const base = { direction: "inbound" as const, channel: "web", chat_id: "web" }

test("newestTs returns null for a session with no messages", () => {
  const store = setup()
  expect(store.newestTs("s1")).toBeNull()
})

test("newestTs returns the latest message timestamp, regardless of insert order", () => {
  const store = setup(["s1"])
  store.append("s1", { id: "m1", ts: "2026-06-13T10:00:00.000Z", ...base })
  store.append("s1", { id: "m2", ts: "2026-06-13T10:05:00.000Z", ...base })
  store.append("s1", { id: "m3", ts: "2026-06-13T10:02:00.000Z", ...base })
  expect(store.newestTs("s1")).toBe("2026-06-13T10:05:00.000Z")
})

test("newestTs is scoped per session", () => {
  const store = setup(["s1", "s2"])
  store.append("s1", { id: "m1", ts: "2026-06-13T10:00:00.000Z", ...base })
  store.append("s2", { id: "m2", ts: "2026-06-13T11:00:00.000Z", ...base })
  expect(store.newestTs("s1")).toBe("2026-06-13T10:00:00.000Z")
})

test("from_session_id round-trips (an inbox message's sender)", () => {
  const store = setup(["s1"])
  store.append("s1", {
    id: "in:inbox:m_1", ts: "2026-10-28T00:00:00.000Z", direction: "inbound", channel: "inbox",
    chat_id: "", message_id: "m_1", text: "hi", from_session_id: "s0",
  })
  expect(store.get("s1")[0]?.from_session_id).toBe("s0")
})

test("lastReply is the newest non-empty, non-error assistant reply", () => {
  const sid = "s1"
  const store = setup([sid])
  expect(store.lastReply(sid)).toBeUndefined()
  store.append(sid, { id: "o1", ts: "2026-10-29T10:00:00.000Z", direction: "outbound", channel: "web", chat_id: "web", op: "reply", text: "first" } as never)
  store.append(sid, { id: "i1", ts: "2026-10-29T10:01:00.000Z", direction: "inbound", channel: "web", chat_id: "web", text: "a question" } as never)
  store.append(sid, { id: "o2", ts: "2026-10-29T10:02:00.000Z", direction: "outbound", channel: "web", chat_id: "web", op: "reply", text: "second" } as never)
  store.append(sid, { id: "o3", ts: "2026-10-29T10:03:00.000Z", direction: "outbound", channel: "web", chat_id: "web", op: "reply", text: "boom", error: true } as never)
  store.append(sid, { id: "o4", ts: "2026-10-29T10:04:00.000Z", direction: "outbound", channel: "web", chat_id: "web", op: "reply", text: "  " } as never)
  expect(store.lastReply(sid)).toBe("second")
})
