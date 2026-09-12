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
import { cursor } from "supermux-core/cursor"
import { grok, opencode } from "supermux-core/agents"

const selected = process.argv[2]
const allowed = new Set(["claude", "codex", "grok", "opencode", "cursor"])
if (!selected || !allowed.has(selected)) {
  throw new Error("Usage: bun examples/real-agents.mjs <claude|codex|grok|opencode|cursor>")
}

function driverFor(name) {
  if (name === "claude") {
    return claude({
      inheritEnv: true,
      tools: [],
      permissionPrompts: "none",
    })
  }
  if (name === "codex") {
    return codex({
      inheritEnv: true,
      sandbox: "read-only",
      approvalPolicy: "never",
    })
  }
  if (name === "grok") {
    return grok({
      inheritEnv: true,
      alwaysApprove: false,
      noLeader: true,
    })
  }
  if (name === "opencode") {
    return opencode({ inheritEnv: true })
  }
  return cursor({ inheritEnv: true, sandbox: "enabled" })
}

const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-real-"))
const core = createCore({
  stateDirectory,
  agents: [driverFor(selected)],
})
try {
  const session = await core.sessions.create({ agent: selected, cwd: process.cwd() })
  const receipt = await session.send({
    content: [{ type: "text", text: "Reply with the single word pong. Do not use tools." }],
    whenBusy: "queue",
  })
  console.log(JSON.stringify({ agent: selected, completion: await receipt.completed, record: session.snapshot() }))
  await session.close()
} finally {
  await core.close()
  await rm(stateDirectory, { recursive: true, force: true })
}
