import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { InboxCursorStore } from "./cursor"

test("get is undefined until set; set overwrites the cursor per session", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cursor = new InboxCursorStore(db)
  expect(cursor.get("s1")).toBeUndefined()
  cursor.set("s1", "m_1")
  cursor.set("s1", "m_2")
  cursor.set("s2", "m_9")
  expect(cursor.get("s1")).toBe("m_2")
  expect(new InboxCursorStore(db).get("s2")).toBe("m_9")
})
