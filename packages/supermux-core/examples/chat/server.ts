// A chat app for coding agents on @supermux/core alone: Claude Code, Codex, Grok, OpenCode and Cursor
// sessions side by side, replies streamed as they come, permission prompts answered in the page.
//
//   node server.ts [--port 4321] [--state .supermux-chat] [--agents claude,codex] [--model claude=opus ...]
//
// The page (index.html) posts JSON to /api/... and listens to one server-sent event stream, /api/events.
// Ctrl-C detaches from the agents instead of stopping them: each keeps running behind its keeper, and the
// next start re-attaches to every session left open, a turn in progress included.
import { randomBytes } from "node:crypto"
import {
  accessSync, appendFileSync, constants, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync,
} from "node:fs"
import { createServer } from "node:http"
import type { IncomingMessage, ServerResponse } from "node:http"
import type { AddressInfo } from "node:net"
import { delimiter, join } from "node:path"
import { parseArgs } from "node:util"
import { CoreError, supermux } from "@supermux/core"
import type { Completion, CoreEvent, EventEnvelope, NormalizedBody, SessionState } from "@supermux/core"

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
    port: { type: "string", default: "4321" },
    state: { type: "string", default: ".supermux-chat" },
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

// Every agent with supermux()'s defaults (its CLI's own asking mode: permission prompts come to this app).
const core = orFail(() => supermux({
  stateDirectory: options.state,
  agents: Object.fromEntries(names.map(name => [name, { model: models.get(name) }])),
}))

// The app's own state, beside the core's: a transcript per session (NDJSON), each session's title, and the
// sessions left open, which the next start re-attaches to (a closed one waits for its next message).
const transcripts = join(options.state, "transcripts")
mkdirSync(transcripts, { recursive: true, mode: 0o700 })
const appFile = join(options.state, "chat.json")
const app: { titles: Record<string, string>; open: string[] } =
  existsSync(appFile) ? JSON.parse(readFileSync(appFile, "utf8")) : { titles: {}, open: [] }

function saveApp() {
  // Written aside and renamed over: a crash mid-write cannot leave a file that stops the next start.
  writeFileSync(`${appFile}.tmp`, JSON.stringify(app))
  renameSync(`${appFile}.tmp`, appFile)
}

/** Records whether session `id` is open: the next start re-attaches to the open ones. */
function setOpen(id: string, open: boolean) {
  app.open = app.open.filter(other => other !== id).concat(open ? [id] : [])
  saveApp()
}

/** An agent event as the page gets it: without `native`, the agent's own frame, which only a debugger needs. */
type AgentEvent = Omit<EventEnvelope, "native"> & NormalizedBody

/**
 * What /api/events sends. The entries a transcript keeps (the first four, but for text deltas, and a message's
 * completion) also carry `line`, their place in it: 1, 2, 3, ... A page loads the transcript, skips the live entries
 * it has, and after a reconnect asks for the lines it missed with ?after=<the last line it has>.
 */
type Entry =
  | { type: "event"; sessionId: string; event: AgentEvent } // everything the agent does
  | { type: "user"; sessionId: string; text: string; ts: string } // a message from the page
  | { type: "user-rejected"; sessionId: string; userLine: number } // the session refused that one: hide it
  | { type: "failed"; sessionId: string; error: string } // the agent failed
  | { type: "state"; sessionId: string; state: SessionState } // idle, running, interrupting, ...
  // A completion names the user line it answers, when the page sent that message: a reloaded page still shows which
  // message was cancelled or failed.
  | {
      type: "message"; sessionId: string; messageId: string; phase: "accepted" | "started" | "completed"
      result?: Completion; userLine?: number
    }
  | { type: "sessions" } // the session list changed: fetch it again

// Text deltas are live only: the final message carries their text.
const DELTAS = new Set(["assistant-delta", "reasoning-delta"])
const streams = new Set<ServerResponse>()
const lastLine = new Map<string, number>()
// The user line of each message the page sent, by messageId, until the message completes.
const userLines = new Map<string, number>()
// The messages whose turn has started, until they complete; and whether this server is detaching (Ctrl-C).
const started = new Set<string>()
let detaching = false

/** Sends `entry` to every event stream and, if a transcript keeps it, appends it there: returns its line then. */
function publish(entry: Entry) {
  const kept = entry.type === "user" || entry.type === "user-rejected" || entry.type === "failed"
    || (entry.type === "event" && !DELTAS.has(entry.event.kind))
    || (entry.type === "message" && entry.phase === "completed")
  const line = kept ? nextLine(entry.sessionId) : undefined
  // A failed message's Error would serialize as {}: it goes out as its message.
  const json = JSON.stringify({ ...entry, line }, (_key, value) => (value instanceof Error ? value.message : value))
  for (const stream of streams) stream.write(`data: ${json}\n\n`)
  if (!kept) return
  try {
    appendFileSync(join(transcripts, `${entry.sessionId}.ndjson`), json + "\n")
  } catch (error) {
    console.error(`Transcript ${entry.sessionId} lost line ${line}: ${error}`) // The live stream goes on.
  }
  return line
}

/** The entries kept for session `id`, in order. A line cut short (a full disk) is skipped. */
function readTranscript(id: string): Array<Entry & { line: number }> {
  const file = join(transcripts, `${id}.ndjson`)
  if (!existsSync(file)) return []
  return readFileSync(file, "utf8").split("\n").flatMap(text => {
    try { return [JSON.parse(text)] } catch { return [] }
  })
}

/** One past the last line of session `id`'s transcript, whichever run wrote it. */
function nextLine(id: string) {
  const line = (lastLine.get(id) ?? readTranscript(id).at(-1)?.line ?? 0) + 1
  lastLine.set(id, line)
  return line
}

/** What the page gets for a core event, if anything. */
function entryOf(event: CoreEvent): Entry | undefined {
  const { sessionId } = event
  switch (event.type) {
    case "session.event": {
      const { native: _native, ...agentEvent } = event.event
      return { type: "event", sessionId, event: agentEvent }
    }
    case "session.stateChanged": return { type: "state", sessionId, state: event.state }
    case "session.failed": return { type: "failed", sessionId, error: event.error.message }
    case "message.accepted": return { type: "message", sessionId, messageId: event.messageId, phase: "accepted" }
    case "message.started": return { type: "message", sessionId, messageId: event.messageId, phase: "started" }
    case "message.completed": {
      const userLine = userLines.get(event.messageId)
      userLines.delete(event.messageId)
      const { messageId, result } = event
      return { type: "message", sessionId, messageId, phase: "completed", result, userLine }
    }
  }
}

// One observer sees every session.
core.subscribe(event => {
  if (event.type === "message.started") started.add(event.messageId)
  if (event.type === "message.completed") {
    const wasStarted = started.delete(event.messageId)
    // Detaching cancels this server's receipt of a turn in progress, but the agent goes on with it behind its keeper
    // and the next start re-attaches to it: that "cancelled" is not how the message ends, so it is neither kept nor
    // sent. A message still queued is cancelled for real: the queue does not outlive the server.
    if (detaching && wasStarted && event.result.status === "cancelled") return
  }
  const entry = entryOf(event)
  if (entry) publish(entry)
})

/** Session `id` if it is open: its requests, turns and subagents belong to a running agent. */
function requireOpen(id: string) {
  const session = core.sessions.live(id)
  if (!session) throw new CoreError("session_closed", `Session ${id} is not open`)
  return session
}

function textOf(body: { text?: unknown }) {
  if (typeof body.text !== "string" || !body.text.trim()) throw new CoreError("invalid_input", "text is required")
  return body.text
}

/** A chat's title: its first message on one line, cut after a word near 60 characters, with an ellipsis. */
function titleOf(text: string) {
  const chars = [...text.replace(/\s+/g, " ").trim()] // by code point: an emoji is not cut in half
  if (chars.length <= 60) return chars.join("")
  const cut = chars.slice(0, 60).join("")
  const space = cut.lastIndexOf(" ")
  return `${space > 20 ? cut.slice(0, space) : cut}…`
}

/** A route's handler gets the path's parameters, the JSON body of a POST and the query. */
type Handler = (params: [string, string], body: any, query: URLSearchParams) => unknown

const routes: Array<[method: string, path: RegExp, handle: Handler]> = [
  ["GET", /^\/api\/agents$/, () => names],
  ["GET", /^\/api\/sessions$/, async () => (await core.sessions.list()).reverse().map(({ id, agent, cwd }) => {
    const snapshot = core.sessions.live(id)?.snapshot()
    return {
      id, agent, cwd, title: app.titles[id] ?? null,
      state: snapshot?.state ?? "stopped", pendingRequests: snapshot?.pendingRequests ?? 0,
    }
  })],
  ["POST", /^\/api\/sessions$/, async (_, { agent, cwd }) => {
    const id = randomBytes(4).toString("hex")
    await core.sessions.create({ id, agent, cwd })
    setOpen(id, true)
    publish({ type: "sessions" })
    return { id }
  }],
  ["GET", /^\/api\/sessions\/([\w-]+)\/transcript$/, async ([id], _, query) => {
    if (!(await core.sessions.get(id))) throw new CoreError("session_not_found", `There is no session ${id}`)
    const after = Number(query.get("after") ?? 0)
    if (!Number.isSafeInteger(after) || after < 0) throw new CoreError("invalid_input", "after takes a line number")
    return readTranscript(id).filter(entry => entry.line > after)
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/messages$/, async ([id], body) => {
    const text = textOf(body)
    // An open session comes back as it is; another (closed, from before a restart, its agent dead) is opened again.
    const session = await core.sessions.resume(id)
    if (!app.open.includes(id)) setOpen(id, true)
    // A Stop pauses the queue; the next message resumes it, once the stopped turn has ended.
    const { state, paused } = session.snapshot()
    if (state === "interrupting") {
      throw new CoreError("session_busy",
        "The agent is still stopping its last turn: send again in a moment, or Close the session if it never stops")
    }
    if (paused) session.pending.continue()
    // The message is kept before it is sent, so it comes ahead of the turn it starts; one the session refuses is
    // taken back. A user entry always has a line: the page links it to the messageId it gets back.
    const line = publish({ type: "user", sessionId: id, text, ts: new Date().toISOString() })!
    const { messageId } = await session.send({ content: [{ type: "text", text }], whenBusy: "queue" }).catch(error => {
      publish({ type: "user-rejected", sessionId: id, userLine: line })
      throw error
    })
    userLines.set(messageId, line)
    if (app.titles[id] === undefined) {
      app.titles[id] = titleOf(text)
      saveApp()
      publish({ type: "sessions" })
    }
    return { messageId, line }
  }],
  // Stop ends the turn and cancels the messages queued behind it: each completes "cancelled".
  ["POST", /^\/api\/sessions\/([\w-]+)\/interrupt$/, ([id]) => requireOpen(id).interrupt({ pending: "discard" })],
  ["POST", /^\/api\/sessions\/([\w-]+)\/requests\/([^/]+)$/, async ([id, requestId], answer) => {
    await requireOpen(id).requests.respond(requestId, answer)
    return { ok: true }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/subagents\/([^/]+)\/stop$/, async ([id, subagentId]) => {
    await requireOpen(id).stopSubagent(subagentId)
    return { ok: true }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/subagents\/([^/]+)\/message$/, async ([id, subagentId], body) => {
    const sent = await requireOpen(id).messageSubagent(subagentId, [{ type: "text", text: textOf(body) }])
    // A relay is a main-thread message asking the agent to pass it on: its id follows it like any other.
    return sent.via === "relay" ? { via: sent.via, messageId: sent.receipt.messageId } : { via: sent.via }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/close$/, async ([id]) => {
    await core.sessions.close(id, { mode: "shutdown" })
    setOpen(id, false)
    publish({ type: "sessions" })
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

function reply(res: ServerResponse, status: number, body: unknown) {
  const json = JSON.stringify(body)
  res.writeHead(status, { "content-type": "application/json" }).end(json)
}

// Error codes, the core's and this file's own, as HTTP statuses. Anything else is a 500.
const STATUS: Record<string, number> = {
  invalid_input: 400, invalid_session_id: 400, invalid_workdir: 400, unknown_agent: 400,
  forbidden: 403,
  not_found: 404, session_not_found: 404, request_not_found: 404, subagent_not_found: 404,
  session_busy: 409, session_failed: 409, session_closed: 409, session_exists: 409, subagent_unavailable: 409,
  too_large: 413, queue_full: 429, unsupported_operation: 501, core_closed: 503,
}

/** True for a request from this server's own page: not from another site, nor through a name rebound to 127.0.0.1. */
function isOwnPage(req: IncomingMessage) {
  const own = [`127.0.0.1:${req.socket.localPort}`, `localhost:${req.socket.localPort}`]
  const { host = "", origin } = req.headers
  return own.includes(host) && (origin === undefined || own.some(name => origin === `http://${name}`))
}

const server = createServer(async (req, res) => {
  // No other site may show the page in a frame, where a click could be tricked onto "Always allow".
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
      reply(res, 200, await handle([first, second], body, url.searchParams))
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
const saved = await core.sessions.list().catch(error =>
  fail(error.code === "state_locked" ? `${options.state} is in use by another chat server` : String(error)))
// Re-attach to the sessions left open, in the background: one that cannot come back is listed as stopped.
for (const { id } of saved) {
  if (!app.open.includes(id)) continue
  core.sessions.resume(id).catch(error => console.error(`Session ${id} did not resume: ${error.message}`))
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
