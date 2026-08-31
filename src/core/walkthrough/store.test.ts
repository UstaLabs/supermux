import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WalkthroughStore } from "./store"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new WalkthroughStore(db)
}

test("replaceCurrent inserts a walkthrough with ordered steps", () => {
  const s = store()
  const wt = s.replaceCurrent("sess", "Auth flow", "head", [
    { title: "Overview", bodyMd: "hello" },
    { title: "Login", bodyMd: "code", path: "src/a.ts", repo: "", anchorLine: 4, rangeStart: 4, rangeEnd: 10, anchorContext: "fn", anchorStatus: "ok" },
  ])
  expect(wt.title).toBe("Auth flow")
  expect(wt.revision).toBe(1)
  expect(wt.isCurrent).toBe(true)
  expect(wt.steps).toHaveLength(2)
  expect(wt.steps[0]).toMatchObject({ title: "Overview", ord: 0, bodyMd: "hello" })
  expect(wt.steps[1]).toMatchObject({ title: "Login", path: "src/a.ts", anchorLine: 4, anchorStatus: "ok" })
  expect(s.getCurrent("sess")?.id).toBe(wt.id)
})

test("replaceCurrent marks the previous walkthrough not-current and bumps revision", () => {
  const s = store()
  const first = s.replaceCurrent("sess", "v1", "", [{ title: "a", bodyMd: "a" }])
  const second = s.replaceCurrent("sess", "v2", "head", [{ title: "b", bodyMd: "b" }])
  expect(second.revision).toBe(2)
  expect(s.getCurrent("sess")?.id).toBe(second.id)
  expect(s.get(first.id)?.isCurrent).toBe(false)
})
