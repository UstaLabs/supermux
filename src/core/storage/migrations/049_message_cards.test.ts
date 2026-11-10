import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MessageStore } from "../../session-manager/messages"
import { SessionStore } from "../../session-manager/session-store"
import { MIGRATIONS } from "./index"

test("049: a message keeps its html card and its from_card, and a session finds only its own card by id", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(messages)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(expect.arrayContaining(["card", "from_card"]))

  const sessions = new SessionStore(db)
  for (const id of ["s1", "s2"]) sessions.register({ id, name: id, workdir: "/wt", pid: 1, agent: "claude" })
  const log = new MessageStore(db)
  const card = { id: "a".repeat(64), title: "Chart", height: 300, desktop_height: 200 }
  log.append("s1", { id: "m1", ts: "2026-11-10T00:00:00Z", direction: "outbound", channel: "web", chat_id: "web", op: "reply", text: "", card })
  log.append("s1", { id: "m2", ts: "2026-11-10T00:00:01Z", direction: "inbound", channel: "web", chat_id: "web", text: "Use B", from_card: { id: card.id, title: "Chart" } })
  log.append("s1", { id: "m3", ts: "2026-11-10T00:00:02Z", direction: "outbound", channel: "web", chat_id: "web", text: "plain" })

  const [m1, m2, m3] = log.get("s1")
  expect(m1!.card).toEqual(card)
  expect(m2!.from_card).toEqual({ id: card.id, title: "Chart" })
  expect(m3!.card).toBeUndefined()
  expect(m3!.from_card).toBeUndefined()

  expect(log.findCard("s1", card.id)).toEqual(card)
  expect(log.findCard("s2", card.id)).toBeUndefined()
  expect(log.findCard("s1", "b".repeat(64))).toBeUndefined()
})
