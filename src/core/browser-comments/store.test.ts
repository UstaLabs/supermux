import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { BrowserCommentStore } from "./store"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new BrowserCommentStore(db)
}

const batch = {
  url: "http://localhost:5173/checkout",
  viewedAs: "iPhone 16 · 393×852",
  comments: [
    { number: 1, comment: "the Pay button is cut off", selector: "button.buy", text: "Pay €42", picture: "comment-1.png" },
    { number: 2, comment: "make this match ①", selector: "section.summary > h3", text: "", picture: null },
  ],
}

test("a batch is stored per comment, sent, in the session it went to", () => {
  const s = store()
  const added = s.addBatch("s1", batch, "2026-11-12T10:00:00Z")
  expect(added.map((c) => [c.number, c.status, c.sessionId])).toEqual([[1, "sent", "s1"], [2, "sent", "s1"]])
  expect(added[0]!.batchId).toBe(added[1]!.batchId)
  expect(added[0]!.url).toBe("http://localhost:5173/checkout")
  expect(s.list("s1").map((c) => c.selector)).toEqual(["button.buy", "section.summary > h3"])
  expect(s.list("s2")).toEqual([])
})

test("an agent answers a comment: done, answered or dismissed, each with its note; open ones are what's left", () => {
  const s = store()
  const [a, b] = s.addBatch("s1", batch, "2026-11-12T10:00:00Z")
  s.setStatus(a!.id, "done", "padding fixed", "2026-11-12T10:05:00Z")
  expect(s.get(a!.id)).toMatchObject({ status: "done", note: "padding fixed", updatedAt: "2026-11-12T10:05:00Z" })
  expect(s.listOpen("s1").map((c) => c.id)).toEqual([b!.id])
  s.setStatus(b!.id, "seen", null, "2026-11-12T10:06:00Z")
  expect(s.listOpen("s1").map((c) => c.id)).toEqual([b!.id]) // seen is still open
  s.setStatus(b!.id, "dismissed", "not a bug", "2026-11-12T10:07:00Z")
  expect(s.listOpen("s1")).toEqual([])
})

test("what the agent reads: the open comments as markdown, with their ids", () => {
  const s = store()
  const [a] = s.addBatch("s1", batch, "2026-11-12T10:00:00Z")
  const md = s.markdown(s.listOpen("s1"))
  expect(md).toContain(`### ① "the Pay button is cut off" · id ${a!.id}`)
  expect(md).toContain("- element: `button.buy` · text \"Pay €42\"")
  expect(md).toContain("http://localhost:5173/checkout")
  expect(s.markdown([])).toBe("No open browser comments.")
})

test("a turn the agent starts after a comment was sent marks it seen; not one sent during that turn", () => {
  const s = store()
  const [a] = s.addBatch("s1", batch, "2026-11-12T10:00:00.000Z")
  expect(s.markSeen("s1", Date.parse("2026-11-12T09:59:00.000Z"), "2026-11-12T10:01:00.000Z")).toEqual([]) // a turn that began before it
  const seen = s.markSeen("s1", Date.parse("2026-11-12T10:00:30.000Z"), "2026-11-12T10:00:31.000Z")
  expect(seen.map((c) => [c.id, c.status])).toContainEqual([a!.id, "seen"])
  expect(s.markSeen("s1", Date.parse("2026-11-12T10:05:00.000Z"), "2026-11-12T10:05:01.000Z")).toEqual([]) // already seen: nothing new
  expect(s.markSeen("s2", Date.parse("2026-11-12T10:05:00.000Z"), "x")).toEqual([])
})
