import { test, expect } from "bun:test"
import { Database } from "bun:sqlite"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SessionStore } from "../src/core/session-manager/session-store"
import { isDraftSession } from "../src/core/session-manager/supervisor"

function store(): SessionStore {
  const db = new Database(":memory:")
  db.exec("PRAGMA foreign_keys = ON")
  for (const m of MIGRATIONS) db.exec(m.sql)
  return new SessionStore(db)
}

test("archive marks the row settled", () => {
  const s = store()
  const sess = s.register({ name: "l1", agent: "claude", workdir: "/tmp", pid: 5 })
  s.archive(sess.id)
  const rec = s.getById(sess.id)!
  expect(rec.status).toBe("archived")
  expect(rec.user_status).toBe("settled")
})

test("resume restores user_status to in_progress", () => {
  const s = store()
  const sess = s.register({ name: "l2", agent: "claude", workdir: "/tmp", pid: 5 })
  s.archive(sess.id)
  s.resume(sess.id, "l2", 99)
  const rec = s.getById(sess.id)!
  expect(rec.status).toBe("active")
  expect(rec.user_status).toBe("in_progress")
})

test("resume from settled places the session at the top of in_progress", () => {
  const s = store()
  const live = s.register({ name: "still-live", agent: "claude", workdir: "/tmp", pid: 1 })
  const settled = s.register({ name: "was-settled", agent: "claude", workdir: "/tmp", pid: 2 })
  // settled currently has a lower sort_order (registered later → top). After archive + resume
  // with another live peer, it should still land at the top of in_progress.
  expect(settled.sort_order).toBeLessThan(live.sort_order)
  s.archive(settled.id)
  // A newer live session takes the top while settled is archived.
  const newer = s.register({ name: "even-newer", agent: "claude", workdir: "/tmp", pid: 3 })
  expect(newer.sort_order).toBeLessThan(live.sort_order)

  const resumed = s.resume(settled.id, "was-settled", 99)!
  expect(resumed.user_status).toBe("in_progress")
  expect(resumed.status).toBe("active")
  // Resumed must be above every current in_progress peer (including newer).
  expect(resumed.sort_order).toBeLessThan(newer.sort_order)
  expect(resumed.sort_order).toBeLessThan(live.sort_order)
})

test("isDraftSession identifies drafts the reconcile loop must skip", () => {
  const s = store()
  const draft = s.register({ name: "sd1", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft" })
  const live = s.register({ name: "sd2", agent: "claude", workdir: "/tmp", pid: 7 })
  expect(isDraftSession(s.getById(draft.id)!)).toBe(true)
  expect(isDraftSession(s.getById(live.id)!)).toBe(false)
})

test("deleteById removes a draft row entirely (no archived ghost)", () => {
  const s = store()
  const d = s.register({ name: "m1", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft", draft_payload: { text: "go" } })
  s.deleteById(d.id)
  expect(s.getById(d.id)).toBeUndefined()
})

// killSession() branches on isDraftSession(): drafts are hard-deleted (discard),
// non-drafts go through archive() → user_status='settled'. killSession itself is a
// non-exported closure in main.ts with many runtime deps (tmux/proxy/display), so we
// pin the discard-vs-settle DECISION at the store layer that the branch relies on.
test("discard (deleteById) leaves nothing, whereas archive would leave a settled ghost", () => {
  const s = store()
  const draft = s.register({ name: "disc", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft" })
  expect(isDraftSession(s.getById(draft.id)!)).toBe(true)
  // The draft path in killSession:
  s.deleteById(draft.id)
  expect(s.getById(draft.id)).toBeUndefined()

  // The non-draft path in killSession would archive → a lingering settled row.
  const live = s.register({ name: "livekill", agent: "claude", workdir: "/tmp", pid: 9 })
  expect(isDraftSession(s.getById(live.id)!)).toBe(false)
  s.archive(live.id)
  const ghost = s.getById(live.id)!
  expect(ghost.user_status).toBe("settled")
})

test("lifecycle: a draft is consumed on start (hard-deleted), leaving no ghost", () => {
  const s = store()
  const d = s.register({ name: "e2e-draft", agent: "claude", workdir: "/tmp", pid: 0, user_status: "draft", draft_payload: { text: "x" } })
  expect(s.getById(d.id)!.user_status).toBe("draft")
  // Starting a draft (main.ts layer) hard-deletes the draft row before spawning a fresh session.
  s.deleteById(d.id)
  expect(s.getById(d.id)).toBeUndefined()
})

test("lifecycle: in_progress → settled (archived) → resumed → in_progress at top", () => {
  const s = store()
  const sess = s.register({ name: "e2e-live", agent: "claude", workdir: "/tmp", pid: 10 })
  expect(s.getById(sess.id)!.user_status).toBe("in_progress")
  const peer = s.register({ name: "peer", agent: "claude", workdir: "/tmp", pid: 11 })

  s.archive(sess.id)
  const settled = s.getById(sess.id)!
  expect(settled.user_status).toBe("settled")
  expect(settled.status).toBe("archived")

  s.resume(sess.id, "e2e-live", 22)
  const resumed = s.getById(sess.id)!
  expect(resumed.user_status).toBe("in_progress")
  expect(resumed.status).toBe("active")
  expect(resumed.sort_order).toBeLessThan(peer.sort_order)
})
