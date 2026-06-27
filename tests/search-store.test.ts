import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SearchStore } from "../src/core/search/store"

function setup() {
  const home = mkdtempSync(join(tmpdir(), "mux-search-"))
  mkdirSync(join(home, "domains"), { recursive: true })
  mkdirSync(join(home, "personal"), { recursive: true })
  writeFileSync(join(home, "domains", "infra.md"), "---\ndescription: infra\n---\n\n## Deploy (2026-01-01)\nsystemd unit mux.service restart.\n")
  writeFileSync(join(home, "domains", "infra.digest.md"), "## Current\nDeploys run via mux.service on the host.\n")
  writeFileSync(join(home, "personal", "identity.md"), "## Who (2026-01-01)\nAhmet prefers Kotlin.\n")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, home }
}

test("knowledge search ranks digest above raw log and finds sections", () => {
  const { db, home } = setup()
  const s = new SearchStore(db, home)
  s.rebuildKnowledge()
  const hits = s.searchKnowledge("deploy", { includePersonal: true, limit: 10 })
  expect(hits.length).toBeGreaterThanOrEqual(2)
  expect(hits[0]!.scope).toBe("digest") // digest boosted to the top
  expect(hits.some((h) => h.path.endsWith("infra.md"))).toBe(true)
})

test("worker query excludes personal/", () => {
  const { db, home } = setup()
  const s = new SearchStore(db, home)
  s.rebuildKnowledge()
  expect(s.searchKnowledge("Kotlin", { includePersonal: true, limit: 10 }).length).toBe(1)
  expect(s.searchKnowledge("Kotlin", { includePersonal: false, limit: 10 }).length).toBe(0)
})

test("session search returns sessions by message text, filtered + deduped", () => {
  const { db, home } = setup()
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal, agent_session_id) VALUES ('s1','auth-fix','archived','claude','/repo/app',0,0,'2026-06-26T10:00:00.000Z','worker',0,0,'cs-1')")
  db.run("INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal, agent_session_id) VALUES ('s2','other','archived','claude','/repo/other',0,0,'2026-06-26T10:00:00.000Z','worker',0,1,'cs-2')")
  const s = new SearchStore(db, home)
  s.indexMessage("s1", "2026-06-26T10:01:00.000Z", "fixed the oauth refresh token bug")
  s.indexMessage("s1", "2026-06-26T10:02:00.000Z", "oauth tests pass")
  s.indexMessage("s2", "2026-06-26T10:03:00.000Z", "oauth internal worker")
  const found = s.searchSessions("oauth", { limit: 10 })
  expect(found.map((f) => f.id)).toEqual(["s1"]) // s2 excluded (internal=1); s1 deduped to one row
  expect(found[0]!.transcript_path).toContain(".claude/projects/")
})
