// #122 phase 4: an agent answers the Browser tab's comments sent to it, through its mux tools: it reads the open ones,
// then resolves, replies to or dismisses each with a note. Only its own: another session's comments aren't its to answer.
import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { Registry } from "../session-manager/registry"
import { SessionManager } from "../session-manager/manager"
import { fakePorts } from "../../../tests/helpers/session-manager-ports"
import { BrowserCommentStore } from "./store"
import { shimTools } from "../../shim/tools"

function rig() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const frames: object[] = []
  const m = new SessionManager(new Registry(db), fakePorts(db, { frames }))
  const mine = m.registry.register({ id: "s1", name: "checkout", workdir: "/tmp", pid: 1 })
  const other = m.registry.register({ id: "s2", name: "other", workdir: "/tmp", pid: 2 })
  const store = new BrowserCommentStore(db)
  const [a, b] = store.addBatch("s1", {
    url: "http://localhost:5173/checkout",
    comments: [
      { number: 1, comment: "the Pay button is cut off", selector: "button.buy", text: "Pay €42" },
      { number: 2, comment: "too much space", selector: "section.cart > h3" },
    ],
  }, "2026-11-12T10:00:00Z")
  const call = (sessionId: string, name: string, args: Record<string, unknown>) =>
    m.handleOrchestration({ kind: "orchestration", call_id: "c", session_id: sessionId, op: { name, args } })
  return { frames, store, mine, other, a: a!, b: b!, call }
}

test("every agent has the four tools", () => {
  const names = shimTools("mux-shim")!.map((t) => t.name)
  for (const n of ["browser_comments", "resolve_browser_comment", "reply_browser_comment", "dismiss_browser_comment"]) {
    expect(names).toContain(n)
  }
})

test("browser_comments reads the session's open comments as markdown with their ids, and marks them seen", async () => {
  const { call, store, a, frames } = rig()
  const r = await call("s1", "browser_comments", {}) as { ok: true; value: string }
  expect(r.ok).toBe(true)
  expect(r.value).toContain(`id ${a.id}`)
  expect(r.value).toContain("button.buy")
  expect(store.get(a.id)!.status).toBe("seen")
  expect(frames).toContainEqual({ type: "browser_comment", sessionId: "s1", comment: expect.objectContaining({ id: a.id, status: "seen" }) })
  const none = await call("s2", "browser_comments", {}) as { ok: true; value: string }
  expect(none.value).toBe("No open browser comments.")
})

test("resolve, reply and dismiss set the state with the note and tell the app", async () => {
  const { call, store, a, b, frames } = rig()
  expect((await call("s1", "resolve_browser_comment", { comment_id: a.id, note: "padding fixed" })).ok).toBe(true)
  expect(store.get(a.id)).toMatchObject({ status: "done", note: "padding fixed" })
  expect((await call("s1", "reply_browser_comment", { comment_id: b.id, text: "it's the design's 32 px" })).ok).toBe(true)
  expect(store.get(b.id)).toMatchObject({ status: "answered", note: "it's the design's 32 px" })
  expect((await call("s1", "dismiss_browser_comment", { comment_id: b.id, why: "not a bug" })).ok).toBe(true)
  expect(store.get(b.id)).toMatchObject({ status: "dismissed", note: "not a bug" })
  expect(frames).toContainEqual({ type: "browser_comment", sessionId: "s1", comment: expect.objectContaining({ id: a.id, status: "done" }) })
})

test("another session can't answer a comment that isn't its own, nor one that doesn't exist", async () => {
  const { call, store, a } = rig()
  const r = await call("s2", "resolve_browser_comment", { comment_id: a.id, note: "x" }) as { ok: boolean; error?: string }
  expect(r.ok).toBe(false)
  expect(store.get(a.id)!.status).toBe("sent")
  expect(((await call("s1", "resolve_browser_comment", { comment_id: "nope", note: "x" })) as { ok: boolean }).ok).toBe(false)
})
