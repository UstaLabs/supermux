import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SearchStore, messageSnippet } from "../src/core/search/store"
import { SessionStore } from "../src/core/session-manager/session-store"

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

function seedMessages(db: any) {
  const ins = "INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal) VALUES (?,?,?,?,?,0,0,'2026-10-01T00:00:00.000Z','worker',0,?)"
  db.run(ins, ["s1", "live-one", "active", "claude", "/repo/a", 0])
  db.run(ins, ["s2", "live-two", "active", "codex", "/repo/b", 0])
  db.run(ins, ["s3", "gone", "archived", "claude", "/repo/c", 0])
  db.run(ins, ["s4", "curator", "active", "claude", "/repo/d", 1])
  const msg = "INSERT INTO messages (id, session, session_id, ts, direction, channel, chat_id, op, text) VALUES (?, '', ?, ?, ?, 'web', 'web:x', ?, ?)"
  db.run(msg, ["m1", "s1", "2026-10-01T10:00:00.000Z", "inbound", null, "Please fix the Deploy script"])
  db.run(msg, ["m2", "s2", "2026-10-02T10:00:00.000Z", "outbound", "reply", "deploy done, 100% green"])
  db.run(msg, ["m3", "s3", "2026-10-03T10:00:00.000Z", "outbound", "reply", "deploy from an archived chat"])
  db.run(msg, ["m4", "s4", "2026-10-04T10:00:00.000Z", "outbound", "reply", "deploy from an internal session"])
  db.run(msg, ["m5", "s1", "2026-10-05T10:00:00.000Z", "outbound", "react", "deploy"])
}

test("message search: substring, case-insensitive, newest first, live non-internal sessions only", () => {
  const { db, home } = setup()
  seedMessages(db)
  const hits = new SearchStore(db, home).searchMessages("DEPLOY")
  expect(hits.map((h) => h.message_id)).toEqual(["m2", "m1"])
  expect(hits[0]).toMatchObject({ session_id: "s2", session_name: "live-two", workdir: "/repo/b", agent: "codex", direction: "outbound" })
})

test("message search: LIKE wildcards in the query are literal; one session filter", () => {
  const { db, home } = setup()
  seedMessages(db)
  const s = new SearchStore(db, home)
  expect(s.searchMessages("100%").map((h) => h.message_id)).toEqual(["m2"])
  expect(s.searchMessages("_").length).toBe(0)
  expect(s.searchMessages("deploy", { sessionId: "s1" }).map((h) => h.message_id)).toEqual(["m1"])
  expect(s.searchMessages("   ")).toEqual([])
})

test("message search: a delegated child's messages are left out", () => {
  const { db, home } = setup()
  const ins = "INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, created_at, role, is_default, internal) VALUES (?,?,?,?,?,0,0,'2026-10-01T00:00:00.000Z','worker',0,0)"
  db.run(ins, ["p1", "parent", "active", "claude", "/repo/a"])
  db.run(ins, ["c1", "child", "active", "codex", "/repo/a"])
  new SessionStore(db).setParent("c1", "p1")
  const msg = "INSERT INTO messages (id, session, session_id, ts, direction, channel, chat_id, op, text) VALUES (?, '', ?, ?, 'outbound', 'web', 'web:x', 'reply', ?)"
  db.run(msg, ["m1", "p1", "2026-10-01T10:00:00.000Z", "the migration is done"])
  db.run(msg, ["m2", "c1", "2026-10-02T10:00:00.000Z", "the migration is done here too"])
  expect(new SearchStore(db, home).searchMessages("migration").map((h) => h.message_id)).toEqual(["m1"])
})

test("message snippet centres the match and marks cuts", () => {
  const long = `${"a ".repeat(100)}NEEDLE here${" b".repeat(100)}`
  const snip = messageSnippet(long, "needle", 10)
  expect(snip.startsWith("…")).toBe(true)
  expect(snip.endsWith("…")).toBe(true)
  expect(snip).toContain("NEEDLE here")
  expect(messageSnippet("short\n\ntext", "text")).toBe("short text")
})
