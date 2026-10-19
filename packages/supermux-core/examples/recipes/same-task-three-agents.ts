// One prompt, three agents, each in a git worktree and on a branch of its own: see what each one changes.
//
//   node same-task-three-agents.ts "<prompt>" [--agents claude,codex,grok] [--repo <path>]
//
// Each agent works on a branch race/<agent>-<time> made from the commit the repository is on, in a worktree under the
// temporary directory. When all are done, one line each says what the agent changed. The branches and worktrees stay,
// for you to compare, merge or remove.
import { execFile } from "node:child_process"
import { mkdtemp, rm } from "node:fs/promises"
import { constants, tmpdir } from "node:os"
import { join } from "node:path"
import { parseArgs, promisify } from "node:util"
import { supermux } from "@supermux/core"
import type { SupermuxAgents } from "@supermux/core"

const { values, positionals: [prompt] } = parseArgs({
  allowPositionals: true,
  options: { agents: { type: "string", default: "claude,codex,grok" }, repo: { type: "string", default: "." } },
})
const names = [...new Set(values.agents.split(",").map(name => name.trim()))]
if (!prompt || names.some(name => !["claude", "codex", "grok"].includes(name))) {
  console.error('Usage: node same-task-three-agents.ts "<prompt>" [--agents claude,codex,grok] [--repo <path>]')
  process.exit(1)
}

const exec = promisify(execFile)
/** Runs git in `cwd` with an argument list (no shell); resolves with what it printed. */
const git = async (cwd: string, ...args: string[]) => (await exec("git", ["-C", cwd, ...args])).stdout.trim()

const repo = await git(values.repo, "rev-parse", "--show-toplevel").catch(() => {
  console.error(`${values.repo} is not a git work tree`)
  process.exit(1)
})
const base = await git(repo, "rev-parse", "HEAD")
const stamp = Date.now()
const root = await mkdtemp(join(tmpdir(), "race-"))
const races = names.map(name => ({ name, branch: `race/${name}-${stamp}`, worktree: join(root, name) }))
for (const { branch, worktree } of races) await git(repo, "worktree", "add", "-b", branch, worktree, base)

// The agents work without asking. That is safe only because each one runs in a throwaway worktree, and then only for
// your checkout: a worktree is not a sandbox, and Grok's auto-approve lets it run any command as you. Do not give an
// agent these settings in a checkout you care about.
const agents: SupermuxAgents = {}
if (names.includes("claude")) agents.claude = { permissionMode: "acceptEdits" }
if (names.includes("codex")) agents.codex = { approvalPolicy: "never", sandbox: "workspace-write" }
if (names.includes("grok")) agents.grok = { policy: "auto-approve" }
const stateDirectory = join(root, "state")
const core = supermux({ stateDirectory, agents })

// One close, whoever asks first: the end of the race or a signal. The agents stop; the worktrees stay.
let closing: Promise<void> | undefined
const close = () => (closing ??= core.close({ agents: "shutdown" })
  .finally(() => rm(stateDirectory, { recursive: true, force: true })))
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => close().catch(console.error).finally(() => process.exit(128 + constants.signals[signal])))
}

// What an agent still asks is refused: a request waits for ever, and the turn with it. A permission request gets
// its reject_once option, a question a decline.
core.subscribe(event => {
  if (event.type !== "session.event") return
  const body = event.event
  const session = core.sessions.live(event.sessionId)
  if (body.kind === "permission-request") {
    const reject = body.options.find(option => option.kind === "reject_once")
    if (reject) session?.requests.respond(body.requestId, { optionId: reject.id }).catch(console.error)
    else session?.interrupt({ pending: "discard" }).catch(console.error) // nothing to refuse with: end the turn
  } else if (body.kind === "user-question" && body.blocking) {
    session?.requests.respond(body.requestId, { decline: true }).catch(console.error)
  }
})

try {
  const lines = await Promise.all(races.map(async ({ name, branch, worktree }) => {
    let outcome: string
    try {
      const session = await core.sessions.create({ id: `${name}-${stamp}`, agent: name, cwd: worktree })
      const { completed } = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "queue" })
      const completion = await completed
      if (completion.status !== "completed") {
        throw completion.status === "failed" ? completion.error : new Error(`the turn was ${completion.status}`)
      }
      await git(worktree, "add", "--all", "--intent-to-add") // a file the agent made shows in the diff too
      outcome = (await git(worktree, "diff", "--shortstat", base)) || "no changes"
    } catch (error) {
      outcome = `failed: ${error instanceof Error ? error.message : error}`
      process.exitCode = 1
    }
    return `${name}: ${outcome} (branch ${branch}, worktree ${worktree})`
  }))
  console.log(lines.join("\n"))
  console.error("The branches and worktrees stay, for you to look at. To remove an agent's:")
  console.error(`  git -C ${repo} worktree remove --force <worktree> && git -C ${repo} branch -D <branch>`)
} finally {
  await close()
}
