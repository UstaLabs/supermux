// C3b: Claude's mux-shim per mode. "external": nothing in the context (it comes from
// ~/.claude.json / the account --mcp-config); an rpc worker's servers come from its json file.
// "host": the broker's host servers (mux-shim; rpc workers mux-rpc) in the context, and
// --strict-mcp-config kept for rpc workers.
import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime, DriverContext } from "../../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import { createClaudeCoreHost } from "./core-host"
import { claudeAccountArgs } from "../account-env"
import { setMuxShimMode } from "../../mux-tools/mode"
import { writeRpcWorkerMcpConfig } from "../../session-manager/trust"

const dirs: string[] = []
afterEach(() => {
  setMuxShimMode("external")
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

async function launch(extraPatch: Record<string, unknown> = {}) {
  const opens: DriverContext[] = []
  const options: ClaudeOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "claude-mux-shim-"))
  dirs.push(dir)
  const driver: AgentDriver = {
    id: "claude",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
  const host = createClaudeCoreHost({ stateDirectory: join(dir, "state"), driverFactory: (o) => { options.push(o); return driver } })
  const extra = { sessionHome: join(dir, "home"), sessionName: "s", sessionId: "id1", workdir: dir, cwd: dir, ...extraPatch }
  await host.register({ id: "id1", env: {}, extra }).start({ cwd: dir })
  await host.close({ agents: "shutdown" })
  const servers = (opens[0]!.sessionContext?.mcpServers ?? []).map((s) => ({ name: s.name, host: !!s.host, command: s.command }))
  return { servers, args: options[0]!.args ?? [], dir }
}

test("external: no mux-shim in the context; an rpc worker gets its json file's servers", async () => {
  expect((await launch()).servers).toEqual([])
  const dir = mkdtempSync(join(tmpdir(), "claude-rpc-")); dirs.push(dir)
  const rpc = join(dir, "rpc.json")
  writeRpcWorkerMcpConfig(rpc)
  const r = await launch({ rpcMcpConfig: rpc })
  expect(r.servers.map((s) => [s.name, s.host])).toEqual([["mux-rpc", false], ["mux-channel", false]])
  expect(r.args).toContain("--strict-mcp-config")
})

test("host: the context's mux-shim is the broker's host server (the core's bridge)", async () => {
  setMuxShimMode("host")
  const r = await launch()
  expect(r.servers).toEqual([{ name: "mux-shim", host: true, command: process.execPath }])
  expect(r.args).not.toContain("--strict-mcp-config")
})

test("host: an rpc worker gets ONLY the mux-rpc host server, still under --strict-mcp-config", async () => {
  setMuxShimMode("host")
  const dir = mkdtempSync(join(tmpdir(), "claude-rpc-")); dirs.push(dir)
  const rpc = join(dir, "rpc.json")
  writeRpcWorkerMcpConfig(rpc)
  const r = await launch({ rpcMcpConfig: rpc })
  expect(r.servers).toEqual([{ name: "mux-rpc", host: true, command: process.execPath }])
  expect(r.args).toContain("--strict-mcp-config")
})

test("host: a subscription account gets no mcp-account.json (the context covers every account)", async () => {
  setMuxShimMode("host")
  const dir = mkdtempSync(join(tmpdir(), "claude-acct-")); dirs.push(dir)
  const args = await claudeAccountArgs(["--x"], { sessionHome: dir, account: "claude:subscription-probe" })
  expect(args).toEqual(["--x"])
  expect(existsSync(join(dir, "mcp-account.json"))).toBe(false)
})
