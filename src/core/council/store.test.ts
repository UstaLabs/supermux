import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations/index"
import { CouncilStore } from "./store"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new CouncilStore(db)
}

test("a council is created open, in round 1, named council-<n>", () => {
  const s = store()
  const a = s.create({ callerSessionId: "p", question: "Q?", callId: "toolu_1" }, new Date("2026-10-29T10:00:00Z"))
  const b = s.create({ callerSessionId: "p", question: "Q2?" })
  expect(a).toMatchObject({ name: "council-1", callerSessionId: "p", question: "Q?", currentRound: 1, status: "open", callId: "toolu_1", createdAt: "2026-10-29T10:00:00.000Z" })
  expect(b.name).toBe("council-2")
  expect(s.get(a.id)).toEqual(a)
  expect(s.byCaller("p").map((c) => c.id)).toEqual([a.id, b.id])
  expect(s.rounds(a.id)).toEqual([{ round: 1, startedAt: "2026-10-29T10:00:00.000Z" }])
})

test("members keep their letter; a session belongs to at most one council", () => {
  const s = store()
  const c = s.create({ callerSessionId: "p", question: "Q" })
  s.addMember(c.id, "m1", "A")
  s.addMember(c.id, "m2", "B")
  expect(s.members(c.id)).toEqual([{ councilId: c.id, sessionId: "m1", letter: "A" }, { councilId: c.id, sessionId: "m2", letter: "B" }])
  expect(s.membership("m2")).toEqual({ councilId: c.id, sessionId: "m2", letter: "B" })
  expect(s.membership("x")).toBeUndefined()
})

test("reports are kept in order; a new round closes the old one; statuses are per round", () => {
  const s = store()
  const c = s.create({ callerSessionId: "p", question: "Q" }, new Date("2026-10-29T10:00:00Z"))
  s.addMember(c.id, "m1", "A")
  s.addMember(c.id, "m2", "B")
  const r1 = s.addReport(c.id, 1, "m2", "b-first", new Date("2026-10-29T10:01:00Z"))
  const r2 = s.addReport(c.id, 1, "m1", "a-first", new Date("2026-10-29T10:02:00Z"))
  s.setStatus(c.id, 1, "m2", "reported", new Date("2026-10-29T10:01:00Z"))
  expect([r1.seq, r2.seq]).toEqual([1, 2])
  expect(s.reports(c.id, 1).map((r) => [r.letter, r.text])).toEqual([["B", "b-first"], ["A", "a-first"]])
  expect(s.statuses(c.id, 1)).toEqual(new Map([["m2", "reported"]]))
  expect(s.nextRound(c.id, "go deeper", new Date("2026-10-29T10:05:00Z"))).toBe(2)
  expect(s.get(c.id)?.currentRound).toBe(2)
  expect(s.rounds(c.id)).toEqual([
    { round: 1, startedAt: "2026-10-29T10:00:00.000Z", completedAt: "2026-10-29T10:05:00.000Z" },
    { round: 2, focus: "go deeper", startedAt: "2026-10-29T10:05:00.000Z" },
  ])
  expect(s.statuses(c.id, 2)).toEqual(new Map())
})

test("remove deletes a council and everything under it", () => {
  const s = store()
  const c = s.create({ callerSessionId: "p", question: "Q" })
  s.addMember(c.id, "m1", "A")
  s.addReport(c.id, 1, "m1", "x")
  s.remove(c.id)
  expect(s.get(c.id)).toBeUndefined()
  expect(s.membership("m1")).toBeUndefined()
  expect(s.reports(c.id)).toEqual([])
})
