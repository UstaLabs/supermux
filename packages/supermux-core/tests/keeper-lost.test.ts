// A keeper connection that closes WITHOUT an `exit` frame (the keeper exited right after its agent
// died, it crashed, or it was killed) while the driver is still in setup. Before the fix the
// transport's close() waited for a socket 'close' event that had already fired: driver.open never
// settled (its setup timeout shares that close), so core.sessions.resume never settled and the
// broker's sequential boot stopped there (live: Codex "Friendly Chat", muxShim "host",
// 2026-10-05). Every agent, through the core, with a host MCP server in the session context.
import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmodSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { z } from "zod"
import { cursor, grok, opencode } from "../src/agents/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import { createCore, type Core } from "../src/core.js"
import { connectKeeper } from "../src/keeper/client.js"
import { mcpServer, tool } from "../src/mcp/index.js"
import type { AgentDriver } from "../src/types.js"
import { TEST_ACP_PERMISSIONS, TEST_CLAUDE_PERMISSIONS, TEST_CODEX_PERMISSIONS, TEST_LIMITS } from "./helpers.js"

setDefaultTimeout(40_000)
const dirs: string[] = []
const cores: Core[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "keeper-lost-")); dirs.push(dir); return dir }
const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const LIMITS = { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 }
// Setup timeouts far above the hang guard: a pass must come from the lost connection, not a timer.
const FIXED = { setupTimeoutMs: 20_000, shutdownTimeoutMs: 500, maxFrameBytes: 16 * 1024 * 1024 }
const ACP = { ...FIXED, maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, inheritEnv: true }
const HANG_GUARD_MS = 10_000

function alive(pid: number): boolean { try { process.kill(pid, 0); return true } catch { return false } }

function opencodeShim(): string {
  const path = join(scratch(), "opencode")
  writeFileSync(path, `#!/bin/sh\nexec "${process.execPath}" "${fixture("acp-agent.mjs")}" "$@"\n`)
  chmodSync(path, 0o755)
  return path
}

/** Each agent's driver; `hang` = an env under which the agent never answers its first setup request. */
const AGENTS: Record<string, { hang: Record<string, string>; driver(keeperDir: string, env: Record<string, string>): AgentDriver }> = {
  claude: {
    hang: { MODE: "init-hang" },
    driver: (dir, env) => claude({
      id: "claude", command: process.execPath, args: [fixture("claude-agent.mjs")], env, inheritEnv: true, tools: [], permissionPrompts: "none",
      permissions: TEST_CLAUDE_PERMISSIONS, partialMessages: false, ...FIXED, requestTimeoutMs: 20_000, keeper: { stateDirectory: dir, limits: LIMITS },
    }),
  },
  codex: {
    hang: { MODE: "setup-hang" },
    driver: (dir, env) => codex({
      id: "codex", command: process.execPath, args: [fixture("codex-agent.mjs")], env, inheritEnv: true, sandbox: "read-only", approvalPolicy: "never",
      permissionPrompts: "none", permissions: TEST_CODEX_PERMISSIONS, ...FIXED, requestTimeoutMs: 20_000, keeper: { stateDirectory: dir, limits: LIMITS },
    }),
  },
  grok: {
    hang: { FIXTURE_MODE: "hang" },
    driver: (dir, env) => grok({
      id: "grok", command: process.execPath, commandArgs: [fixture("grok-agent.mjs")], env, noLeader: true, mcpServers: [],
      permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: { stateDirectory: dir, limits: LIMITS },
    }),
  },
  cursor: {
    hang: { FIXTURE_MODE: "hang" },
    driver: (dir, env) => cursor({
      id: "cursor", command: process.execPath, commandArgs: [fixture("cursor-agent.mjs")], env, mcpServers: [],
      permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: { stateDirectory: dir, limits: LIMITS },
    }),
  },
  opencode: {
    hang: { FIXTURE_MODE: "hang" },
    driver: (dir, env) => opencode({
      id: "opencode", command: opencodeShim(), env, mcpServers: [], permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: { stateDirectory: dir, limits: LIMITS },
    }),
  },
}

/** Waits until the keeper of `id` has passed the client's first frame to the agent, then SIGKILLs the keeper (no exit frame) and its agent. */
async function killKeeperInSetup(keeperDir: string, id: string): Promise<void> {
  const dir = join(keeperDir, "keepers", id)
  const start = Date.now()
  for (;;) {
    const journal = join(dir, "journal.ndjson"), status = join(dir, "status.json")
    if (existsSync(journal) && existsSync(status) && readFileSync(journal, "utf8").includes('"dir":"in"')) break
    if (Date.now() - start > 15_000) throw new Error("the driver never wrote its first setup frame")
    await Bun.sleep(10)
  }
  const { keeperPid, agentPid } = JSON.parse(readFileSync(join(dir, "status.json"), "utf8"))
  process.kill(keeperPid, "SIGKILL")
  try { process.kill(agentPid, "SIGKILL") } catch { /* already gone */ }
}

function settleWithin<T>(promise: Promise<T>, ms: number): Promise<{ outcome: "resolved" | "rejected" | "hung"; error?: unknown }> {
  return Promise.race([
    promise.then(() => ({ outcome: "resolved" as const }), error => ({ outcome: "rejected" as const, error })),
    Bun.sleep(ms).then(() => ({ outcome: "hung" as const })),
  ])
}

test("keeper connection: detach() settles when the keeper already closed the socket (no exit frame)", async () => {
  const conn = await connectKeeper({
    stateDirectory: scratch(), sessionId: "lost-1",
    spec: { command: process.execPath, args: ["-e", "setInterval(() => {}, 1 << 30)"], cwd: process.cwd(), env: {}, frameShape: "jsonrpc", captureStderr: false },
    limits: { maxFrameBytes: 1 << 20, shutdownTimeoutMs: 500, ...LIMITS },
    cursor: "acked",
  })
  const status = JSON.parse(readFileSync(join(dirs.at(-1)!, "keepers", "lost-1", "status.json"), "utf8"))
  process.kill(status.keeperPid, "SIGKILL")
  for await (const _ of conn.frames) { /* drain until the socket is gone */ }
  expect((await settleWithin(conn.detach(), 3000)).outcome).toBe("resolved")
  expect((await settleWithin(conn.shutdown(), 3000)).outcome).toBe("resolved")
  try { process.kill(status.agentPid, "SIGKILL") } catch { /* */ }
})

for (const [agent, spec] of Object.entries(AGENTS)) {
  test(`${agent}: resume with a host MCP server whose keeper dies in setup rejects instead of hanging`, async () => {
    const state = scratch(), keeperDir = scratch()
    const orders = mcpServer({ name: "orders", tools: { lookup: tool({ description: "Look up", input: z.object({ id: z.string() }), run: ({ id }) => `order ${id}` }) } })
    const context = { mcpServers: [{ kind: "host" as const, name: "orders" }] }
    const first = createCore({ stateDirectory: state, agents: [spec.driver(keeperDir, {})], limits: TEST_LIMITS, mcpServers: [orders] })
    cores.push(first)
    await first.sessions.create({ id: `lost-${agent}`, agent, cwd: process.cwd(), context })
    await first.close({ agents: "shutdown" })
    // The first launch's keeper is gone; its files go too, so the wait below sees only the resume's keeper.
    await rm(join(keeperDir, "keepers", `lost-${agent}`), { recursive: true, force: true })

    const second = createCore({ stateDirectory: state, agents: [spec.driver(keeperDir, spec.hang)], limits: TEST_LIMITS, mcpServers: [orders] })
    cores.push(second)
    const resumed = second.sessions.resume(`lost-${agent}`)
    await killKeeperInSetup(keeperDir, `lost-${agent}`)
    const settled = await settleWithin(resumed, HANG_GUARD_MS)
    expect(settled.outcome).toBe("rejected")
    // The core is usable afterwards: the session is not live and can be closed by id.
    expect(second.sessions.live(`lost-${agent}`)).toBeUndefined()
    expect((await settleWithin(second.sessions.close(`lost-${agent}`, { mode: "shutdown" }), 3000)).outcome).toBe("resolved")
    const status = JSON.parse(readFileSync(join(keeperDir, "keepers", `lost-${agent}`, "status.json"), "utf8"))
    expect(alive(status.agentPid)).toBe(false)
  })
}
