// Run one turn on a real agent CLI with your own login:
//   node examples/real-agents.mjs <claude|codex|grok|opencode|cursor>
// The agent runs in its CLI's own asking mode; the prompt asks it not to use tools.
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { supermux } from "@supermux/core"

const agents = ["claude", "codex", "grok", "opencode", "cursor"]
const agent = process.argv[2]
if (!agents.includes(agent)) throw new Error(`Usage: node examples/real-agents.mjs <${agents.join("|")}>`)

const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-real-"))
const core = supermux({ stateDirectory, agents: { [agent]: {} } })
core.subscribe(event => {
  if (event.type === "session.event" && event.event.kind === "assistant-delta") process.stderr.write(event.event.text)
})
try {
  const session = await core.sessions.create({ id: "demo", agent, cwd: process.cwd() })
  const { completed } = await session.send({
    content: [{ type: "text", text: "Reply with the single word pong. Do not use tools." }],
    whenBusy: "queue",
  })
  const completion = await completed
  console.log(JSON.stringify({ agent, completion: completion.status === "failed" ? { status: "failed", error: completion.error.message } : completion }))
} finally {
  await core.close({ agents: "shutdown" })
  await rm(stateDirectory, { recursive: true, force: true })
}
