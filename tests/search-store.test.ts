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

test("knowledge search matches when query terms are split across sections (OR, not AND)", () => {
  // Regression for the FTS5 implicit-AND bug: a natural keyword-bag query where no
  // single indexed section contains EVERY word used to return `[]`. This is the exact
  // query that returned nothing in production (transcript audit 2026-07-01).
  const home = mkdtempSync(join(tmpdir(), "mux-search-or-"))
  mkdirSync(join(home, "domains"), { recursive: true })
  writeFileSync(
    join(home, "domains", "claudemux.md"),
    "## Resume from archive (2026-06-28)\nThe broker resume path broadcasts a session_added frame that carries no logs.\n\n" +
      "## Snapshot (2026-06-01)\nThe WS snapshot ships the recent messages for each session.\n",
  )
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const s = new SearchStore(db, home)
  s.rebuildKnowledge()
  // "snapshot" lives only in section 2; "resume"/"logs"/"session_added" only in section 1.
  // Under implicit-AND this matched nothing; it must find the best-overlap section.
  const hits = s.searchKnowledge("resume archived session snapshot logs session_added frame broker", {
    includePersonal: true,
    limit: 10,
  })
  expect(hits.length).toBeGreaterThanOrEqual(1)
  expect(hits.some((h) => h.heading.includes("Resume from archive"))).toBe(true)
})

test("session search matches when the query has extra words not in the message (OR, not AND)", () => {
  const { db, home } = setup()
  db.run(
    "INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal, agent_session_id) VALUES ('s1','ota-build','archived','claude','/repo/app',0,0,'2026-06-29T10:00:00.000Z','worker',0,0,'cs-1')",
  )
  const s = new SearchStore(db, home)
  s.indexMessage("s1", "2026-06-29T10:01:00.000Z", "shipped the zsapp OTA itms-services manifest and install link")
  // Keyword-bag with several words absent from the message (plist, hosting, iphone, sign);
  // this is the shape of the real find_sessions query that returned `[]` in production.
  const found = s.searchSessions("zsapp OTA itms-services manifest plist hosting iphone sign", { limit: 10 })
  expect(found.map((f) => f.id)).toEqual(["s1"])
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
