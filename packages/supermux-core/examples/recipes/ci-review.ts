// A review of the current branch for CI: Codex reads the diff against a base and lists the bugs it finds.
//
//   node ci-review.ts [--base origin/main]
//
// Run it in the repository. Codex can read it and cannot change it (read-only sandbox), and is never asked anything
// (approval policy "never"). The review, the agent's last message, goes to stdout. Exit code 1 means there is none: the
// base is no commit, the agent could not run or its turn failed, or it said nothing.
import { execFile } from "node:child_process"
import { mkdtemp, rm } from "node:fs/promises"
import { constants, tmpdir } from "node:os"
import { join } from "node:path"
import { parseArgs, promisify } from "node:util"
import { supermux } from "@supermux/core"

const { values: { base } } = parseArgs({ options: { base: { type: "string", default: "origin/main" } } })
// Without this check a base git does not know comes back as a "review" that says so, with exit code 0.
await promisify(execFile)("git", ["rev-parse", "--verify", "--quiet", `${base}^{commit}`]).catch(() => {
  console.error(`${base} is not a commit of this repository: fetch it, or pass --base`)
  process.exit(1)
})

const stateDirectory = await mkdtemp(join(tmpdir(), "ci-review-"))
const core = supermux({ stateDirectory, agents: { codex: { sandbox: "read-only", approvalPolicy: "never" } } })

// One close, whoever asks first: the end of the review or a signal.
let closing: Promise<void> | undefined
const close = () => (closing ??= core.close({ agents: "shutdown" })
  .finally(() => rm(stateDirectory, { recursive: true, force: true })))
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => close().catch(console.error).finally(() => process.exit(128 + constants.signals[signal])))
}

// The review is the turn's last message, read once the turn is over: a receipt settles before the last events arrive.
// What the agent still asks is refused (a request waits for ever, and the turn with it).
const review = new Promise<string>(resolve => {
  let last = ""
  core.subscribe(event => {
    if (event.type !== "session.event") return
    const body = event.event
    const session = core.sessions.live(event.sessionId)
    if (body.kind === "assistant-message" && !body.subagentId && body.text.trim()) last = body.text
    else if (body.kind === "turn-complete") resolve(last)
    else if (body.kind === "permission-request") {
      const reject = body.options.find(option => option.kind === "reject_once")
      if (reject) session?.requests.respond(body.requestId, { optionId: reject.id }).catch(console.error)
      else session?.interrupt({ pending: "discard" }).catch(console.error) // nothing to refuse with: end the turn
    } else if (body.kind === "user-question" && body.blocking) {
      session?.requests.respond(body.requestId, { decline: true }).catch(console.error)
    }
  })
})

try {
  const session = await core.sessions.create({ id: "review", agent: "codex", cwd: process.cwd() })
  const prompt = [
    `Review the changes on this branch: run \`git diff ${base}...HEAD\` and read what it shows.`,
    "List the concrete bugs you find, each with its file:line, or say there are none. Do not modify any file.",
  ].join(" ")
  const { completed } = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "queue" })
  const completion = await completed
  if (completion.status !== "completed") {
    throw completion.status === "failed" ? completion.error : new Error(`the turn was ${completion.status}`)
  }
  const text = await review
  if (!text) throw new Error("the agent sent no review")
  console.log(text)
} catch (error) {
  console.error(`No review: ${error instanceof Error ? error.message : error}`)
  process.exitCode = 1
} finally {
  await close()
}
