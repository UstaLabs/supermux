import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createCursorCoreHost, type CursorCoreHost } from "../agents/cursor/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { CursorOptions } from "../../../packages/supermux-core/src/cursor/index.js"

function fakeChildFactory(nativeId = "cur-sess-1") {
  const opens: DriverContext[] = []
  const factory = (_gopts: CursorOptions, _overrides: SessionConfiguration): AgentDriver => {
    return {
      id: "cursor",
      async open(ctx) {
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? nativeId,
          capabilities: {
            resume: true, steer: false, fork: false, detach: false,
            configure: false, history: false,
          },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
          async configure() {},
          configuration: () => ({}),
        }
        return runtime
      },
    }
  }
  return { factory, opens }
}

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const hosts: CursorCoreHost[] = []
const dirs: string[] = []

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

describe("Cursor spawn", () => {
  const prevKey = process.env.CURSOR_API_KEY
  process.env.CURSOR_API_KEY = "test-key"
  afterEach(() => {
    if (prevKey === undefined) delete process.env.CURSOR_API_KEY
    else process.env.CURSOR_API_KEY = prevKey
  })

  test("does not create a tmux placeholder window", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-cur-core-"))
    dirs.push(dir)
    const host = createCursorCoreHost({ stateDirectory: dir, driverFactory: child.factory, smoke: async () => {}, sharedRuntime: null })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-"))
    dirs.push(workdir)
    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      cursorHost: host,
    }, {
      workdir,
      requestedName: "cursor-no-tmux",
      agent: AgentKind.Cursor,
    })

    expect(result.name).toBe("cursor-no-tmux")
    expect(reg.get(result.session_id)?.agent).toBe(AgentKind.Cursor)
    expect(reg.get(result.session_id)?.tmux_target).toBe("")
    expect(result.pid).toBe(0)
  })
})
