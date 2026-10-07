import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { MessageStore } from "../../session-manager/messages"

test("034 adds messages.subagent_id and the store round-trips it", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','s','active','claude','/w','t')")
  const store = new MessageStore(db)
  store.append("s1", { id: "a", ts: "2026-09-28T00:00:00.000Z", direction: "inbound", channel: "web", chat_id: "web", text: "↪ to Explore: hi", subagent_id: "sub-1" })
  store.append("s1", { id: "b", ts: "2026-09-28T00:00:01.000Z", direction: "inbound", channel: "web", chat_id: "web", text: "plain" })
  const rows = store.get("s1")
  expect(rows.find((m) => m.id === "a")?.subagent_id).toBe("sub-1")
  expect(rows.find((m) => m.id === "b")?.subagent_id).toBeUndefined()
})
