// A host tool: the agent calls a function of this process. `deploy` is a tool of an MCP server whose tools are
// TypeScript functions, given to one session.
//
//   node deploy-tool.ts ["<prompt>"] [--agent claude|codex]
//
// The tool takes "staging" only: zod checks the agent's arguments before `run` is called.
import { createHash, randomUUID } from "node:crypto"
import { mkdir, rm } from "node:fs/promises"
import { constants, homedir } from "node:os"
import { basename, join } from "node:path"
import { parseArgs } from "node:util"
import { supermux } from "@supermux/core"
import { mcpServer, tool } from "@supermux/core/mcp"
import { z } from "zod"

const { values: { agent }, positionals: [prompt = "Deploy to staging with the deploy tool."] } = parseArgs({
  allowPositionals: true,
  options: { agent: { type: "string", default: "claude" } },
})
if (agent !== "claude" && agent !== "codex") {
  console.error('Usage: node deploy-tool.ts ["<prompt>"] [--agent claude|codex]')
  process.exit(1)
}

const ops = mcpServer({
  name: "ops", // the agent sees an MCP server of this name, and the tool as mcp__ops__deploy
  tools: {
    deploy: tool({
      description: "Deploy the current build",
      input: z.object({ environment: z.enum(["staging"]) }),
      run: async ({ environment }, ctx) => {
        console.log(`deploy called by ${ctx.agent} session ${ctx.sessionId}: ${environment}`)
        return `deployed to ${environment}`
      },
    }),
  },
})

// The core's state lives under the home, not in the temporary directory, where an agent could swap what it keeps
// for a link.
const dir = process.cwd()
const folder = `deploy-tool-${basename(dir)}-${createHash("sha1").update(dir).digest("hex").slice(0, 8)}`
const root = join(homedir(), ".supermux-recipes", folder, String(Date.now()))
await mkdir(root, { recursive: true, mode: 0o700 })
const stateDirectory = join(root, "state")
// Claude asks before it calls a tool: allowedTools lets it call this one, and what else it asks for is refused. Codex
// reads only and is never asked, and calls the host tool all the same.
const core = supermux({
  stateDirectory,
  agents: agent === "claude"
    ? { claude: { allowedTools: ["mcp__ops__deploy"] } }
    : { codex: { sandbox: "read-only", approvalPolicy: "never" } },
})

// One close, whoever asks first: the end of the turn or a signal.
let closing: Promise<void> | undefined
const close = () => (closing ??= core.close({ agents: "shutdown" })
  .finally(() => rm(root, { recursive: true, force: true })))
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => close().catch(console.error).finally(() => process.exit(128 + constants.signals[signal])))
}

// The agent's reply is printed. What it still asks (anything but this tool) is refused, and said: a request waits for
// ever, and the turn with it. `over` is the end of the turn: a receipt settles before the last events arrive.
const over = new Promise<void>(resolve => {
  core.subscribe(event => {
    if (event.type !== "session.event") return
    const body = event.event
    const session = core.sessions.live(event.sessionId)
    if (body.kind === "assistant-message" && !body.subagentId) console.log(body.text)
    else if (body.kind === "turn-complete") resolve()
    else if (body.kind === "permission-request") {
      console.error(`refused: ${body.toolCall.title}`)
      const reject = body.options.find(option => option.kind === "reject_once")
      if (reject) session?.requests.respond(body.requestId, { optionId: reject.id }).catch(console.error)
      else session?.interrupt({ pending: "discard" }).catch(console.error) // nothing to refuse with: end the turn
    } else if (body.kind === "user-question" && body.blocking) {
      console.error(`declined: ${body.questions.map(question => question.prompt).join(" ")}`)
      session?.requests.respond(body.requestId, { decline: true }).catch(console.error)
    }
  })
})

try {
  const session = await core.sessions.create({
    id: randomUUID(), agent, cwd: dir, context: { mcpServers: [ops] },
  })
  const { completed } = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "queue" })
  const completion = await completed
  if (completion.status !== "completed") {
    throw completion.status === "failed" ? completion.error : new Error(`the turn was ${completion.status}`)
  }
  await over
} catch (error) {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
} finally {
  await close()
}
