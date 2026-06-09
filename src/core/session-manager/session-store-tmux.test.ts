import { describe, expect, test } from "bun:test"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { SessionStore } from "./session-store"
import { join } from "path"

function store(): SessionStore {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new SessionStore(db)
}

describe("SessionStore tmux metadata", () => {
  test("registers non-tmux sessions without a tmux target", () => {
    const sessions = store()
    const s = sessions.register({
      id: "sid",
      name: "codex-worker",
      agent: AgentKind.Codex,
      workdir: "/tmp",
      pid: 123,
    })
    expect(s.tmux_target).toBe("")
  })

  test("persists tmux window id when session is not cached", () => {
    const sessions = store()
    sessions.register({
      id: "sid",
      name: "claude-worker",
      agent: AgentKind.Claude,
      workdir: "/tmp",
      pid: 123,
    })
    sessions.archive("sid")

    sessions.setTmuxWindowId("sid", "@42")

    expect(sessions.getById("sid")?.tmux_window_id).toBe("@42")
  })
})
