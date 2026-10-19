// A kanban board for coding agents on @supermux/core alone. Each card is a task for one agent (Claude Code, Codex,
// Grok, OpenCode or Cursor), which works on a branch of its own, in a git worktree of its own. A started card moves by
// itself: to "needs you" when its agent asks for a permission or an answer, to "review" when the agent is done.
//
//   node server.ts [--repo .] [--port 4322] [--state <repo>/.supermux-kanban] [--agents claude,codex] [--model claude=opus ...]
//
// The page (index.html) posts JSON to /api/... and listens to one server-sent event stream, /api/events.
// Ctrl-C detaches from the agents instead of stopping them: the next start re-attaches to every card at work.
import { execFile } from "node:child_process"
import { randomBytes } from "node:crypto"
import {
  accessSync, appendFileSync, constants, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync,
} from "node:fs"
import { createServer } from "node:http"
import type { IncomingMessage, ServerResponse } from "node:http"
import type { AddressInfo } from "node:net"
import { delimiter, dirname, isAbsolute, join, relative, resolve, sep } from "node:path"
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

/** Runs git with `args` in directory `cwd`, as an argument list (no shell): its output, or what git said is wrong. */
async function git(cwd: string, ...args: string[]) {
  try {
    const { stdout } = await runFile("git", ["-C", cwd, ...args], { timeout: 120_000, maxBuffer: 64 * 1024 * 1024 })
    return stdout
  } catch (error) {
    const { stderr, message } = error as { stderr?: string; message: string }
    throw new CoreError("git_failed", stderr?.trim() || message)
  }
}

// The repository the cards branch from: its top directory, whichever of its directories --repo names.
const repo = (await git(options.repo, "rev-parse", "--show-toplevel").catch((error: Error) =>
  fail(`${resolve(options.repo)} is not a git work tree: ${error.message.split("\n")[0]}`))).trim()

// The board, the agents' sessions and the cards' worktrees. Inside the repository, git is told to ignore it in
// .git/info/exclude, which stays on this machine (a .gitignore would be committed).
const state = resolve(options.state ?? join(repo, ".supermux-kanban"))
const worktrees = join(state, "worktrees")
mkdirSync(worktrees, { recursive: true, mode: 0o700 })
const inside = relative(repo, state)
if (inside && !inside.startsWith("..") && !isAbsolute(inside)) {
  const exclude = resolve(repo, (await git(repo, "rev-parse", "--git-path", "info/exclude")).trim())
  const entry = `/${inside.split(sep).join("/")}/`
  const text = existsSync(exclude) ? readFileSync(exclude, "utf8") : ""
  if (!text.split("\n").includes(entry)) {
    mkdirSync(dirname(exclude), { recursive: true })
    appendFileSync(exclude, `${text && !text.endsWith("\n") ? "\n" : ""}${entry}\n`)
  }
}

// Every agent with supermux()'s defaults (its CLI's own asking mode: permission prompts come to the board).
const core = orFail(() => supermux({
  stateDirectory: state,
  agents: Object.fromEntries(names.map(name => [name, { model: models.get(name) }])),
}))

type Column = "backlog" | "running" | "needs-you" | "review" | "done"

/** A task for one agent. Started, it has a branch and a worktree of its own, and a session with the card's id. */
type Card = {
  id: string; title: string; prompt: string; agent: string; column: Column
  branch?: string; worktree?: string
  base?: string // the commit the branch starts from
  diffStat?: string // what the agent changed since base, as `git diff --shortstat` says it, taken at each review
  error?: string // why the agent, or its last turn, failed
  request?: PendingRequest["body"] // the latest permission prompt or question the agent waits on
  activity?: string // what the agent did last, in a line
}

const boardFile = join(state, "board.json")
const cards: Card[] = existsSync(boardFile) ? JSON.parse(readFileSync(boardFile, "utf8")).cards : []
const streams = new Set<ServerResponse>()
// Whether this server is detaching (Ctrl-C): from then on the board stays as it is, for the next start.
let detaching = false

/** Saves the board. Written aside and renamed over: a crash mid-write cannot leave a file that stops the next start. */
function save() {
  writeFileSync(`${boardFile}.tmp`, JSON.stringify({ cards }))
  renameSync(`${boardFile}.tmp`, boardFile)
}

/** What /api/events sends: the whole board after every change, and each card's status line as it comes. */
function broadcast(message: { type: "board"; cards: Card[] } | { type: "activity"; cardId: string; text: string }) {
  for (const stream of streams) stream.write(`data: ${JSON.stringify(message)}\n\n`)
}

/** After every change: saves the board and sends it to every page. */
function publish() {
  save()
  broadcast({ type: "board", cards })
}

function update(card: Card, change: Partial<Card>) {
  Object.assign(card, change)
  publish()
}

/** What the agent changed since the card's base, new files included (`git add --intent-to-add` makes them count). */
async function diffOf(card: Card, ...args: string[]) {
  if (!card.worktree || !card.base) throw new CoreError("not_started", `Card ${card.id} has not started`)
  await git(card.worktree, "add", "--all", "--intent-to-add")
  return git(card.worktree, "diff", "--no-color", "--no-ext-diff", ...args, card.base)
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
  const column = state === "failed" ? "backlog" : request ? "needs-you" : state === "idle" ? "review" : "running"
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

/** A route's handler gets the path's parameters and the JSON body of a POST. */
type Handler = (params: [string, string], body: any) => unknown

const routes: Array<[method: string, path: RegExp, handle: Handler]> = [
  ["GET", /^\/api\/agents$/, () => names],
  ["GET", /^\/api\/board$/, () => ({ cards })],
  ["POST", /^\/api\/cards$/, (_, body) => {
    const [title, prompt] = [textOf(body, "title").trim(), textOf(body, "prompt")]
    if (!names.includes(body.agent)) throw new CoreError("unknown_agent", `agent is one of ${names.join(", ")}`)
    const card: Card = { id: randomBytes(4).toString("hex"), title, prompt, agent: body.agent, column: "backlog" }
    cards.push(card)
    publish()
    return { id: card.id }
  }],
  ["POST", /^\/api\/cards\/([\w-]+)\/start$/, async ([id]) => {
    const card = cardOf(id)
    if (card.column !== "backlog") throw new CoreError("session_busy", `Card ${id} is not in the backlog`)
    let { worktree } = card
    if (!worktree) {
      // A branch of its own, from the commit the repository is on, checked out in a worktree of its own.
      const base = (await git(repo, "rev-parse", "HEAD")).trim()
      worktree = join(worktrees, id)
      await git(repo, "worktree", "add", worktree, "-b", `agent/${id}`, base)
      update(card, { base, worktree, branch: `agent/${id}` })
    }
    // A card back in the backlog (its agent failed) goes on where it was: same worktree, same conversation.
    const session = await core.sessions.get(id)
      ? await core.sessions.resume(id)
      : await core.sessions.create({ id, agent: card.agent, cwd: worktree })
    await run(card, session, card.prompt)
    return { ok: true }
  }],
  ["POST", /^\/api\/cards\/([\w-]+)\/followup$/, async ([id], body) => {
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
  }],
  ["POST", /^\/api\/cards\/([\w-]+)\/requests\/([^/]+)$/, async ([id, requestId], answer) => {
    await requireOpen(id).requests.respond(requestId, answer)
    sync(cardOf(id))
    return { ok: true }
  }],
  // Stop ends the turn and cancels the messages queued behind it.
  ["POST", /^\/api\/cards\/([\w-]+)\/interrupt$/, ([id]) => requireOpen(id).interrupt({ pending: "discard" })],
  ["GET", /^\/api\/cards\/([\w-]+)\/diff$/, ([id]) => diffOf(cardOf(id))],
  // Done stops the agent; its branch and worktree stay, to merge or to look at.
  ["POST", /^\/api\/cards\/([\w-]+)\/done$/, async ([id]) => {
    const card = cardOf(id)
    await core.sessions.close(id, { mode: "shutdown" })
    update(card, { column: "done", request: undefined })
    return { ok: true }
  }],
  // Discard stops the agent and deletes its worktree, its branch and the card. What is gone already is no error.
  ["POST", /^\/api\/cards\/([\w-]+)\/discard$/, async ([id]) => {
    const card = cardOf(id)
    await core.sessions.close(id, { mode: "shutdown" })
    await core.sessions.forget(id)
    if (card.worktree && existsSync(card.worktree)) await git(repo, "worktree", "remove", "--force", card.worktree)
    await git(repo, "worktree", "prune")
    if (card.branch && (await git(repo, "branch", "--list", card.branch)).trim()) {
      await git(repo, "branch", "-D", card.branch)
    }
    cards.splice(cards.indexOf(card), 1)
    publish()
    return { ok: true }
  }],
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
  fail(error.code === "state_locked" ? `${state} is in use by another kanban server` : String(error)))
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
