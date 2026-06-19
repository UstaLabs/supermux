import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { SessionStore } from "./session-store"
import type { FinishJob } from "../worktree/finish-job"

test("setFinishJob persists to db and survives a store reload", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ id: "s1", name: "n", workdir: "/wt", pid: 1, agent: "claude" })
  const id = s.id

  const job: FinishJob = {
    sessionId: id,
    action: "merge" as const,
    status: "done" as const,
    outcome: { status: "integrated" as const, base: "main", branch: "mux/x", mergedSha: "abc", verified: null, cleanedUp: true },
    startedAt: 1,
    endedAt: 2,
  }

  store.setFinishJob(id, job)

  // In-memory cache should reflect the update immediately
  expect(store.getById(id)?.finish_job).toEqual(job)

  // Reload from the same db (forces a fresh read from disk)
  const store2 = new SessionStore(db)
  expect(store2.getById(id)?.finish_job).toEqual(job)
})

test("setFinishJob(null) clears the persisted job", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const s = store.register({ id: "s2", name: "m", workdir: "/wt", pid: 1, agent: "claude" })
  const id = s.id

  const job: FinishJob = {
    sessionId: id,
    action: "pr" as const,
    status: "done" as const,
    startedAt: 10,
    endedAt: 20,
  }

  store.setFinishJob(id, job)
  expect(store.getById(id)?.finish_job).toEqual(job)

  store.setFinishJob(id, null)
  expect(store.getById(id)?.finish_job).toBeUndefined()

  // Verify cleared value survives reload
  const store2 = new SessionStore(db)
  expect(store2.getById(id)?.finish_job).toBeUndefined()
})
