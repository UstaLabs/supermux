// Run one turn on a real agent CLI with your own login:
//   node examples/real-agents.mjs <claude|codex|grok|opencode|cursor> [prompt]
// The agent runs in its CLI's own asking mode. Nobody is here to answer it, so a permission request
// is refused and a question declined (a pending request never expires, so the turn would wait for
// ever), and the default prompt asks the agent not to use tools. Ctrl-C closes the core before the
// example exits, so no agent process is left behind. The state (each session's keeper) is in
// ~/.supermux-examples/real-agents-<time>, for you alone, and removed once the core has closed.
import { mkdir, rm } from "node:fs/promises"
import { constants, homedir } from "node:os"
import { dirname, join } from "node:path"
import { supermux } from "@supermux/core"

const agents = ["claude", "codex", "grok", "opencode", "cursor"]
const agent = process.argv[2]
const prompt = process.argv[3] ?? "Reply with the single word pong. Do not use tools."
if (!agents.includes(agent)) throw new Error(`Usage: node examples/real-agents.mjs <${agents.join("|")}> [prompt]`)

// The state holds each keeper's socket, journal and token, and the core and the keepers write it as you do: keep it where
// no agent can write, never in the temporary directory (sandboxed Codex can write there). A folder of this run's own
// under the home, for you alone.
const stateDirectory = join(homedir(), ".supermux-examples", `real-agents-${Date.now()}`)
await mkdir(dirname(stateDirectory), { recursive: true, mode: 0o700 })
await mkdir(stateDirectory, { mode: 0o700 })
const core = supermux({ stateDirectory, agents: { [agent]: {} } })

// The assistant's text streams to stderr, and a note starts on a line of its own.
let midLine = false
const endLine = () => {
  if (midLine) process.stderr.write("\n")
  midLine = false
}
const note = text => {
  endLine()
  console.error(text)
}

// One close, whoever asks first: the end of the turn or a signal. The state is removed once the core has closed, and not
// before: when it could not (a keeper may still run), the keeper's files stay, and a note says where and what to do.
let closing
const close = () => (closing ??= core.close({ agents: "shutdown" }).then(
  () => rm(stateDirectory, { recursive: true, force: true }),
  error => {
    note("The core did not close, so an agent or its keeper may still be running.")
    note(`Its state is left in ${stateDirectory}`)
    note(`To stop them, kill the processes named by keeperPid and agentPid in keepers/*/status.json there; then remove the state: rm -r ${JSON.stringify(stateDirectory)}`)
    throw error
  },
))
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, () => close().catch(console.error).finally(() => process.exit(128 + constants.signals[signal])))
}

// Says `done` once the answer has gone through, or why it has not.
const settle = (answer, done) => answer.then(() => note(done), error => note(`could not answer: ${error.message}`))

function refuse(session, request) {
  if (!session) return
  const what = `permission request: ${request.toolCall.title}${request.detail?.command ? ` ${JSON.stringify(request.detail.command)}` : ""}`
  const reject = request.options.find(option => option.kind === "reject_once")
  if (reject) {
    settle(session.requests.respond(request.requestId, { optionId: reject.id }), `refused ${what}`)
  } else {
    // An agent that offers nothing to refuse with cannot be answered: end its turn instead. The
    // turn's own completion says how that went (interrupt() rejects once the example closes the core).
    note(`cannot refuse ${what}; interrupting the turn`)
    session.interrupt({ pending: "discard" }).catch(() => {})
  }
}

function decline(session, question) {
  if (!session) return
  const prompts = question.questions.map(item => JSON.stringify(item.prompt)).join(" ")
  settle(session.requests.respond(question.requestId, { decline: true }), `declined question: ${prompts}`)
}

core.subscribe(({ type, sessionId, event }) => {
  if (type !== "session.event") return
  if (event.kind === "assistant-delta") {
    process.stderr.write(event.text)
    midLine = !event.text.endsWith("\n")
  } else if (event.kind === "permission-request") {
    refuse(core.sessions.live(sessionId), event)
  } else if (event.kind === "user-question" && event.blocking) {
    decline(core.sessions.live(sessionId), event)
  }
})

try {
  const session = await core.sessions.create({ id: "demo", agent, cwd: process.cwd() })
  const { completed } = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "queue" })
  const completion = await completed
  endLine()
  console.log(JSON.stringify({ agent, completion: completion.status === "failed" ? { status: "failed", error: completion.error.message } : completion }))
} finally {
  await close()
}
