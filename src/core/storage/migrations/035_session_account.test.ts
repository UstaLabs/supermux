import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { SessionStore } from "../../session-manager/session-store"

test("035 adds sessions.account and the store round-trips it", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SessionStore(db)
  const plain = store.register({ name: "plain", agent: "claude", workdir: "/w", pid: 0 })
  const withAccount = store.register({ name: "acct", agent: "codex", workdir: "/w", pid: 0, account: "codex-key" })
  expect(plain.account).toBeUndefined()
  expect(withAccount.account).toBe("codex-key")
  store.setAccount(plain.id, "claude-token")
  store.setAccount(withAccount.id, null)
  const reread = new SessionStore(db)
  expect(reread.getById(plain.id)?.account).toBe("claude-token")
  expect(reread.getById(withAccount.id)?.account).toBeUndefined()
})
