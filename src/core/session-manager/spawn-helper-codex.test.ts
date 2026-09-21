import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createCodexCoreHost, type CodexCoreHost } from "../agents/codex/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { CodexOptions } from "../../../packages/supermux-core/src/codex/index.js"

function fakeChildFactory(nativeId = "codex-sess-1") {
  const opens: DriverContext[] = []
  const codexCalls: { options: CodexOptions }[] = []
  const factory = (gopts: CodexOptions, _overrides: SessionConfiguration): AgentDriver => {
    codexCalls.push({ options: { ...gopts, env: { ...gopts.env }, args: [...gopts.args] } })
    return {
      id: "codex",
      async open(ctx) {
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? nativeId,
          capabilities: {
            resume: true, steer: false, fork: false, detach: false,
            configure: true, history: false,
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
  return { factory, opens, codexCalls }
}

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const hosts: CodexCoreHost[] = []
const dirs: string[] = []
const prevKey = process.env.OPENAI_API_KEY

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close().catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
  if (prevKey === undefined) delete process.env.OPENAI_API_KEY
  else process.env.OPENAI_API_KEY = prevKey
})

describe("Codex spawn", () => {
  test("registers a codex session with pid 0 and starts the Core adapter", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-codex-core-"))
    dirs.push(dir)
    const host = createCodexCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-"))
    dirs.push(workdir)
    const persisted: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      codexHost: host,
      onThreadId: (_name, sid) => persisted.push(sid),
    }, {
      workdir,
      requestedName: "codex-core",
      agent: AgentKind.Codex,
    })

    expect(result.name).toBe("codex-core")
    expect(result.pid).toBe(0)
    const row = reg.get(result.session_id)
    expect(row?.agent).toBe(AgentKind.Codex)
    expect(row?.pid).toBe(0)
    expect(persisted).toContain("codex-sess-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
    expect(child.codexCalls[0]?.options.env?.CODEX_HOME).toBe(row?.agent_home)
    expect(existsSync(join(row!.agent_home!, "config.toml"))).toBe(true)
    expect(readFileSync(join(row!.agent_home!, "config.toml"), "utf8")).toContain("mux-shim")
  })
})
