// After "bun run build", run: bun examples/lifecycle.mjs /path/to/acp-agent [args...]
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "supermux-core"
import { acp } from "supermux-core/acp"

const [command, ...args] = process.argv.slice(2)
if (!command) throw new Error("Supply an ACP executable and optional arguments")
const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-example-"))
const core = createCore({ stateDirectory, agents: [acp({ id: "example", command, args })] })
core.subscribe(event => console.log(event))
try {
  // Create-time configuration is applied before native open when nonempty.
  // ACP generic does not advertise configure; omit configuration here.
  const session = await core.sessions.create({ agent: "example", cwd: process.cwd() })
  const receipt = await session.send({ content: [{ type: "text", text: "Hello" }], whenBusy: "queue" })
  console.log("Completion:", await receipt.completed)
  await session.close()
  if (session.capabilities().resume) {
    const resumed = await core.sessions.resume(session.id)
    console.log("Restored:", resumed.snapshot())
    // Explicit patch: core.sessions.resume(session.id, { configuration: { model: "..." } })
    // merges before native open. Omit options to keep persisted configuration.
  }
} finally {
  await core.close()
  await rm(stateDirectory, { recursive: true, force: true })
}
