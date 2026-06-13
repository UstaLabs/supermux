// src/core/forge/store.test.ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { ForgeStore } from "./store"
import type { ForgeCredential } from "./types"

function store() { const db = openDb(":memory:"); runMigrations(db, MIGRATIONS); return new ForgeStore(db) }

const cred: ForgeCredential = {
  id: "github:github.com:ahmet", kind: "github", host: "github.com",
  apiBase: "https://api.github.com", label: "github.com · @ahmet",
  account: { login: "ahmet", name: "Ahmet" }, source: "pat",
  transport: "https", status: "ok", token: "ghp_secret",
}

test("add + list redacts the token; getCredential keeps it", () => {
  const s = store()
  s.add(cred)
  const listed = s.list()
  expect(listed).toHaveLength(1)
  expect((listed[0] as any).token).toBeUndefined()
  expect(listed[0].label).toBe("github.com · @ahmet")
  expect(s.getCredential(cred.id)?.token).toBe("ghp_secret")
})

test("add is idempotent on id (replace), survives reopen via the constructor cache", () => {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  new ForgeStore(db).add(cred)
  new ForgeStore(db).add({ ...cred, token: "ghp_rotated" }) // same id → replace
  const reopened = new ForgeStore(db)
  expect(reopened.list()).toHaveLength(1)
  expect(reopened.getCredential(cred.id)?.token).toBe("ghp_rotated")
})

test("remove deletes; setStatus + setSsh mutate", () => {
  const s = store()
  s.add(cred)
  s.setStatus(cred.id, "needs_reconnect")
  expect(s.list()[0].status).toBe("needs_reconnect")
  s.setSsh(cred.id, { keyPath: "/k", keyId: "42", fingerprint: "SHA256:x" })
  expect(s.list()[0].ssh).toEqual({ fingerprint: "SHA256:x", registered: true })
  expect(s.getCredential(cred.id)?.sshKeyPath).toBe("/k")
  s.remove(cred.id)
  expect(s.list()).toHaveLength(0)
})
