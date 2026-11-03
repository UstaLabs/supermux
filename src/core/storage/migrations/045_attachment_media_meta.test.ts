import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("045 adds the media size, length, probed mark and poster to attachments", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(attachments)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(expect.arrayContaining(["width", "height", "duration_ms", "media_probed", "poster_file_id"]))
})
