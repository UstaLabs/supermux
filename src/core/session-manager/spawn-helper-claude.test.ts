import { afterAll, describe, expect, test } from "bun:test"
import { join } from "path"
import { rmSync } from "fs"
import { AgentKind } from "../../shared/agents"
import { STATE_DIR } from "../../shared/paths"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import type { SessionBackend } from "../runtime/session-backend"

// Move 1 of the session-consolidation spec: the claude session row is born in
// the spawn path (synchronously), not in main.ts onRegister ~1s later. The
// shim's register frame ATTACHES to this row.

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const NAMES = ["ztest-claude-row", "ztest-claude-row-internal", "ztest-claude-row-fail"]

afterAll(() => {
  for (const n of NAMES) {
    try { rmSync(join(STATE_DIR, "memory-preambles", `${n}.md`)) } catch {}
  }
})

function fakeBackend(overrides?: Partial<SessionBackend>): SessionBackend {
  return {
    list: async () => [],
    create: async () => ({ id: "@42", name: "w", pid: 4242, alive: true }),
    ...overrides,
  } as unknown as SessionBackend
}

function deps(reg: Registry, backend?: SessionBackend) {
  return {
    registry: reg,
    bind: async () => {},
    tmuxSession: "mux",
    sessionBackend: backend ?? fakeBackend(),
    postSpawnReady: async () => {},
  }
}

describe("Claude spawn — synchronous row registration", () => {
  test("the row exists when spawn resolves, with window id and agent session id", async () => {
    const reg = registry()
    const r = await spawnSession(deps(reg), {
      workdir: process.cwd(),
      requestedName: "ztest-claude-row",
      agent: AgentKind.Claude,
    })

    const row = reg.get(r.session_id)
    expect(row).toBeDefined()
    expect(row?.agent).toBe("claude")
    expect(row?.tmux_window_id).toBe("@42")
    expect(row?.agent_session_id).toBeTruthy()
    expect(row?.pid).toBe(4242)
    expect(row?.connected).toBe(false) // shim has not joined yet
    expect(row?.base_commits && Object.keys(row.base_commits).length).toBeGreaterThan(0)
  })

  test("internal spawns carry the internal flag at birth", async () => {
    const reg = registry()
    const r = await spawnSession(deps(reg), {
      workdir: process.cwd(),
      requestedName: "ztest-claude-row-internal",
      agent: AgentKind.Claude,
      internal: true,
    })
    expect(reg.get(r.session_id)?.internal).toBe(true)
  })

  test("a failed tmux create leaves no row and no reservation", async () => {
    const reg = registry()
    const backend = fakeBackend({ create: async () => { throw new Error("boom") } })
    await expect(
      spawnSession(deps(reg, backend), {
        workdir: process.cwd(),
        requestedName: "ztest-claude-row-fail",
        agent: AgentKind.Claude,
      }),
    ).rejects.toThrow("boom")
    expect(reg.list().length).toBe(0)
    expect(reg.takenNames().size).toBe(0)
  })
})
