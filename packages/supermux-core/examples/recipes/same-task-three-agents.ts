// One prompt, several agents, each in a git worktree and on a branch of its own: see what each one changes.
//
//   node same-task-three-agents.ts "<prompt>" [--agents claude,codex,grok] [--repo <path>]
//
// Each agent works on a branch race/<agent>-<time> made from the commit the repository is on, in a worktree under
// ~/.supermux-recipes. When all are done, what each one changed is committed on its branch (the branch keeps it when
// the worktree goes) and a line says how much. The agents work without asking: read the warning in README.md before
// you run it. --agents is claude,codex unless you say otherwise: Grok, on auto-approve, runs only when asked.
import { execFile } from "node:child_process"
import { createHash } from "node:crypto"
import { lstat, mkdir, realpath, rm } from "node:fs/promises"
import { constants, homedir, tmpdir } from "node:os"
import { basename, join, sep } from "node:path"
import { parseArgs, promisify } from "node:util"
import { supermux } from "@supermux/core"
import type { SupermuxAgents } from "@supermux/core"

type Race = { name: string; branch: string; worktree: string; gitDir: string; commonDir: string }

const { values, positionals: [prompt] } = parseArgs({
  allowPositionals: true,
  options: { agents: { type: "string", default: "claude,codex" }, repo: { type: "string", default: "." } },
})
const names = [...new Set(values.agents.split(",").map(name => name.trim()))]
if (!prompt || names.some(name => !["claude", "codex", "grok"].includes(name))) {
  console.error('Usage: node same-task-three-agents.ts "<prompt>" [--agents claude,codex,grok] [--repo <path>]')
  process.exit(1)
}
if (names.includes("grok")) {
  console.error("grok runs on auto-approve: it can run any command as you, anywhere on this machine,")
  console.error("not only in its worktree.")
}

const exec = promisify(execFile)
/** Runs git with an argument list (no shell); resolves with what it printed. */
const git = async (args: string[], env: NodeJS.ProcessEnv = {}) =>
  (await exec("git", args, { env: { ...process.env, ...env }, maxBuffer: 64 * 1024 * 1024 })).stdout.trim()

// The agents write into their worktrees, so git must not trust what it finds there: a `.git` an agent plants makes
// git run its commands (as the fsmonitor hook, a clean filter, a hook). So every command names the git directory it
// works on, recorded before any agent ran, and runs with fsmonitor and hooks off.
const OFF = ["-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null"]
const repo = await git(["-C", values.repo, "rev-parse", "--show-toplevel"]).catch(() => {
  console.error(`${values.repo} is not a git work tree`)
  process.exit(1)
})
// The recipe runs git in the repository, so it must trust the repository's .git. The agents can write in the temporary
// directories (Codex can, by default), and a .git they changed would have git run what they wrote: a repository there
// is refused.
const temporary = [tmpdir(), process.env.TMPDIR ?? "", "/tmp", "/var/tmp"]
const roots = await Promise.all(temporary.map(dir => realpath(dir).catch(() => "")))
if (roots.some(dir => dir && (repo === dir || repo.startsWith(dir + sep)))) {
  console.error(`${repo} is inside a temporary directory the agents can write to: keep the repository outside it`)
  process.exit(1)
}
const repoGitDir = await git(["-C", repo, "rev-parse", "--absolute-git-dir"])
const repoGit = (...args: string[]) => git(["-C", repo, "--git-dir", repoGitDir, "--work-tree", repo, ...OFF, ...args])
/** Git on an agent's worktree, through the git directories recorded for it and never the `.git` there. */
const inWorktree = ({ worktree, gitDir, commonDir }: Race, ...args: string[]) =>
  git(["--git-dir", gitDir, "--work-tree", worktree, ...OFF, ...args], { GIT_COMMON_DIR: commonDir })

const base = await repoGit("rev-parse", "HEAD")
// The submodules the base records stay as they are: git asks a repository an agent puts in one's place for its
// status, and that runs the filters of its config.
const submodules = (await repoGit("ls-tree", "-r", "-z", "--full-tree", base)).split("\0")
  .filter(entry => entry.startsWith("160000 "))
  .map(entry => `:(top,exclude,literal)${entry.slice(entry.indexOf("\t") + 1)}`)

// What the recipe makes lives under the home, never in the temporary directory: agents can write there (Codex can, by
// default), and what they swap there for a link (a keeper's journal, a worktree) would have the recipe write or read
// what the link names. The root is this run's own, in a folder per recipe and repository, for you alone.
const stamp = Date.now()
const folder = `same-task-three-agents-${basename(repo)}-${createHash("sha1").update(repo).digest("hex").slice(0, 8)}`
const root = join(homedir(), ".supermux-recipes", folder, String(stamp))
await mkdir(root, { recursive: true, mode: 0o700 })
const realRoot = await realpath(root)
const races: Race[] = []
for (const name of names) {
  const branch = `race/${name}-${stamp}`
  const worktree = join(root, name)
  await repoGit("worktree", "add", "-b", branch, "--", worktree, base)
  // The worktree's own git directories, read from the .git file git has just written there.
  const ids = ["-C", worktree, ...OFF, "rev-parse", "--path-format=absolute", "--absolute-git-dir", "--git-common-dir"]
  const [gitDir = "", commonDir = ""] = (await git(ids)).split("\n")
  races.push({ name, branch, worktree, gitDir, commonDir })
}

// The agents work without asking, so nobody has to sit here. A worktree keeps their edits off your checkout and is no
// sandbox: Claude edits files without asking (what else it asks for is refused); Codex runs commands in its sandbox,
// which reads what you can read and writes in its worktree and the temporary directories; Grok runs any command as
// you, anywhere. Do not give an agent these settings in a checkout you care about.
const agents: SupermuxAgents = {}
if (names.includes("claude")) agents.claude = { permissionMode: "acceptEdits" }
if (names.includes("codex")) agents.codex = { approvalPolicy: "never", sandbox: "workspace-write" }
if (names.includes("grok")) agents.grok = { policy: "auto-approve" }
const stateDirectory = join(root, "state")
const core = supermux({ stateDirectory, agents })

// One close, whoever asks first: the end of the turns or a signal. The agents stop; the worktrees stay. The state is
// removed once the core has closed, and not before: when it could not (a keeper may still run), nothing is committed or
// removed, and a note says where everything is and what to do.
let closing: Promise<void> | undefined
const close = () => (closing ??= core.close({ agents: "shutdown" }).then(
  () => rm(stateDirectory, { recursive: true, force: true }),
  error => {
    console.error("The core did not close, so an agent or its keeper may still be running: nothing is committed, nothing is removed.")
    console.error(`The worktrees and the state are left in ${root}`)
    console.error(`To stop the agents, kill the processes named by keeperPid and agentPid in ${stateDirectory}/keepers/*/status.json; their work is in the worktrees, uncommitted.`)
    throw error
  },
))
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => close().catch(console.error).finally(() => process.exit(128 + constants.signals[signal])))
}

// What an agent still asks is refused, and said: a request waits for ever, and the turn with it. A permission
// request gets its reject_once option, a question a decline.
core.subscribe(event => {
  if (event.type !== "session.event") return
  const body = event.event
  const session = core.sessions.live(event.sessionId)
  if (body.kind === "permission-request") {
    console.error(`refused: ${body.toolCall.title} (${body.agent})`)
    const reject = body.options.find(option => option.kind === "reject_once")
    if (reject) session?.requests.respond(body.requestId, { optionId: reject.id }).catch(console.error)
    else session?.interrupt({ pending: "discard" }).catch(console.error) // nothing to refuse with: end the turn
  } else if (body.kind === "user-question" && body.blocking) {
    console.error(`declined: ${body.questions.map(question => question.prompt).join(" ")} (${body.agent})`)
    session?.requests.respond(body.requestId, { decline: true }).catch(console.error)
  }
})

/** Commits what the agent left in its worktree on its branch; says what that is (git diff --shortstat). */
async function keep(race: Race) {
  // Before git reads the worktree it must still be the directory made for the agent, not a link in its place.
  const info = await lstat(race.worktree)
  if (!info.isDirectory() || (await realpath(race.worktree)) !== join(realRoot, race.name)) {
    throw new Error(`${race.worktree} is not the directory made for ${race.name}: left alone`)
  }
  // An agent that ran git may have moved HEAD: the commit goes on the branch whatever.
  await inWorktree(race, "symbolic-ref", "HEAD", `refs/heads/${race.branch}`)
  await inWorktree(race, "add", "--all", "--", ":(top)", ...submodules)
  const diff = ["diff", "--no-ext-diff", "--no-textconv", "--ignore-submodules=all", "--shortstat"]
  if (await inWorktree(race, ...diff, "--cached", "HEAD", "--")) {
    // The author is the one git has; a name or an email it lacks is the recipe's.
    const author: string[] = []
    const fallbacks = { "user.name": "supermux recipes", "user.email": "recipes@supermux.invalid" }
    for (const [key, fallback] of Object.entries(fallbacks)) {
      if (!(await inWorktree(race, "config", "--get", key).catch(() => ""))) author.push("-c", `${key}=${fallback}`)
    }
    const message = `${race.name}: ${prompt}`
    await inWorktree(race, ...author, "-c", "commit.gpgsign=false", "commit", "--no-verify", "-q", "-m", message)
  }
  return (await inWorktree(race, ...diff, base, "HEAD", "--")) || "no changes"
}

try {
  const problems = await Promise.all(races.map(async ({ name, worktree }) => {
    try {
      const session = await core.sessions.create({ id: name, agent: name, cwd: worktree })
      const { completed } = await session.send({ content: [{ type: "text", text: prompt }], whenBusy: "queue" })
      const completion = await completed
      if (completion.status === "completed") return ""
      return completion.status === "failed" ? `failed: ${completion.error.message}` : completion.status
    } catch (error) {
      return `failed: ${error instanceof Error ? error.message : error}`
    }
  }))
  // The agents stop before git reads what they wrote: nothing of theirs runs while it does.
  await close()
  const lines = await Promise.all(races.map(async (race, index) => {
    let changes: string
    try {
      changes = await keep(race)
    } catch (error) {
      changes = `not kept: ${error instanceof Error ? error.message : error}`
      process.exitCode = 1
    }
    if (problems[index]) process.exitCode = 1
    const outcome = [problems[index], changes].filter(Boolean).join("; ")
    return `${race.name}: ${outcome} (branch ${race.branch}, worktree ${race.worktree})`
  }))
  console.log(lines.join("\n"))
  const at = `git -C ${JSON.stringify(repo)}`
  console.error("Each agent's changes are committed on its branch. To take one:")
  for (const { branch } of races) console.error(`  ${at} merge ${branch}`)
  console.error("To remove the worktrees, then the branches, then the folder of this run:")
  for (const { worktree } of races) console.error(`  ${at} worktree remove --force ${JSON.stringify(worktree)}`)
  console.error(`  ${at} branch -D ${races.map(race => race.branch).join(" ")}`)
  console.error(`  rmdir ${JSON.stringify(root)}`)
} finally {
  await close()
}
