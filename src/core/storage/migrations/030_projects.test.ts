import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("030 creates projects and project_locations with a unique path", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.run("INSERT INTO projects (id, name, sort_order, created_at) VALUES ('p1','A',0,'t')")
  db.run("INSERT INTO project_locations (id, project_id, path) VALUES ('l1','p1','/a')")
  expect(() => db.run("INSERT INTO project_locations (id, project_id, path) VALUES ('l2','p1','/a')")).toThrow()
})

test("030 rejects a location for a missing project", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  expect(() => db.run("INSERT INTO project_locations (id, project_id, path) VALUES ('l1','nope','/a')")).toThrow()
})

test("030 rejects an empty name", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  expect(() => db.run("INSERT INTO projects (id, name, sort_order, created_at) VALUES ('p1','',0,'t')")).toThrow()
})
