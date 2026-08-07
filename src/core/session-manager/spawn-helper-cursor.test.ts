import { describe, expect, test } from "bun:test"
import { join } from "path"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

describe("Cursor spawn", () => {
  test("does not create a tmux placeholder window", async () => {
    const reg = registry()
    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      cursorResolveAuth: async () => ({ mode: "api_key", env: { CURSOR_API_KEY: "test" } }),
      cursorSmokeAgent: async () => {},
      cursorRunnerFactory: () => async (_args, _onLine, onExit) => {
        onExit(0)
      },
    }, {
      workdir: process.cwd(),
      requestedName: "cursor-no-tmux",
      agent: AgentKind.Cursor,
    })

    expect(result.name).toBe("cursor-no-tmux")
    expect(reg.get(result.session_id)?.agent).toBe(AgentKind.Cursor)
    expect(reg.get(result.session_id)?.tmux_target).toBe("")
  })
})
