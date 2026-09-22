import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, existsSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createClaudeCoreHost, type ClaudeCoreHost } from "../agents/claude/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../packages/supermux-core/src/claude/index.js"

function fakeChildFactory(nativeId = "claude-sess-1") {
  const opens: DriverContext[] = []
  const claudeCalls: { options: ClaudeOptions }[] = []
  const factory = (gopts: ClaudeOptions, _overrides: SessionConfiguration): AgentDriver => {
    claudeCalls.push({ options: { ...gopts, env: { ...gopts.env }, args: [...gopts.args] } })
    return {
      id: "claude",
      async open(ctx) {
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? nativeId,
          capabilities: {
            resume: true, steer: false, fork: false, detach: true,
            configure: false, history: false,
          },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
        }
        return runtime
      },
    }
  }
  return { factory, opens, claudeCalls }
}

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const hosts: ClaudeCoreHost[] = []
const dirs: string[] = []

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

describe("Claude core spawn", () => {
  test("registers a core claude worker with pid 0 and starts the Core adapter", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-claude-core-"))
    dirs.push(dir)
    const host = createClaudeCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-claude-"))
    dirs.push(workdir)
    const persisted: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      claudeHost: host,
      onClaudeSessionId: (_name, sid) => persisted.push(sid),
    }, {
      workdir,
      requestedName: "claude-core",
      agent: AgentKind.Claude,
    })

    expect(result.name).toBe("claude-core")
    expect(result.pid).toBe(0)
    const row = reg.get(result.session_id)
    expect(row?.agent).toBe(AgentKind.Claude)
    expect(row?.pid).toBe(0)
    expect(row?.core).toBe(true)
    expect(persisted).toContain("claude-sess-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
    expect(existsSync(join(row!.agent_home!, "instructions.md"))).toBe(true)
    expect(child.claudeCalls[0]?.options.env?.CLAUDE_CODE_DISABLE_AUTO_MEMORY).toBe("1")
    expect(child.claudeCalls[0]?.options.args?.some((a) => a === "--append-system-prompt-file")).toBe(true)
  })
})
