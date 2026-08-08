import { afterAll, describe, expect, mock, test } from "bun:test"
import { join } from "path"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"

// The cursor collaborators are swapped via bun module mocks (the spawn path
// has no injection seams). mock.module is process-global: capture the real
// export VALUES first (a spread snapshot — the namespace's live bindings get
// patched by the mock) and restore them in afterAll.
const realCursorAuth = { ...(await import("../agents/cursor/auth")) }
const realCursorSmoke = { ...(await import("../agents/cursor/smoke")) }
const realCursorRunner = { ...(await import("../agents/cursor/runner")) }

mock.module("../agents/cursor/auth", () => ({
  ...realCursorAuth,
  resolveCursorAuth: async () => ({ mode: "api_key", env: { CURSOR_API_KEY: "test" } }),
}))
mock.module("../agents/cursor/smoke", () => ({
  ...realCursorSmoke,
  smokeCursorAgent: async () => {},
}))
mock.module("../agents/cursor/runner", () => ({
  ...realCursorRunner,
  makeRealCursorRunner: () => async (_args: unknown, _onLine: unknown, onExit: (code: number) => void) => {
    onExit(0)
  },
}))

afterAll(() => {
  mock.module("../agents/cursor/auth", () => realCursorAuth)
  mock.module("../agents/cursor/smoke", () => realCursorSmoke)
  mock.module("../agents/cursor/runner", () => realCursorRunner)
})

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
