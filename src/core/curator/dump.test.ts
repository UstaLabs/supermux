import { test, expect, beforeEach } from "bun:test"
import { Database } from "bun:sqlite"
import { queryLast24h, formatLast24h } from "./dump"

let db: Database

beforeEach(() => {
  db = new Database(":memory:")
  db.run(`CREATE TABLE messages (
    session TEXT, session_id TEXT, chat_id TEXT, direction TEXT, text TEXT, ts TEXT
  )`)
  db.run(`CREATE TABLE sessions (id TEXT, name TEXT, workdir TEXT)`)
  db.run("INSERT INTO sessions (id,name,workdir) VALUES ('s-alpha','alpha','/home/user/acme')")
  db.run("INSERT INTO sessions (id,name,workdir) VALUES ('s-beta','beta','/home/user/projects/myapp')")
  const ins = db.prepare("INSERT INTO messages (session, session_id, chat_id, direction, text, ts) VALUES (?,?,?,?,?,?)")
  // Two different sessions sharing ONE web chat_id — the real-world case.
  ins.run("", "s-alpha", "web:Mobile Pwa", "inbound", "hello", "2026-05-30T10:00:00.000Z")
  ins.run("", "s-alpha", "web:Mobile Pwa", "outbound", "hi back", "2026-05-30T10:01:00.000Z")
  ins.run("", "s-beta", "web:Mobile Pwa", "inbound", "I prefer V60 pour-over", "2026-05-30T12:00:00.000Z")
  // out-of-window
  ins.run("", "s-alpha", "web:Mobile Pwa", "inbound", "ancient", "2026-05-28T10:00:00.000Z")
  // empty text — excluded
  ins.run("", "s-alpha", "web:Mobile Pwa", "outbound", "", "2026-05-30T10:02:00.000Z")
})

const SINCE = "2026-05-29T10:00:00.000Z"

test("returns only in-window, non-empty rows", () => {
  const rows = queryLast24h(db, SINCE)
  expect(rows.length).toBe(3)
  expect(rows.some((r) => r.text === "ancient")).toBe(false)
  expect(rows.some((r) => r.text === "")).toBe(false)
})

test("resolves session name + workdir via the join", () => {
  const rows = queryLast24h(db, SINCE)
  const alpha = rows.find((r) => r.session_id === "s-alpha")!
  expect(alpha.session).toBe("alpha")
  expect(alpha.workdir).toBe("/home/user/acme")
})

test("separates sessions that share one chat_id, labeling each workdir", () => {
  const out = formatLast24h(queryLast24h(db, SINCE))
  // NOT collapsed into one web:Mobile Pwa blob — split by real session + path
  expect(out).toContain("=== session: alpha · workdir: /home/user/acme (2 msgs) ===")
  expect(out).toContain("=== session: beta · workdir: /home/user/projects/myapp (1 msgs) ===")
  expect(out).toContain("USER: hello")
  expect(out).toContain("AGENT: hi back")
})

test("empty result has a clear marker", () => {
  expect(formatLast24h([])).toBe("(no messages in the window)")
})
