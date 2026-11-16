import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { SessionStore } from "../../session-manager/session-store"
import { MIGRATIONS } from "./index"

test("053: a session keeps where its work comes from, its pull request, its push remote and who made it", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(sessions)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(expect.arrayContaining(["source_kind", "pr_forge", "pr_number", "pr_url", "push_remote", "created_by"]))
  const made = { source_kind: "pr" as const, pr_forge: "github" as const, pr_number: 123, pr_url: "https://github.com/a/b/pull/123", push_remote: "pr-deniz", created_by: '{"kind":"device","name":"laptop"}' }
  new SessionStore(db).register({ id: "s1", name: "PR", agent: "claude", workdir: "/w", pid: 0, ...made })
  new SessionStore(db).register({ id: "s2", name: "Old", agent: "claude", workdir: "/w2", pid: 0 })
  const reread = new SessionStore(db)
  expect(reread.getById("s1")).toMatchObject(made)
  expect(reread.getById("s2")?.source_kind).toBeUndefined()
  expect(reread.getById("s2")?.created_by).toBeUndefined()
  const prefs = (db.query("PRAGMA table_info(user_prefs)").all() as { name: string }[]).map((c) => c.name)
  expect(prefs).toEqual(["user_id", "scope", "key", "value", "updated_at"])
})
