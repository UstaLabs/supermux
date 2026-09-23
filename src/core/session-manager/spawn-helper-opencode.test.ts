import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createOpenCodeCoreHost, type OpenCodeCoreHost } from "../agents/opencode/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { OpenCodeOptions } from "../../../packages/supermux-core/src/agents/index.js"

function fakeChildFactory(nativeId = "oc-sess-1") {
  const opens: DriverContext[] = []
  const ocCalls: { options: OpenCodeOptions }[] = []
  const factory = (gopts: OpenCodeOptions, _overrides: SessionConfiguration): AgentDriver => {
    ocCalls.push({ options: { ...gopts, env: { ...gopts.env } } })
    return {
      id: "opencode",
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
  return { factory, opens, ocCalls }
}

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const hosts: OpenCodeCoreHost[] = []
const dirs: string[] = []

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

describe("OpenCode spawn", () => {
  test("registers an opencode session with pid 0 and starts the ACP adapter", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-oc-core-"))
    dirs.push(dir)
    const host = createOpenCodeCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-"))
    dirs.push(workdir)
    const persisted: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      opencodeHost: host,
      onOpenCodeSessionId: (_name, sid) => persisted.push(sid),
    }, {
      workdir,
      requestedName: "oc-no-tmux",
      agent: AgentKind.OpenCode,
    })

    expect(result.name).toBe("oc-no-tmux")
    expect(result.pid).toBe(0)
    const row = reg.get(result.session_id)
    expect(row?.agent).toBe(AgentKind.OpenCode)
    expect(persisted).toContain("oc-sess-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("registers mux-shim in session-private opencode.json and writes AGENTS.md in session home", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-oc-core-"))
    dirs.push(dir)
    const host = createOpenCodeCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-"))
    dirs.push(workdir)

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      opencodeHost: host,
    }, {
      workdir,
      requestedName: "oc-shim",
      agent: AgentKind.OpenCode,
    })

    expect(child.opens[0]?.cwd).toBe(workdir)
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(child.ocCalls[0]?.options.env?.XDG_CONFIG_HOME).toBe(join(sessionHome, "config"))

    const json = readFileSync(join(sessionHome, "config", "opencode", "opencode.json"), "utf8")
    expect(json).toContain("mux-shim")
    expect(json).toContain(result.session_id)
    expect(json).toContain("oc-shim")
    expect(existsSync(join(sessionHome, "AGENTS.md"))).toBe(true)
  })

  test("permissionMode ask is stored and applied on first open", async () => {
    const child = fakeChildFactory()
    const dir = mkdtempSync(join(tmpdir(), "mux-oc-core-"))
    dirs.push(dir)
    const host = createOpenCodeCoreHost({ stateDirectory: dir, driverFactory: child.factory })
    hosts.push(host)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-"))
    dirs.push(workdir)

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      opencodeHost: host,
    }, {
      workdir,
      requestedName: "oc-ask",
      agent: AgentKind.OpenCode,
      permissionMode: "ask",
    })

    expect(reg.get(result.session_id)?.permissionMode).toBe("ask")
    expect(child.opens).toHaveLength(1)
    const json = readFileSync(join(reg.get(result.session_id)!.agent_home!, "config", "opencode", "opencode.json"), "utf8")
    const parsed = JSON.parse(json) as { permission?: Record<string, string> }
    expect(parsed.permission?.edit).toBe("ask")
    expect(parsed.permission?.bash).toBe("ask")
  })
})
