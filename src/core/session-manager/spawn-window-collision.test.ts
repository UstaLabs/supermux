import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createClaudeCoreHost, type ClaudeCoreHost } from "../agents/claude/core-host"
import type { AgentDriver, AgentRuntime, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../packages/supermux-core/src/claude/index.js"
import { setSessionBackendForTests } from "../runtime"
import type { SessionBackend } from "../runtime/session-backend"

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

function fakeFactory(): AgentDriver {
  return {
    id: "claude",
    async open(ctx) {
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? "native",
        capabilities: { resume: true, steer: false, fork: false, detach: true, configure: false, history: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

const hosts: ClaudeCoreHost[] = []
const dirs: string[] = []
afterEach(async () => {
  setSessionBackendForTests()
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

describe("Claude worker spawn is Core-backed (no tmux window)", () => {
  test("does not create a tmux window even when a same-named window exists", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cl-col-"))
    dirs.push(dir)
    const host = createClaudeCoreHost({
      stateDirectory: dir,
      driverFactory: (_o: ClaudeOptions, _ov: SessionConfiguration) => fakeFactory(),
    })
    hosts.push(host)
    const reg = registry()
    const occupied = "ztest-spawn-collision"
    const spawnedWindows: string[] = []
    setSessionBackendForTests({
      list: async () => [{ id: "target-0", name: occupied, pid: 1, alive: true }],
      create: async (opts: Parameters<SessionBackend["create"]>[0]) => {
        spawnedWindows.push(opts.name)
        return { id: "target-new", name: opts.name, pid: 99, alive: true }
      },
      capture: async () => "Listening for channel messages",
    } as unknown as SessionBackend)

    const workdir = mkdtempSync(join(tmpdir(), "cl-wd-"))
    dirs.push(workdir)
    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      claudeHost: host,
    }, {
      workdir,
      requestedName: occupied,
      agent: AgentKind.Claude,
    })

    expect(spawnedWindows).toEqual([])
    expect(result.pid).toBe(0)
    expect(result.name).toBe(occupied)
    expect(reg.get(result.session_id)?.core).toBe(true)
  })
})
