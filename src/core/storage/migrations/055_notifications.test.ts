import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"
import { NotificationPrefsStore } from "../../push/notification-prefs"

test("055: a fresh install gets the new defaults", () => {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  expect(new NotificationPrefsStore(db).get("owner")).toMatchObject({ agents: "all", sessions: "needs", applets: "on", needsYou: true, notices: true, showTip: false })
})

test("055: an account from before keeps everything on, its muted sessions are Off, and it gets the one-time tip", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 55))
  const insert = db.query("INSERT INTO sessions (id, name, status, agent, workdir, mute, created_at) VALUES (?, ?, 'active', 'claude', '/w', ?, '2026-11-01T00:00:00Z')")
  insert.run("loud", "Loud", 0)
  insert.run("quiet", "Quiet", 1)
  runMigrations(db, MIGRATIONS)
  expect(new NotificationPrefsStore(db).get("owner")).toMatchObject({
    agents: "all", sessions: "all", applets: "on", needsYou: true, notices: true, showTip: true,
    overrides: { session: { quiet: "off" }, applet: {} },
  })
})
