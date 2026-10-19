// A kanban board for coding agents on @supermux/core alone. Each card is a task for one agent (Claude Code, Codex,
// Grok, OpenCode or Cursor), which works on a branch of its own, in a git worktree of its own. A started card moves by
// itself: to "needs you" when its agent asks for a permission or an answer, to "review" when the agent is done.
//
//   node server.ts [--repo .] [--port 4322] [--state ~/.supermux-kanban/<repo>-<hash>] [--agents claude,codex] [--model ...]
//
// The page (index.html) posts JSON to /api/... and listens to one server-sent event stream, /api/events.
// Ctrl-C detaches from the agents instead of stopping them: the next start re-attaches to every card at work.
//
// The board drives git itself, so it must trust the repository: keep --repo outside the agents' writable roots (a
// /tmp checkout is refused). The repository's own git hooks do not run for the worktrees the board creates (git runs
// with core.hooksPath and core.fsmonitor off), so nothing an agent writes there runs as a command.
import { execFile } from "node:child_process"
import { createHash, randomBytes } from "node:crypto"
import {
  accessSync, appendFileSync, constants, existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync,
} from "node:fs"
import { createServer } from "node:http"
import type { IncomingMessage, ServerResponse } from "node:http"
import type { AddressInfo } from "node:net"
import { homedir, tmpdir } from "node:os"
import { basename, dirname, delimiter, isAbsolute, join, relative, resolve, sep } from "node:path"
import { parseArgs, promisify } from "node:util"
import { CoreError, supermux } from "@supermux/core"
import type { NormalizedBody, PendingRequest, Session } from "@supermux/core"

/** Ends the start, saying what is wrong in one line. */
function fail(message: string): never {
  console.error(message)
  process.exit(1)
}

/** What `make()` returns, or the start ends with its error's message. */
function orFail<T>(make: () => T): T {
  try { return make() } catch (error) { return fail(error instanceof Error ? error.message : String(error)) }
}

const { values: options } = orFail(() => parseArgs({
  options: {
    repo: { type: "string", default: "." },
    state: { type: "string" },
    port: { type: "string", default: "4322" },
    agents: { type: "string" },
    model: { type: "string", multiple: true },
  },
}))

// The agents asked for, or else each one whose CLI is on PATH.
const CLIS = { claude: "claude", codex: "codex", grok: "grok", opencode: "opencode", cursor: "cursor-agent" }
const onPath = (command: string) => (process.env.PATH ?? "").split(delimiter).some(dir => {
  try {
    accessSync(join(dir, command), constants.X_OK)
    return statSync(join(dir, command)).isFile()
  } catch {
    return false
  }
})
const names = options.agents?.split(",").map(name => name.trim()).filter(Boolean)
  ?? Object.entries(CLIS).filter(([, command]) => onPath(command)).map(([name]) => name)
if (!names.length) console.warn(`No agent CLI on PATH (${Object.values(CLIS).join(", ")}): pass --agents`)

// --model codex=gpt-5, once per agent: the model the agent is launched with.
const models = new Map<string, string>()
for (const pair of options.model ?? []) {
  const [, agent = "", model = ""] = /^(\w+)=(.+)$/.exec(pair) ?? []
  if (!names.includes(agent)) {
    fail(`--model takes <agent>=<model> for one of the agents (${names.join(", ")}), not ${pair}`)
  }
  models.set(agent, model)
}

const runFile = promisify(execFile)
// A diff larger than this is 413, not a maxBuffer failure: open the worktree to read it instead.
const DIFF_CAP = 5 * 1024 * 1024

/** Runs git as an argument list (no shell): its stdout, or what git said is wrong. A capped output over the cap is 413. */
async function runGit(args: string[], opts: { maxBuffer?: number; cap?: boolean; env?: NodeJS.ProcessEnv } = {}) {
  const maxBuffer = opts.maxBuffer ?? 64 * 1024 * 1024
  try {
    const { stdout } = await runFile("git", args, { timeout: 120_000, maxBuffer, ...(opts.env ? { env: opts.env } : {}) })
    return stdout
  } catch (error) {
    const { stderr, message, code } = error as { stderr?: string; message: string; code?: string }
    if (opts.cap && (code === "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" || /maxBuffer/i.test(message))) {
      throw new CoreError("too_large", `The diff is over ${Math.round(maxBuffer / (1024 * 1024))} MB: open the worktree to view it`)
    }
    throw new CoreError("git_failed", stderr?.trim() || message)
  }
}

// The repository the cards branch from: its top directory, whichever of its directories --repo names.
const repo = (await runGit(["-C", options.repo, "rev-parse", "--show-toplevel"]).catch((error: Error) =>
  fail(`${resolve(options.repo)} is not a git work tree: ${error.message.split("\n")[0]}`))).trim()
// The board drives git in the repository, so it must trust the repository's .git. A repository inside the agents'
// writable roots (a /tmp checkout, say) is refused: an agent could write <repo>/.git/hooks or config for git to run.
const writableRoots = [tmpdir(), process.env.TMPDIR, "/tmp", "/var/tmp"].filter((dir): dir is string => Boolean(dir)).map(dir => resolve(dir))
if (writableRoots.some(root => repo === root || repo.startsWith(root + sep))) {
  fail(`${repo} is inside a temporary directory agents can write to: keep the repo outside the agents' writable roots`)
}
// The repository's real git directory, recorded once before any agent runs. Every main-repo git command pins it (and
// the repository's work tree) and runs with core.hooksPath/core.fsmonitor off, so a .git file an agent plants in its
// worktree cannot redirect them and nothing an agent writes under .git/hooks or config runs through them.
const repoGitDir = (await runGit(["-C", repo, "rev-parse", "--absolute-git-dir"])).trim()
function repoGit(...args: string[]) {
  return runGit(["-C", repo, "--git-dir", repoGitDir, "--work-tree", repo,
    "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null", ...args])
}

// The board (board.json), the core's records (core/: sessions, keepers) and the cards' worktrees (worktrees/), side by
// side: the core refuses an agent folder inside its own directory. The default is outside the repository, so
// `git clean -fdx` cannot delete the board and the session records; it is keyed by the repository's path so two repos
// never collide.
const repoKey = `${basename(repo)}-${createHash("sha1").update(repo).digest("hex").slice(0, 8)}`
const stateDir = resolve(options.state ?? join(homedir(), ".supermux-kanban", repoKey))
const worktrees = join(stateDir, "worktrees")
mkdirSync(worktrees, { recursive: true, mode: 0o700 })
// Only an explicit --state inside the repository is told to git in .git/info/exclude (which stays on this machine; a
// .gitignore would be committed); the default is outside and needs none.
const inside = relative(repo, stateDir)
if (options.state && inside && !inside.startsWith("..") && !isAbsolute(inside)) {
  const exclude = resolve(repo, (await repoGit("rev-parse", "--git-path", "info/exclude")).trim())
  const entry = `/${inside.split(sep).join("/")}/`
  const text = existsSync(exclude) ? readFileSync(exclude, "utf8") : ""
  if (!text.split("\n").includes(entry)) {
    mkdirSync(dirname(exclude), { recursive: true })
    appendFileSync(exclude, `${text && !text.endsWith("\n") ? "\n" : ""}${entry}\n`)
  }
}

// Every agent with supermux()'s defaults (its CLI's own asking mode: permission prompts come to the board).
const core = orFail(() => supermux({
  stateDirectory: join(stateDir, "core"),
  agents: Object.fromEntries(names.map(name => [name, { model: models.get(name) }])),
}))

type Column = "backlog" | "running" | "needs-you" | "review" | "done"

/** A task for one agent. Started, it has a branch and a worktree of its own, and a session with the card's id. */
type Card = {
  id: string; title: string; prompt: string; agent: string; column: Column
  branch?: string; worktree?: string
  base?: string // the commit the branch starts from
  gitDir?: string; commonDir?: string // the worktree's real git directory, recorded at creation (never its .git file)
  diffStat?: string // what the agent changed since base, as `git diff --shortstat` says it: at each review, and at Done
  commits?: number // once done: how many commits agent/<id> has over base (0: nothing to merge)
  error?: string // why the agent, or its last turn, failed
  request?: PendingRequest["body"] // the latest permission prompt or question the agent waits on
  activity?: string // what the agent did last, in a line
}

const boardFile = join(stateDir, "board.json")
const cards: Card[] = existsSync(boardFile) ? JSON.parse(readFileSync(boardFile, "utf8")).cards : []
const streams = new Set<ServerResponse>()
// The cards with a lifecycle action in flight (Start, follow-up, Done, Discard): a second one on the same card is 409,
// so two Starts cannot both send the prompt and a Discard cannot race a Start into an orphaned branch or agent.
const busy = new Set<string>()
// Whether this server is detaching (Ctrl-C): from then on the board stays as it is, for the next start.
let detaching = false

/** Runs `work` while the card is marked busy; a concurrent action on the same card is 409 until it finishes. */
async function withBusy<T>(id: string, work: () => Promise<T>): Promise<T> {
  if (busy.has(id)) throw new CoreError("session_busy", `Card ${id} is busy with another action`)
  busy.add(id)
  try {
    return await work()
  } finally {
    busy.delete(id)
  }
}

/** Saves the board. Written aside and renamed over: a crash mid-write cannot leave a file that stops the next start. */
function save() {
  writeFileSync(`${boardFile}.tmp`, JSON.stringify({ cards }))
  renameSync(`${boardFile}.tmp`, boardFile)
}

/** The board as a page gets it: the repository the cards branch from (its name, and its full path), and the cards. */
const board = () => ({ repo: { name: basename(repo), path: repo }, cards })
type Message = ({ type: "board" } & ReturnType<typeof board>) | { type: "activity"; cardId: string; text: string }

/** What /api/events sends: the whole board after every change, and each card's status line as it comes. */
function broadcast(message: Message) {
  for (const stream of streams) stream.write(`data: ${JSON.stringify(message)}\n\n`)
}

/** After every change: saves the board and sends it to every page. */
function publish() {
  save()
  broadcast({ type: "board", ...board() })
}

function update(card: Card, change: Partial<Card>) {
  Object.assign(card, change)
  publish()
}

/**
 * The flags that run a git command against the card's worktree through its recorded git directory, never the `.git`
 * file in the worktree (which the agent can replace to point at a config of its own). `core.fsmonitor`/`core.hooksPath`
 * are forced off so no path the agent writes runs as a command.
 */
function worktreePins(card: Card) {
  const gitDir = card.gitDir ?? join(repoGitDir, "worktrees", basename(card.worktree!))
  return {
    args: ["--git-dir", gitDir, "--work-tree", card.worktree!, "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null"],
    env: { ...process.env, GIT_COMMON_DIR: card.commonDir ?? repoGitDir },
  }
}

/** The paths a tree records as submodules (gitlinks). `ls-tree` reads the tree only; it runs nothing. */
async function gitlinks(tree: string, pins: string[], env: NodeJS.ProcessEnv) {
  const out = await runGit([...pins, "ls-tree", "-r", "-z", "--full-tree", tree], { env })
  return out.split("\0").filter(entry => entry.startsWith("160000 ")).map(entry => entry.slice(entry.indexOf("\t") + 1))
}

/** The pathspec that leaves `path` out of a git command, taken as it is (a * in a name is no glob). */
const leaveOut = (path: string) => `:(top,literal,exclude)${path}`

/** What the agent changed since the card's base, new files included (`git add --intent-to-add` makes them count). */
async function diffOf(card: Card, ...args: string[]) {
  if (!card.worktree || !card.base) throw new CoreError("not_started", `Card ${card.id} has not started`)
  const { args: pins, env } = worktreePins(card)
  // A submodule the base records is excluded from `add` and `diff`: a repository the agent fills it in with must never
  // have its own config (a clean filter, say) run. `--ignore-submodules=all` keeps `diff` out of nested repos too.
  const exclude = (await gitlinks(card.base, pins, env)).map(leaveOut)
  await runGit([...pins, "add", "--all", "--intent-to-add", "--", ":(top)", ...exclude], { env })
  // core.quotePath off: a name that is not ASCII shows as it is, not octal-escaped in quotes.
  return runGit([...pins, "-c", "core.quotePath=false", "diff", "--no-color", "--no-ext-diff", "--no-textconv",
    "--ignore-submodules=all", ...args, card.base, "--", ":(top)", ...exclude],
    { env, maxBuffer: DIFF_CAP, cap: true })
}

/**
 * Done: what the agent changed (agents do not commit by default) becomes one commit on the card's branch, so
 * `git merge agent/<id>` merges it. On that branch or nowhere: a worktree the agent moved off it is a 409. The message
 * is the first line of the card's title; the commit is unsigned, runs no hooks, and has the repository's identity (the
 * board's where the repository has none). Repositories the agent made inside its worktree stay out: as gitlinks they
 * would point at commits nobody else has. Gives the commit (null when nothing changed), what was left out, and what the
 * branch now holds over its base: its commits, and their stat.
 */
type Committed = { commit: string | null; skipped: string[]; commits?: number; diffStat?: string }
const IDENTITY = { "user.name": "supermux kanban", "user.email": "kanban@supermux.invalid" }
async function commitWork(card: Card): Promise<Committed> {
  if (!card.worktree || !card.base) return { commit: null, skipped: [] }
  const { args: pins, env } = worktreePins(card)
  const git = (...args: string[]) => runGit([...pins, ...args], { env })
  const branch = `agent/${card.id}`, ref = `refs/heads/${branch}`
  const head = await git("symbolic-ref", "-q", "HEAD").then(out => out.trim(), () => "")
  if (head !== ref) {
    const where = head ? `to ${head.replace(/^refs\/heads\//, "")}` : "HEAD is detached"
    throw new CoreError("off_branch",
      `The agent moved its worktree off ${branch} (${where}): switch it back to ${branch}, then Done again`)
  }
  // A repository git has not seen is "path/" among the untracked; one a diff has seen is a gitlink no commit records,
  // added to the index with --intent-to-add (which the index forgets here). Both stay out.
  const known = new Set(await gitlinks("HEAD", pins, env))
  const untracked = (await git("ls-files", "-z", "--others", "--exclude-standard")).split("\0")
    .filter(path => path.endsWith("/")).map(path => path.slice(0, -1))
  const staged = (await git("ls-files", "-z", "--stage")).split("\0").filter(entry => entry.startsWith("160000 "))
    .map(entry => entry.slice(entry.indexOf("\t") + 1)).filter(path => !known.has(path))
  if (staged.length) await git("update-index", "--force-remove", "--", ...staged)
  const skipped = [...new Set([...untracked, ...staged])].sort()
  await git("add", "--all", "--", ":(top)", ...[...await gitlinks(card.base, pins, env), ...skipped].map(leaveOut))
  let commit: string | null = null
  if ((await git("diff", "--cached", "--name-only", "--ignore-submodules=all")).trim()) {
    const identity: string[] = []
    for (const [key, fallback] of Object.entries(IDENTITY)) {
      const set = await git("config", "--get", key).then(value => value.trim(), () => "")
      if (!set) identity.push("-c", `${key}=${fallback}`)
    }
    // One line: a title made through the API could carry a second, a trailer such as Signed-off-by.
    const message = card.title.split(/[\r\n]/)[0] ?? card.title
    await git(...identity, "-c", "commit.gpgSign=false", "commit", "--quiet", "--no-verify", "-m", message)
    commit = (await git("rev-parse", "HEAD")).trim()
  }
  const commits = Number((await git("rev-list", "--count", `${card.base}..${ref}`)).trim())
  const diffStat = await git("diff", "--shortstat", "--no-ext-diff", "--no-textconv", "--ignore-submodules=all",
    card.base, ref)
  return { commit, skipped, commits, diffStat: diffStat.trim() }
}

/**
 * A started card follows its session: it needs you while the agent waits on a prompt, runs while the agent works, goes
 * to review (with the diff stat) once the agent is idle, and back to the backlog if the agent fails.
 */
function sync(card: Card) {
  const session = core.sessions.live(card.id)
  const state = session?.snapshot().state
  // A card in the backlog or done moves only when you move it; Done and Discard say where a closing one goes.
  if (detaching || !session || state === "closing" || card.column === "backlog" || card.column === "done") return
  const request = session.requests.list().at(-1)?.body
  // A failed agent sends the card back to the backlog to start over — but one already in review has done its work, so it
  // stays there with its error (a follow-up resumes it): a restart must not resend the original prompt on finished work.
  const column = state === "failed" ? (card.column === "review" ? "review" : "backlog")
    : request ? "needs-you" : state === "idle" ? "review" : "running"
  if (column === card.column && request?.requestId === card.request?.requestId) return
  update(card, { column, request })
  if (column !== "review") return
  diffOf(card, "--shortstat").then(stat => update(card, { diffStat: stat.trim() }),
    (error: Error) => console.error(`Card ${card.id} has no diff stat: ${error.message}`))
}

/** A status line for an agent event that shows what the agent does: a tool it starts, or the first line it says. */
function statusOf(body: NormalizedBody) {
  const text = body.kind === "tool-call" && body.phase === "started" ? body.title ?? body.tool
    : body.kind === "assistant-message" ? body.text : ""
  const [line = ""] = text.trim().split("\n")
  return [...line].slice(0, 120).join("") // by code point: an emoji is not cut in half
}

// One observer sees every session: a session's id is its card's.
core.subscribe(event => {
  const card = cards.find(card => card.id === event.sessionId)
  if (!card || detaching) return
  const text = event.type === "session.event" ? statusOf(event.event) : ""
  if (text) {
    card.activity = text
    save()
    broadcast({ type: "activity", cardId: card.id, text })
  }
  // The agent failed, or one of its turns did.
  const error = event.type === "session.failed" ? event.error
    : event.type === "message.completed" && event.result.status === "failed" ? event.result.error : undefined
  if (error) update(card, { error: error.message })
  sync(card)
})

/** Sends `text` to the card's agent: the card is at work from now on, until its session says otherwise. */
async function run(card: Card, session: Session, text: string) {
  update(card, { column: "running", error: undefined })
  try {
    await session.send({ content: [{ type: "text", text }], whenBusy: "queue" })
  } finally {
    sync(card)
  }
}

function cardOf(id: string) {
  const card = cards.find(card => card.id === id)
  if (!card) throw new CoreError("not_found", `There is no card ${id}`)
  return card
}

/** The card's session if it is open: its requests and turns belong to a running agent. */
function requireOpen(id: string) {
  const session = core.sessions.live(cardOf(id).id)
  if (!session) throw new CoreError("session_closed", `The agent of card ${id} is not running`)
  return session
}

function textOf(body: Record<string, unknown>, field: string) {
  const value = body[field]
  if (typeof value !== "string" || !value.trim()) throw new CoreError("invalid_input", `${field} is required`)
  return value
}

/** Whether `path` is a worktree git already knows about (a partial Start may have registered one with no card field). */
async function worktreeRegistered(path: string) {
  const target = resolve(path)
  return (await repoGit("worktree", "list", "--porcelain")).split("\n")
    .some(line => line.startsWith("worktree ") && resolve(line.slice("worktree ".length)) === target)
}

/** A route's handler gets the path's parameters and the JSON body of a POST. */
type Handler = (params: [string, string], body: any) => unknown

const routes: Array<[method: string, path: RegExp, handle: Handler]> = [
  ["GET", /^\/api\/agents$/, () => names],
  ["GET", /^\/api\/board$/, board],
  ["POST", /^\/api\/cards$/, (_, body) => {
    const [title, prompt] = [textOf(body, "title").trim(), textOf(body, "prompt")]
    if (!names.includes(body.agent)) throw new CoreError("unknown_agent", `agent is one of ${names.join(", ")}`)
    const card: Card = { id: randomBytes(4).toString("hex"), title, prompt, agent: body.agent, column: "backlog" }
    cards.push(card)
    publish()
    return { id: card.id }
  }],
  ["POST", /^\/api\/cards\/([0-9a-f]{8})\/start$/, ([id]) => withBusy(id, async () => {
    const card = cardOf(id)
    if (card.column !== "backlog") throw new CoreError("session_busy", `Card ${id} is not in the backlog`)
    let { worktree } = card
    if (!worktree) {
      // A branch and worktree of its own. A partial earlier Start may have left the branch (its worktree add failed on a
      // hook) or an unregistered directory: reuse the branch, start the worktree from it, and clear a stray directory.
      const branch = `agent/${id}`
      worktree = join(worktrees, id)
      const hasBranch = Boolean((await repoGit("branch", "--list", "--", branch)).trim())
      const base = (await repoGit("rev-parse", hasBranch ? branch : "HEAD")).trim()
      if (!(await worktreeRegistered(worktree))) {
        if (existsSync(worktree)) rmSync(worktree, { recursive: true, force: true })
        await repoGit("worktree", "add", ...(hasBranch ? [] : ["-b", branch]), "--", worktree, hasBranch ? branch : base)
      }
      // The worktree's real git directory, read from the .git file git just wrote, before any agent can replace it.
      const gitDir = (await runGit(["-C", worktree, "rev-parse", "--absolute-git-dir"])).trim()
      const commonDir = (await runGit(["-C", worktree, "rev-parse", "--path-format=absolute", "--git-common-dir"])).trim()
      update(card, { base, worktree, branch, gitDir, commonDir })
    }
    // A card back in the backlog (its agent failed) goes on where it was: same worktree, same conversation.
    const session = await core.sessions.get(id)
      ? await core.sessions.resume(id)
      : await core.sessions.create({ id, agent: card.agent, cwd: worktree })
    await run(card, session, card.prompt)
    return { ok: true }
  })],
  ["POST", /^\/api\/cards\/([0-9a-f]{8})\/followup$/, ([id], body) => withBusy(id, async () => {
    const card = cardOf(id)
    const text = textOf(body, "text")
    // An open session comes back as it is; another (done, failed, from before a restart) is opened again.
    const session = await core.sessions.resume(id)
    // A Stop pauses the queue; the next message resumes it, once the stopped turn has ended.
    const { state, paused } = session.snapshot()
    if (state === "interrupting") {
      throw new CoreError("session_busy", "The agent is still stopping its last turn: send again in a moment")
    }
    if (paused) session.pending.continue()
    await run(card, session, text)
    return { ok: true }
  })],
  ["POST", /^\/api\/cards\/([0-9a-f]{8})\/requests\/([^/]+)$/, async ([id, requestId], answer) => {
    await requireOpen(id).requests.respond(requestId, answer)
    sync(cardOf(id))
    return { ok: true }
  }],
  // Stop ends the turn and cancels the messages queued behind it.
  ["POST", /^\/api\/cards\/([0-9a-f]{8})\/interrupt$/, ([id]) => requireOpen(id).interrupt({ pending: "discard" })],
  ["GET", /^\/api\/cards\/([0-9a-f]{8})\/diff$/, ([id]) => diffOf(cardOf(id))],
  // Done stops the agent first (one left running behind its keeper after a restart too), so nothing it does afterwards
  // slips into the commit; then it commits the agent's work on its branch. The branch and the worktree stay. A commit
  // refused or failed leaves the card in review, its agent stopped (a follow-up starts it again).
  ["POST", /^\/api\/cards\/([0-9a-f]{8})\/done$/, ([id]) => withBusy(id, async () => {
    const card = cardOf(id)
    await core.sessions.close(id, { mode: "shutdown" })
    const { commit, skipped, commits, diffStat = card.diffStat } = await commitWork(card)
    update(card, { column: "done", request: undefined, commits, diffStat })
    return { ok: true, commit, skipped }
  })],
  // Discard stops the agent (a detached one too), forgets the session (keeper directory included), and deletes the
  // worktree, the branch and the card. Names come from the id, not only the card, so a partial Start leaves nothing;
  // what is gone already, or a .git file the agent tampered with, is no error (the directory is removed outright).
  ["POST", /^\/api\/cards\/([0-9a-f]{8})\/discard$/, ([id]) => withBusy(id, async () => {
    const card = cardOf(id)
    await core.sessions.close(id, { mode: "shutdown" })
    await core.sessions.forget(id)
    const branch = card.branch ?? `agent/${id}`
    const worktree = card.worktree ?? join(worktrees, id)
    if (await worktreeRegistered(worktree)) await repoGit("worktree", "remove", "--force", "--", worktree).catch(() => {})
    if (existsSync(worktree)) rmSync(worktree, { recursive: true, force: true })
    await repoGit("worktree", "prune")
    if ((await repoGit("branch", "--list", "--", branch)).trim()) await repoGit("branch", "-D", "--", branch).catch(() => {})
    cards.splice(cards.indexOf(card), 1)
    publish()
    return { ok: true }
  })],
]

const MAX_BODY = 1024 * 1024

async function readBody(req: IncomingMessage) {
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size <= MAX_BODY) chunks.push(chunk) // Past the cap the rest is read and dropped, so the 413 gets through.
  }
  if (size > MAX_BODY) throw new CoreError("too_large", "The body is over 1 MB")
  try {
    // Decoded once: a character split between two chunks stays whole.
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")
    if (body && typeof body === "object") return body
  } catch {}
  throw new CoreError("invalid_input", "The body must be a JSON object")
}

/** Sends `body`: a diff as text, anything else as JSON. */
function reply(res: ServerResponse, status: number, body: unknown) {
  if (typeof body === "string") res.writeHead(status, { "content-type": "text/plain; charset=utf-8" }).end(body)
  else res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body))
}

// Error codes, the core's and this file's own, as HTTP statuses. Anything else is a 500.
const STATUS: Record<string, number> = {
  invalid_input: 400, invalid_session_id: 400, invalid_workdir: 400, unknown_agent: 400,
  forbidden: 403,
  not_found: 404, session_not_found: 404, request_not_found: 404,
  session_busy: 409, session_failed: 409, session_closed: 409, session_exists: 409, not_started: 409, git_failed: 409,
  off_branch: 409,
  too_large: 413, queue_full: 429, unsupported_operation: 501, core_closed: 503,
}

/** True for a request from this server's own page: not from another site, nor through a name rebound to 127.0.0.1. */
function isOwnPage(req: IncomingMessage) {
  const own = [`127.0.0.1:${req.socket.localPort}`, `localhost:${req.socket.localPort}`]
  const { host = "", origin } = req.headers
  return own.includes(host) && (origin === undefined || own.some(name => origin === `http://${name}`))
}

const server = createServer(async (req, res) => {
  // No other site may show the page in a frame, where a click could be tricked onto "Allow".
  res.setHeader("x-frame-options", "DENY").setHeader("content-security-policy", "frame-ancestors 'none'")
  const url = new URL(req.url ?? "/", "http://127.0.0.1")
  try {
    if (!isOwnPage(req)) throw new CoreError("forbidden", "Requests must come from this server's own page")
    if (req.method === "GET" && url.pathname === "/") {
      const page = readFileSync(new URL("index.html", import.meta.url))
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" }).end(page)
    } else if (req.method === "GET" && url.pathname === "/api/events") {
      res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" }).flushHeaders()
      // A new page starts from the whole board, then follows it by the changes that come after.
      res.write(`data: ${JSON.stringify({ type: "board", ...board() })}\n\n`)
      streams.add(res)
      res.on("close", () => streams.delete(res))
    } else {
      const route = routes.find(([method, path]) => method === req.method && path.test(url.pathname))
      if (!route) throw new CoreError("not_found", `No route for ${req.method} ${url.pathname}`)
      const [, path, handle] = route
      const [first = "", second = ""] = path.exec(url.pathname)!.slice(1).map(decodeURIComponent)
      const body = req.method === "POST" ? await readBody(req) : {}
      reply(res, 200, await handle([first, second], body))
    }
  } catch (error) {
    // A broken % escape in the path is the caller's too.
    const code = error instanceof CoreError ? error.code : error instanceof URIError ? "invalid_input" : "internal"
    reply(res, STATUS[code] ?? 500, { error: error instanceof Error ? error.message : String(error), code })
  }
})

// A comment every 15 s keeps an idle event stream open through proxies and timeouts.
setInterval(() => streams.forEach(stream => stream.write(": ping\n\n")), 15_000).unref()

// The core claims its state directory on first use: a second server on the same one stops here.
await core.sessions.list().catch(error =>
  fail(error.code === "state_locked" ? `${stateDir} is in use by another kanban server` : String(error)))
// The cards at work re-attach to their agents, in the background. One whose agent finished meanwhile goes to review;
// one whose agent cannot come back goes to the backlog, saying why.
for (const card of cards) {
  if (card.column !== "running" && card.column !== "needs-you") continue
  core.sessions.resume(card.id).then(() => sync(card), (error: Error) => {
    if (!detaching) update(card, { column: "backlog", error: error.message, request: undefined })
  })
}

server.on("error", error => fail(String(error)))
orFail(() => server.listen(Number(options.port), "127.0.0.1", () => {
  console.log(`listening on http://127.0.0.1:${(server.address() as AddressInfo).port}`)
}))

// Ctrl-C detaches: the agents keep running behind their keepers, and the next start re-attaches to them.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, () => {
    detaching = true
    core.close({ agents: "detach" }).then(() => process.exit(0), error => fail(String(error)))
  })
}
