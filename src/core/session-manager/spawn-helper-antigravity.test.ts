import { afterEach, describe, expect, test } from "bun:test"
import { join } from "path"
import { existsSync, mkdirSync, mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { createAntigravityCoreHost, type AntigravityCoreHost } from "../agents/antigravity/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { AntigravityOptions } from "../../../packages/supermux-core/src/antigravity/index.js"

function fakeChildFactory(nativeId = "agy-conv-1") {
  const opens: DriverContext[] = []
  const calls: { options: AntigravityOptions }[] = []
  const factory = (options: AntigravityOptions, _overrides: SessionConfiguration): AgentDriver => {
    calls.push({ options: { ...options, env: { ...options.env } } })
    return {
      id: "antigravity",
      async open(ctx) {
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? nativeId,
          capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true, history: false },
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
  return { factory, opens, calls }
}

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

const hosts: AntigravityCoreHost[] = []
const dirs: string[] = []

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function setup(child: ReturnType<typeof fakeChildFactory>) {
  const dir = mkdtempSync(join(tmpdir(), "mux-agy-core-"))
  dirs.push(dir)
  const userHome = join(dir, "user")
  mkdirSync(join(userHome, ".gemini", "antigravity-cli"), { recursive: true })
  const host = createAntigravityCoreHost({ stateDirectory: join(dir, "state"), driverFactory: child.factory, userHome })
  hosts.push(host)
  const workdir = mkdtempSync(join(tmpdir(), "mux-agy-"))
  dirs.push(workdir)
  return { host, workdir }
}

describe("Antigravity spawn", () => {
  test("registers an antigravity session with no tmux window and starts the core adapter", async () => {
    const child = fakeChildFactory()
    const { host, workdir } = setup(child)
    const reg = registry()
    const persisted: string[] = []

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      antigravityHost: host,
      onAntigravitySessionId: (_name, sid) => persisted.push(sid),
    }, { workdir, requestedName: "agy-no-tmux", agent: AgentKind.Antigravity })

    expect(result.name).toBe("agy-no-tmux")
    const row = reg.get(result.session_id)
    expect(row?.agent).toBe(AgentKind.Antigravity)
    expect(row?.tmux_target).toBe("")
    expect(persisted).toContain("agy-conv-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("mux-shim and the instructions are session context; nothing is written into the repo", async () => {
    const child = fakeChildFactory()
    const { host, workdir } = setup(child)
    const reg = registry()

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      antigravityHost: host,
    }, { workdir, requestedName: "agy-shim", agent: AgentKind.Antigravity })

    expect(child.opens[0]?.cwd).toBe(workdir)
    const agentHome = reg.get(result.session_id)!.agent_home!
    expect(child.calls[0]?.options.env?.HOME).toBe(join(agentHome, "home"))
    const launched = child.opens[0]!.sessionContext!
    const shim = launched.mcpServers.find((server) => server.name === "mux-shim")!
    expect(shim.env).toMatchObject({ MUX_SESSION_ID: result.session_id, MUX_DISPLAY_NAME: "agy-shim", MUX_AGENT_KIND: "antigravity" })
    expect(launched.instructions).toContain("agy-shim")
    expect(existsSync(join(workdir, "GEMINI.md"))).toBe(false)
    expect(existsSync(join(workdir, "AGENTS.md"))).toBe(false)
  })

  test("permissionMode accept-edits maps onto the first open (no restart)", async () => {
    const child = fakeChildFactory()
    const { host, workdir } = setup(child)
    const reg = registry()

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      antigravityHost: host,
    }, { workdir, requestedName: "agy-edits", agent: AgentKind.Antigravity, permissionMode: "accept-edits" })

    expect(reg.get(result.session_id)?.permissionMode).toBe("accept-edits")
    expect(child.calls).toHaveLength(1)
    expect(child.calls[0]?.options.permissions).toEqual({ kind: "antigravity", mode: "acceptEdits" })
    expect(child.opens).toHaveLength(1)
  })
})
