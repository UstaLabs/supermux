import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createGrokCoreHost, type GrokCoreHost } from "../agents/grok/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { GrokOptions } from "../../../packages/supermux-core/src/agents/index.js"

function fakeChildFactory(nativeId = "grok-sess-1") {
  const opens: DriverContext[] = []
  const grokCalls: { options: GrokOptions }[] = []
  const factory = (gopts: GrokOptions, _overrides: SessionConfiguration): AgentDriver => {
    grokCalls.push({ options: { ...gopts, env: { ...gopts.env } } })
    return {
      id: "grok",
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
  return { factory, opens, grokCalls }
}

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const hosts: GrokCoreHost[] = []
const dirs: string[] = []

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close().catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

describe("Grok spawn", () => {
  test("registers a grok session with no tmux window and starts the ACP adapter", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-grok-core-"))
    dirs.push(dir)
    const host = createGrokCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-"))
    dirs.push(workdir)
    const persisted: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      grokHost: host,
      onGrokSessionId: (_name, sid) => persisted.push(sid),
    }, {
      workdir,
      requestedName: "grok-no-tmux",
      agent: AgentKind.Grok,
    })

    expect(result.name).toBe("grok-no-tmux")
    const row = reg.get(result.session_id)
    expect(row?.agent).toBe(AgentKind.Grok)
    expect(row?.tmux_target).toBe("")
    expect(persisted).toContain("grok-sess-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("registers mux-shim in the session-private config.toml and writes the AGENTS.md preamble", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-grok-core-"))
    dirs.push(dir)
    const host = createGrokCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-"))
    dirs.push(workdir)

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      grokHost: host,
    }, {
      workdir,
      requestedName: "grok-shim",
      agent: AgentKind.Grok,
    })

    expect(child.opens[0]?.cwd).toBe(workdir)
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(child.grokCalls[0]?.options.env?.HOME).toBe(sessionHome)

    const toml = readFileSync(join(sessionHome, ".grok", "config.toml"), "utf8")
    expect(toml).toContain("[mcp_servers.mux-shim]")
    expect(toml).toContain(`MUX_SESSION_ID = ${JSON.stringify(result.session_id)}`)
    expect(toml).toContain('MUX_DISPLAY_NAME = "grok-shim"')
    expect(toml).toContain('MUX_AGENT_KIND = "grok"')
    expect(toml).toContain("[claude_compat]")
    expect(toml).toContain("imported = true")

    const preamble = join(workdir, "AGENTS.md")
    expect(existsSync(preamble)).toBe(true)
    expect(readFileSync(preamble, "utf8")).toContain("grok-shim")
  })
})
