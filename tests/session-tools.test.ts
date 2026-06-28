import { test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SearchStore } from "../src/core/search/store"

test("find_sessions returns a matching session with a transcript path", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal, agent_session_id) VALUES ('s1','auth','archived','claude','/repo/app',0,0,'2026-06-26T10:00:00.000Z','worker',0,0,'cs-1')")
  const store = new SearchStore(db, mkdtempSync(join(tmpdir(), "mux-st-")))
  store.indexMessage("s1", "2026-06-26T10:01:00.000Z", "refactored the oauth flow")
  const hits = store.searchSessions("oauth", { limit: 5 })
  expect(hits[0]!.id).toBe("s1")
  expect(hits[0]!.transcript_path).toContain("-repo-app/cs-1.jsonl")
})
