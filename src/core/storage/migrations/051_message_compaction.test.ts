import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MessageStore } from "../../session-manager/messages"
import { SessionStore } from "../../session-manager/session-store"
import { MIGRATIONS } from "./index"

test("051: a chat's compacted line keeps its sizes, trigger, note and summary", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(messages)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toContain("compaction")
  new SessionStore(db).register({ id: "s1", name: "s1", workdir: "/wt", pid: 1, agent: "claude" })
  const log = new MessageStore(db)
  const compaction = { status: "completed" as const, before: 262000, after: 20113, trigger: "manual" as const, note: "keep the #144 decisions", summary: "Summary: …" }
  log.append("s1", { id: "m1", ts: "2026-11-14T00:00:00Z", direction: "outbound", channel: "web", chat_id: "web", op: "compaction", text: "", compaction })
  log.append("s1", { id: "m2", ts: "2026-11-14T00:00:01Z", direction: "outbound", channel: "web", chat_id: "web", text: "plain" })
  const [m1, m2] = log.get("s1")
  expect(m1!.compaction).toEqual(compaction)
  expect(m2!.compaction).toBeUndefined()
})
