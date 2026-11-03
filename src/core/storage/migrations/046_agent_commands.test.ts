import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("046 creates agent_commands, one row per agent and folder", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(agent_commands)").all() as { name: string; pk: number }[])
  expect(cols.map((c) => c.name)).toEqual(["agent", "workdir", "commands", "updated_at"])
  expect(cols.filter((c) => c.pk > 0).map((c) => c.name)).toEqual(["agent", "workdir"])
})
