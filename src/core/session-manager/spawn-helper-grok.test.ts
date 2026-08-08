import { afterAll, describe, expect, mock, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, existsSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import type { AcpClient } from "../agents/grok/acp-client"
import type { GrokRunner } from "../agents/grok/runner"

// The grok stdio runner is swapped via a bun module mock (the spawn path has
// no injection seams); each test points `currentRunner` at its own fake.
// Snapshot-capture the real exports and restore in afterAll.
const realGrokRunnerModule = { ...(await import("../agents/grok/runner")) }
let currentRunner: GrokRunner
mock.module("../agents/grok/runner", () => ({
  ...realGrokRunnerModule,
  realGrokRunner: ((opts) => currentRunner(opts)) as GrokRunner,
}))
afterAll(() => {
  mock.module("../agents/grok/runner", () => realGrokRunnerModule)
})

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  return new Registry(db)
}

// A runner that answers the ACP handshake (initialize -> session/new) by hand, so
// spawnGrokSession's `await adapter.start()` resolves without a real grok binary.
function fakeRunner(onSessionNew?: (params: any) => void) {
  return (opts: { client: AcpClient }) => {
    const client = opts.client
    client.setWrite((line: string) => {
      const msg = JSON.parse(line)
      if (msg.method === "initialize") {
        queueMicrotask(() => client.feed(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { protocolVersion: 1 } }) + "\n"))
      } else if (msg.method === "session/new") {
        onSessionNew?.(msg.params)
        queueMicrotask(() => client.feed(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { sessionId: "grok-sess-1" } }) + "\n"))
      }
    })
    return { kill: () => {} }
  }
}

describe("Grok spawn", () => {
  test("registers a grok session with no tmux window and starts the ACP adapter", async () => {
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-"))
    const persisted: string[] = []

    currentRunner = fakeRunner() as unknown as GrokRunner
    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
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
    // session id came back over ACP and was persisted for resume
    expect(persisted).toContain("grok-sess-1")
  })

  test("registers mux-shim in the session-private config.toml and writes the AGENTS.md preamble", async () => {
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-"))
    let newParams: any
    let runnerEnv: any

    currentRunner = ((opts: any) => { runnerEnv = opts.env; return fakeRunner((p) => { newParams = p })(opts) }) as unknown as GrokRunner
    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
    }, {
      workdir,
      requestedName: "grok-shim",
      agent: AgentKind.Grok,
    })

    // grok ignores mcpServers on session/new — the shim must NOT be smuggled there.
    expect(newParams.cwd).toBe(workdir)
    expect(newParams.mcpServers).toEqual([])

    // HOME is redirected so grok cannot auto-import the user's ~/.claude.json MCPs.
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(runnerEnv.HOME).toBe(sessionHome)

    const toml = readFileSync(join(sessionHome, ".grok", "config.toml"), "utf8")
    expect(toml).toContain("[mcp_servers.mux-shim]")
    expect(toml).toContain(`MUX_SESSION_ID = ${JSON.stringify(result.session_id)}`)
    expect(toml).toContain('MUX_DISPLAY_NAME = "grok-shim"')
    expect(toml).toContain('MUX_AGENT_KIND = "grok"')
    // Belt-and-braces against the claude config import.
    expect(toml).toContain("[claude_compat]")
    expect(toml).toContain("imported = true")

    const preamble = join(workdir, "AGENTS.md")
    expect(existsSync(preamble)).toBe(true)
    expect(readFileSync(preamble, "utf8")).toContain("grok-shim")
  })
})
