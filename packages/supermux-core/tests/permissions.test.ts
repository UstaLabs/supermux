import { afterEach, expect, test, setDefaultTimeout } from "bun:test"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { mkdtempSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { createCore } from "../src/core.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import { acp } from "../src/acp/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext, PermissionsSpec } from "../src/types.js"
import { TEST_LIMITS, TEST_ACP_PERMISSIONS, TEST_CLAUDE_PERMISSIONS, TEST_CODEX_PERMISSIONS, nextId } from "./helpers.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(c => c.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(d => rm(d, { recursive: true, force: true })))
})

function keeperDir() {
  const stateDirectory = mkdtempSync(join(tmpdir(), "perm-k-"))
  dirs.push(stateDirectory)
  return { stateDirectory, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } }
}

test("Session persists spec, emits permissions-update, resume restores, capability enforced", async () => {
  const applied: PermissionsSpec[] = []
  const driver: AgentDriver = {
    id: "test",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: false, permissions: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
        async setPermissions(spec) {
          applied.push(spec)
          return { applied: spec.kind === "codex" ? "next-turn" : "now" }
        },
      }
      return runtime
    },
  }
  const stateDirectory = await mkdtemp(join(tmpdir(), "perm-core-"))
  dirs.push(stateDirectory)
  const core = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS })
  cores.push(core)
  const events: CoreEvent[] = []
  core.subscribe(e => events.push(e))
  const spec: PermissionsSpec = { kind: "claude", permissionMode: "plan" }
  const session = await core.sessions.create({ id: nextId(), agent: "test", cwd: tmpdir(), permissions: spec })
  expect(session.snapshot().permissions).toEqual(spec)
  const result = await session.setPermissions({ kind: "claude", permissionMode: "auto" })
  expect(result).toEqual({ applied: "now" })
  expect(applied.at(-1)).toEqual({ kind: "claude", permissionMode: "auto" })
  const update = events.find(e => e.type === "session.event" && e.event.kind === "permissions-update")
  expect(update).toBeTruthy()
  await core.close({ agents: "shutdown" })
  const next = createCore({ stateDirectory, agents: [driver], limits: TEST_LIMITS })
  cores.push(next)
  const resumed = await next.sessions.resume(session.id)
  expect(resumed.snapshot().permissions).toEqual({ kind: "claude", permissionMode: "auto" })

  const noPerm: AgentDriver = {
    id: "none",
    async open() {
      return {
        agentSessionId: "x",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
    },
  }
  const d2 = await mkdtemp(join(tmpdir(), "perm-none-"))
  dirs.push(d2)
  const core2 = createCore({ stateDirectory: d2, agents: [noPerm], limits: TEST_LIMITS })
  cores.push(core2)
  const s2 = await core2.sessions.create({ id: nextId(), agent: "none", cwd: tmpdir() })
  await expect(s2.setPermissions({ kind: "claude", permissionMode: "plan" })).rejects.toMatchObject({ code: "unsupported_operation" })
})

test("claude fixture handles set_permission_mode", async () => {
  const fixture = fileURLToPath(new URL("./fixtures/claude-agent.mjs", import.meta.url))
  const traceDir = await mkdtemp(join(tmpdir(), "claude-perm-"))
  dirs.push(traceDir)
  const trace = join(traceDir, "trace")
  const r = await claude({
    id: "claude",
    command: process.execPath,
    args: [fixture],
    env: { TRACE: trace },
    inheritEnv: true,
    tools: [],
    permissionPrompts: "none",
    permissions: TEST_CLAUDE_PERMISSIONS,
    partialMessages: false,
    setupTimeoutMs: 5000,
    requestTimeoutMs: 3000,
    shutdownTimeoutMs: 40,
    maxFrameBytes: 16 * 1024 * 1024,
    keeper: keeperDir(),
  }).open({
    sessionId: "c", cwd: process.cwd(), signal: new AbortController().signal,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }),
    requestAnswers: async () => ({ outcome: "cancelled" as const }),
  })
  try {
    expect(r.capabilities.permissions).toBe(true)
    const applied = await r.setPermissions!({ kind: "claude", permissionMode: "plan" })
    expect(applied).toEqual({ applied: "now" })
    const lines = (await readFile(trace, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l))
    expect(lines.some((m: { request?: { subtype?: string } }) => m.request?.subtype === "set_permission_mode")).toBe(true)
  } finally { await r.close({ mode: "shutdown" }) }
})

test("codex fixture asserts approvalPolicy/sandboxPolicy on turn/start after setPermissions", async () => {
  const fixture = fileURLToPath(new URL("./fixtures/codex-agent.mjs", import.meta.url))
  const r = await codex({
    id: "codex",
    command: process.execPath,
    args: [fixture],
    env: { EXPECT_TURN_POLICY: "untrusted", EXPECT_TURN_SANDBOX: "workspace-write" },
    inheritEnv: true,
    sandbox: "read-only",
    approvalPolicy: "never",
    permissionPrompts: "none",
    permissions: TEST_CODEX_PERMISSIONS,
    setupTimeoutMs: 5000,
    requestTimeoutMs: 3000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024,
    keeper: keeperDir(),
  }).open({
    sessionId: "x", cwd: process.cwd(), signal: new AbortController().signal,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }),
    requestAnswers: async () => ({ outcome: "cancelled" as const }),
  })
  try {
    expect(await r.setPermissions!({ kind: "codex", approvalPolicy: "untrusted", sandbox: "workspace-write" })).toEqual({ applied: "next-turn" })
    await r.prompt([{ type: "text", text: "hello" }], new AbortController().signal)
  } finally { await r.close({ mode: "shutdown" }) }
})

test("ACP auto-approve answers allow with no permission-request; ask surfaces; read-only rejects execute and approves read", async () => {
  const fixture = fileURLToPath(new URL("./fixtures/acp-agent.mjs", import.meta.url))
  const ctx = (extra: Partial<DriverContext> = {}): DriverContext => ({
    sessionId: "a", cwd: process.cwd(), signal: new AbortController().signal,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }),
    requestAnswers: async () => ({ outcome: "cancelled" as const }),
    ...extra,
  })
  const mk = (permissions: PermissionsSpec) => acp({
    id: "fixture", command: process.execPath, args: [fixture], inheritEnv: true, mcpServers: [],
    setupTimeoutMs: 3000, shutdownTimeoutMs: 40, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, captureStderr: false, keeper: keeperDir(),
    permissions: permissions as Extract<PermissionsSpec, { kind: "acp" }>,
  })
  const autoUpdates: unknown[] = []
  let host = 0
  const auto = await mk({ kind: "acp", policy: "auto-approve", nativeMode: null }).open(ctx({
    onUpdate: u => autoUpdates.push(u),
    requestPermission: async () => { host++; return { outcome: { outcome: "cancelled" } } },
  }))
  try {
    await auto.prompt([{ type: "text", text: "permission" }], new AbortController().signal)
    expect(host).toBe(0)
    expect(autoUpdates.some(u => (u as { value?: { method?: string } }).value?.method === "permission-auto")).toBe(true)
    expect(autoUpdates.some(u => (u as { value?: { method?: string } }).value?.method === "session/request_permission")).toBe(false)
  } finally { await auto.close({ mode: "shutdown" }) }

  let asked = 0
  const ask = await mk(TEST_ACP_PERMISSIONS).open(ctx({
    requestPermission: async () => { asked++; return { outcome: { outcome: "selected", optionId: "allow" } } },
  }))
  try {
    await ask.prompt([{ type: "text", text: "permission" }], new AbortController().signal)
    expect(asked).toBe(1)
  } finally { await ask.close({ mode: "shutdown" }) }

  const ro = await mk({ kind: "acp", policy: "read-only", nativeMode: null }).open(ctx())
  try {
    await ro.prompt([{ type: "text", text: "permission-execute" }], new AbortController().signal)
    await ro.prompt([{ type: "text", text: "permission-read" }], new AbortController().signal)
  } finally { await ro.close({ mode: "shutdown" }) }
})

test("ACP set_mode and set_config_option paths", async () => {
  const fixture = fileURLToPath(new URL("./fixtures/acp-agent.mjs", import.meta.url))
  const dir = await mkdtemp(join(tmpdir(), "acp-mode-"))
  dirs.push(dir)
  const trace = join(dir, "trace")
  const r = await acp({
    id: "fixture", command: process.execPath, args: [fixture],
    env: { TRACE: trace, ACP_MODES: "1" }, inheritEnv: true, mcpServers: [],
    setupTimeoutMs: 3000, shutdownTimeoutMs: 40, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, captureStderr: false, keeper: keeperDir(),
    permissions: { kind: "acp", policy: "ask", nativeMode: "plan" },
  }).open({
    sessionId: "m", cwd: process.cwd(), signal: new AbortController().signal,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }),
    requestAnswers: async () => ({ outcome: "cancelled" as const }),
  })
  try {
    const lines = (await readFile(trace, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l))
    expect(lines.some((row: { setMode?: { modeId?: string } }) => row.setMode?.modeId === "plan")).toBe(true)
  } finally { await r.close({ mode: "shutdown" }) }

  const dir2 = await mkdtemp(join(tmpdir(), "acp-cfg-"))
  dirs.push(dir2)
  const trace2 = join(dir2, "trace")
  const r2 = await acp({
    id: "fixture", command: process.execPath, args: [fixture],
    env: { TRACE: trace2, ACP_MODE_OPTION: "1" }, inheritEnv: true, mcpServers: [],
    setupTimeoutMs: 3000, shutdownTimeoutMs: 40, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, captureStderr: false, keeper: keeperDir(),
    permissions: { kind: "acp", policy: "ask", nativeMode: "ask" },
    sessionConfig: { mode: "agent" },
  }).open({
    sessionId: "m2", cwd: process.cwd(), signal: new AbortController().signal,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }),
    requestAnswers: async () => ({ outcome: "cancelled" as const }),
  })
  try {
    await r2.setPermissions!({ kind: "acp", policy: "ask", nativeMode: "plan" })
    const lines = (await readFile(trace2, "utf8")).trim().split("\n").filter(Boolean).map(l => JSON.parse(l))
    expect(lines.some((row: { setConfig?: { configId?: string; value?: string } }) => row.setConfig?.configId === "mode" && row.setConfig.value === "plan")).toBe(true)
  } finally { await r2.close({ mode: "shutdown" }) }
})
