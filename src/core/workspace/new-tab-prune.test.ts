// #91: a New tab is a view of kind "new" that only asks what to show. One an app left behind (it crashed, or
// lost the broker) means nothing after a restart, so the broker drops them all when it loads.
import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { collectViewIds } from "./layout-tree"

test("loading drops every New tab, out of its workspace's layout too, and keeps every other view", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const w = store.create({ name: "a", workdir: "/w" })
  const term = store.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t1" } })
  const left = store.addView(w.id, { kind: "new", state: {} })
  const other = store.create({ name: "b", workdir: "/b" })
  const alone = store.addView(other.id, { kind: "new", state: {} })

  expect(store.dropNewTabs()).toBe(2)

  expect(store.getView(left.id)).toBeUndefined()
  expect(store.getView(alone.id)).toBeUndefined()
  expect(store.getView(term.id)).toBeDefined()
  expect(collectViewIds(store.getById(w.id)!.layout)).toEqual([term.id])
  expect(collectViewIds(store.getById(other.id)!.layout)).toEqual([])
  expect(store.getById(w.id)!.active_view_id).toBe(term.id)
})
