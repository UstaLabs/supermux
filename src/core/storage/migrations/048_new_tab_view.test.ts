import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("048 widens views.kind to new, keeping every row, column, index and the workspace FK", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 48))
  const schemaBefore = db.query("PRAGMA table_info(views)").all()
  db.run("INSERT INTO workspaces (id, name, workdir, layout, created_at) VALUES ('w1','w','/w','{}','t')")
  db.run(`INSERT INTO views (id, workspace_id, kind, title, state, created_at) VALUES ('v1','w1','browser','B','{"url":"https://x.test/"}','t1')`)
  const before = db.query("SELECT * FROM views WHERE id='v1'").get()
  expect(() => db.run(`INSERT INTO views (id, workspace_id, kind, state, created_at) VALUES ('n0','w1','new','{}','t')`)).toThrow()

  runMigrations(db, MIGRATIONS.filter((m) => m.version === 48))

  expect(db.query("SELECT * FROM views WHERE id='v1'").get()).toEqual(before)
  expect(db.query("PRAGMA table_info(views)").all()).toEqual(schemaBefore)
  db.run(`INSERT INTO views (id, workspace_id, kind, state, created_at) VALUES ('n1','w1','new','{}','t')`)
  for (const kind of ["chat", "terminal", "editor", "display", "browser"]) {
    db.run(`INSERT INTO views (id, workspace_id, kind, state, created_at) VALUES ('k_${kind}','w1','${kind}','{}','t')`)
  }
  expect(() => db.run(`INSERT INTO views (id, workspace_id, kind, state, created_at) VALUES ('x1','w1','newtab','{}','t')`)).toThrow()
  expect((db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='views' AND name NOT LIKE 'sqlite_%'").all() as { name: string }[]).map((r) => r.name)).toEqual(["views_workspace"])
  expect(db.query("PRAGMA foreign_key_list(views)").all()).toEqual([expect.objectContaining({ table: "workspaces", from: "workspace_id", to: "id", on_delete: "CASCADE" })])
  db.run("DELETE FROM workspaces WHERE id='w1'")
  expect(db.query("SELECT COUNT(*) AS n FROM views").get()).toEqual({ n: 0 })
})
