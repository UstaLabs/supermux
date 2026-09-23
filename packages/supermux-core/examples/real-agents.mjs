// Opt-in live CLI. Run from a project that depends on the installed package, or from this
// repository after `bun run build`. Example: bun examples/real-agents.mjs <claude|codex|grok|opencode|cursor>
// Uses the caller’s environment as-is. This example does not copy credentials via copiedCredentials.
// Vendor CLIs may still refresh credentials, write history, or update files under HOME. Treat as
// opt-in live I/O. Default prompt is text-only and tells the agent not to use tools. Cursor remains
// fixture-oriented; live create-chat has timed out before.
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "supermux-core"
import { claude } from "supermux-core/claude"
import { codex } from "supermux-core/codex"
import { grok, opencode, cursor } from "supermux-core/agents"

const selected = process.argv[2]
const allowed = new Set(["claude", "codex", "grok", "opencode", "cursor"])
if (!selected || !allowed.has(selected)) {
  throw new Error("Usage: bun examples/real-agents.mjs <claude|codex|grok|opencode|cursor>")
}

function driverFor(name) {
  const keeper = {
    stateDirectory: join(tmpdir(), `supermux-core-real-${name}-keeper`),
    limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
  }
  const acpShared = {
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 30_000,
    shutdownTimeoutMs: 2_000,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper,
  }
  if (name === "claude") {
    return claude({
      id: "claude",
      command: "claude",
      args: [],
      inheritEnv: true,
      tools: [],
      permissionPrompts: "none",
      permissions: { kind: "claude", permissionMode: "dontAsk" },
      setupTimeoutMs: 30_000,
      requestTimeoutMs: 30_000,
      shutdownTimeoutMs: 2_000,
      maxFrameBytes: 16 * 1024 * 1024,
      keeper,
    })
  }
  if (name === "codex") {
    return codex({
      id: "codex",
      command: "codex",
      args: ["app-server"],
      inheritEnv: true,
      sandbox: "read-only",
      approvalPolicy: "never",
      permissionPrompts: "none",
      permissions: { kind: "codex", approvalPolicy: "never", sandbox: "read-only" },
      setupTimeoutMs: 30_000,
      requestTimeoutMs: 30_000,
      shutdownTimeoutMs: 2_000,
      maxFrameBytes: 16 * 1024 * 1024,
      keeper,
    })
  }
  if (name === "grok") {
    return grok({
      id: "grok",
      command: "grok",
      commandArgs: [],
      permissions: { kind: "acp", policy: "ask", nativeMode: null },
      noLeader: true,
      ...acpShared,
    })
  }
  if (name === "opencode") {
    return opencode({
      id: "opencode",
      command: "opencode",
      ...acpShared,
    })
  }
  return cursor({
    id: "cursor",
    command: "cursor-agent",
    commandArgs: [],
    permissions: { kind: "acp", policy: "auto-approve", nativeMode: "agent" },
    ...acpShared,
  })
}

const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-real-"))
const core = createCore({
  stateDirectory,
  agents: [driverFor(selected)],
  limits: { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
})
try {
  const session = await core.sessions.create({ id: "real-session", agent: selected, cwd: process.cwd() })
  const receipt = await session.send({
    content: [{ type: "text", text: "Reply with the single word pong. Do not use tools." }],
    whenBusy: "queue",
  })
  console.log(JSON.stringify({ agent: selected, completion: await receipt.completed, record: session.snapshot() }))
  await session.close({ mode: "shutdown" })
} finally {
  await core.close({ agents: "shutdown" })
  await rm(stateDirectory, { recursive: true, force: true })
}
