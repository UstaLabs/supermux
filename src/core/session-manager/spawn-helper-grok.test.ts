import { describe, expect, test } from "bun:test"
import { join } from "path"
import { mkdtempSync, existsSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { AgentKind } from "../../shared/agents"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import type { AcpClient } from "../agents/grok/acp-client"

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

    const result = await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      spawnTmux: async () => { throw new Error("grok must not spawn tmux") },
      grokRunnerFactory: () => fakeRunner(),
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

  test("passes the mux-shim MCP server into session/new and writes the AGENTS.md preamble", async () => {
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-"))
    let newParams: any

    await spawnSession({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      spawnTmux: async () => { throw new Error("grok must not spawn tmux") },
      grokRunnerFactory: () => fakeRunner((p) => { newParams = p }),
    }, {
      workdir,
      requestedName: "grok-shim",
      agent: AgentKind.Grok,
    })

    expect(newParams.cwd).toBe(workdir)
    const shim = newParams.mcpServers.find((s: any) => s.name === "mux-shim")
    expect(shim).toBeTruthy()
    expect(shim.env.find((e: any) => e.name === "MUX_DISPLAY_NAME").value).toBe("grok-shim")
    expect(shim.env.find((e: any) => e.name === "MUX_AGENT_KIND").value).toBe("grok")

    const preamble = join(workdir, "AGENTS.md")
    expect(existsSync(preamble)).toBe(true)
    expect(readFileSync(preamble, "utf8")).toContain("grok-shim")
  })
})
