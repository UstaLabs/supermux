import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { SessionStore } from "./session-store"
import { MessageStore } from "./messages"
import { makeReadAdvancer } from "./read-status"

const base = { direction: "inbound" as const, channel: "web", chat_id: "web" }

function setup() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const sessions = new SessionStore(db)
  const messages = new MessageStore(db)
  sessions.register({ id: "s1", name: "s1", workdir: "/wt", pid: 1, agent: "claude" })
  const sent: any[] = []
  const advanceRead = makeReadAdvancer({ sessions, messages, broadcast: (f) => sent.push(f) })
  return { sessions, messages, advanceRead, sent }
}

test("advanceRead marks the session read up to its newest message and broadcasts", () => {
  const { sessions, messages, advanceRead, sent } = setup()
  messages.append("s1", { id: "m1", ts: "2026-06-13T10:00:00.000Z", ...base })
  advanceRead("s1")
  expect(sessions.getLastReadAt("s1")).toBe("2026-06-13T10:00:00.000Z")
  expect(sent).toEqual([{ type: "session_read", session: "s1", last_read_at: "2026-06-13T10:00:00.000Z" }])
})

test("advanceRead is a no-op (no broadcast) when already read up to the newest message", () => {
  const { messages, advanceRead, sent } = setup()
  messages.append("s1", { id: "m1", ts: "2026-06-13T10:00:00.000Z", ...base })
  advanceRead("s1")
  advanceRead("s1") // nothing new since last read
  expect(sent).toHaveLength(1)
})

test("advanceRead does nothing for a session with no messages", () => {
  const { sessions, advanceRead, sent } = setup()
  advanceRead("s1")
  expect(sessions.getLastReadAt("s1")).toBeNull()
  expect(sent).toHaveLength(0)
})

test("advanceRead advances again when a newer message arrives", () => {
  const { sessions, messages, advanceRead, sent } = setup()
  messages.append("s1", { id: "m1", ts: "2026-06-13T10:00:00.000Z", ...base })
  advanceRead("s1")
  messages.append("s1", { id: "m2", ts: "2026-06-13T10:05:00.000Z", ...base })
  advanceRead("s1")
  expect(sessions.getLastReadAt("s1")).toBe("2026-06-13T10:05:00.000Z")
  expect(sent).toHaveLength(2)
  expect(sent[1]).toEqual({ type: "session_read", session: "s1", last_read_at: "2026-06-13T10:05:00.000Z" })
})
