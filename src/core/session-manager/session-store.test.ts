import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { SessionStore } from "./session-store"

test("setWorktree persists and reads back worktree metadata", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ id: "s1", name: "n", workdir: "/wt", pid: 1, agent: "claude" })
  store.setWorktree(s.id, { repo_root: "/repo", base_branch: "main", session_branch: "mux/n" })
  const got = store.getById(s.id)
  expect(got).toMatchObject({ repo_root: "/repo", base_branch: "main", session_branch: "mux/n" })
})
