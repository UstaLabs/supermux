// After "bun run build", run: bun examples/lifecycle.mjs /path/to/acp-agent [args...]
// A session on an ACP agent from create to resume. The agent runs with the "ask" policy and nobody is here to answer it,
// so a permission request is refused and a question declined, and each is said on stderr (a pending request never
// expires, so the turn would wait for ever). Ctrl-C closes the core before the example exits, so no agent process is left
// behind. The state (the session's record and its keeper) is in ~/.supermux-examples/lifecycle-<time>, for you alone, and
// removed once the core has closed.
import { mkdir, rm } from "node:fs/promises"
import { constants, homedir } from "node:os"
import { dirname, join } from "node:path"
import { createCore } from "@supermux/core"
import { acp } from "@supermux/core/acp"

const [command, ...args] = process.argv.slice(2)
if (!command) throw new Error("Supply an ACP executable and optional arguments")
// The state holds the keeper's socket, journal and token, and the core and the keeper write it as you do: keep it where no
// agent can write, never in the temporary directory (a sandboxed agent can write there). A folder of this run's own under
// the home, for you alone.
const stateDirectory = join(homedir(), ".supermux-examples", `lifecycle-${Date.now()}`)
await mkdir(dirname(stateDirectory), { recursive: true, mode: 0o700 })
await mkdir(stateDirectory, { mode: 0o700 })
const core = createCore({
  stateDirectory,
  agents: [acp({
    id: "example",
    command,
    args,
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 30_000,
    shutdownTimeoutMs: 2_000,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    captureStderr: false,
    permissions: { kind: "acp", policy: "ask", nativeMode: null },
    keeper: {
      stateDirectory,
      limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
    },
  })],
  limits: { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
})

// One close, whoever asks first: the end of the run or a signal. The state is removed once the core has closed, and not
// before: when it could not (a keeper may still run), the keeper's files stay, and a note says where and what to do.
let closing
const close = () => (closing ??= core.close({ agents: "shutdown" }).then(
  () => rm(stateDirectory, { recursive: true, force: true }),
  error => {
    console.error("The core did not close, so an agent or its keeper may still be running.")
    console.error(`Its state is left in ${stateDirectory}`)
    console.error(`To stop them, kill the processes named by keeperPid and agentPid in keepers/*/status.json there; then remove the state: rm -r ${JSON.stringify(stateDirectory)}`)
    throw error
  },
))
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, () => close().catch(console.error).finally(() => process.exit(128 + constants.signals[signal])))
}

core.subscribe(event => {
  console.log(event)
  if (event.type !== "session.event") return
  // What the agent asks is refused, and said: a permission request gets its reject_once option, a question a decline.
  const body = event.event
  const session = core.sessions.live(event.sessionId)
  if (body.kind === "permission-request") {
    console.error(`refused: ${body.toolCall.title}`)
    const reject = body.options.find(option => option.kind === "reject_once")
    if (reject) session?.requests.respond(body.requestId, { optionId: reject.id }).catch(console.error)
    else session?.interrupt({ pending: "discard" }).catch(console.error) // nothing to refuse with: end the turn
  } else if (body.kind === "user-question" && body.blocking) {
    console.error(`declined: ${body.questions.map(question => question.prompt).join(" ")}`)
    session?.requests.respond(body.requestId, { decline: true }).catch(console.error)
  }
})
try {
  // Create-time configuration is applied before native open when nonempty.
  // ACP generic does not advertise configure; omit configuration here.
  const session = await core.sessions.create({ id: "example-session", agent: "example", cwd: process.cwd() })
  const receipt = await session.send({ content: [{ type: "text", text: "Hello" }], whenBusy: "queue" })
  console.log("Completion:", await receipt.completed)
  await session.close({ mode: "shutdown" })
  if (session.capabilities().resume) {
    const resumed = await core.sessions.resume(session.id)
    console.log("Restored:", resumed.snapshot())
    // Explicit patch: core.sessions.resume(session.id, { configuration: { model: "..." } })
    // merges before native open. Omit options to keep persisted configuration.
  }
} finally {
  await close()
}
