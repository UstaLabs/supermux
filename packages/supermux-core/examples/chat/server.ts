// A chat app for coding agents on @supermux/core alone: Claude Code, Codex, Grok, OpenCode and Cursor
// sessions side by side, replies streamed as they come, permission prompts answered in the page.
//
//   node server.ts [--port 4321] [--state .supermux-chat] [--agents claude,codex]
//
// The page (index.html) posts JSON to /api/... and listens to one server-sent event stream, /api/events.
// Ctrl-C detaches from the agents instead of stopping them: each keeps running behind its keeper, and the
// next start re-attaches to every session, a turn in progress included.
import { randomBytes } from "node:crypto"
import { accessSync, appendFileSync, constants, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs"
import { createServer } from "node:http"
import type { IncomingMessage, ServerResponse } from "node:http"
import type { AddressInfo } from "node:net"
import { delimiter, join } from "node:path"
import { parseArgs } from "node:util"
import { CoreError, supermux } from "@supermux/core"
import type { Completion, EventEnvelope, NormalizedBody, SessionState } from "@supermux/core"

const { values: options } = parseArgs({
  options: {
    port: { type: "string", default: "4321" },
    state: { type: "string", default: ".supermux-chat" },
    agents: { type: "string" },
  },
})

// The agents asked for, or else each one whose CLI is on PATH.
const CLIS = { claude: "claude", codex: "codex", grok: "grok", opencode: "opencode", cursor: "cursor-agent" }
const onPath = (command: string) =>
  (process.env.PATH ?? "").split(delimiter).some(dir => {
    try {
      accessSync(join(dir, command), constants.X_OK)
      return statSync(join(dir, command)).isFile()
    } catch {
      return false
    }
  })
const agents = options.agents?.split(",") ?? Object.entries(CLIS).filter(([, command]) => onPath(command)).map(([name]) => name)
if (!agents.length) console.warn(`No agent CLI on PATH (${Object.values(CLIS).join(", ")}): pass --agents`)

// Every agent with supermux()'s defaults: its CLI's own asking mode, so permission prompts come to this app.
const core = supermux({ stateDirectory: options.state, agents: Object.fromEntries(agents.map(name => [name, {}])) })

// The app's own state, beside the core's: a transcript per session (NDJSON) and the session titles.
const transcripts = join(options.state, "transcripts")
mkdirSync(transcripts, { recursive: true, mode: 0o700 })
const titlesFile = join(options.state, "titles.json")
const titles: Record<string, string> = existsSync(titlesFile) ? JSON.parse(readFileSync(titlesFile, "utf8")) : {}

/** What /api/events sends. The first three are also appended to the session's transcript. */
type Entry =
  | { type: "event"; sessionId: string; event: EventEnvelope & NormalizedBody } // everything the agent does
  | { type: "user"; sessionId: string; text: string; ts: string } // a message from the page
  | { type: "failed"; sessionId: string; error: string } // the agent failed
  | { type: "state"; sessionId: string; state: SessionState } // idle, running, interrupting, ...
  | { type: "message"; sessionId: string; messageId: string; phase: "accepted" | "started" | "completed"; result?: Completion }
  | { type: "sessions" } // the session list changed: fetch it again

const streams = new Set<ServerResponse>()

function publish(entry: Entry) {
  // A failed message's Error would serialize as {}: it goes out as its message.
  const json = JSON.stringify(entry, (_key, value) => (value instanceof Error ? value.message : value))
  for (const stream of streams) stream.write(`data: ${json}\n\n`)
  if (entry.type !== "event" && entry.type !== "user" && entry.type !== "failed") return
  try {
    appendFileSync(join(transcripts, `${entry.sessionId}.ndjson`), json + "\n")
  } catch (error) {
    console.error(`Transcript ${entry.sessionId} lost an entry: ${error}`) // The live stream goes on.
  }
}

// One observer sees every session.
core.subscribe(event => {
  const { sessionId } = event
  switch (event.type) {
    case "session.event": return publish({ type: "event", sessionId, event: event.event })
    case "session.stateChanged": return publish({ type: "state", sessionId, state: event.state })
    case "session.failed": return publish({ type: "failed", sessionId, error: event.error.message })
    case "message.accepted": return publish({ type: "message", sessionId, messageId: event.messageId, phase: "accepted" })
    case "message.started": return publish({ type: "message", sessionId, messageId: event.messageId, phase: "started" })
    case "message.completed": return publish({ type: "message", sessionId, messageId: event.messageId, phase: "completed", result: event.result })
  }
})

/** The open session `id`: requests, interrupts and subagents belong to a running agent. */
function live(id: string) {
  const session = core.sessions.live(id)
  if (!session) throw new CoreError("session_closed", `Session ${id} is not open`)
  return session
}

function textOf(body: { text?: unknown }) {
  if (typeof body.text !== "string" || !body.text.trim()) throw new CoreError("invalid_input", "text is required")
  return body.text
}

const routes: Array<[method: string, path: RegExp, handle: (params: string[], body: any) => unknown]> = [
  ["GET", /^\/api\/agents$/, () => agents],
  ["GET", /^\/api\/sessions$/, async () => (await core.sessions.list()).reverse().map(({ id, agent, cwd }) => {
    const snapshot = core.sessions.live(id)?.snapshot()
    return { id, agent, cwd, title: titles[id] ?? null, state: snapshot?.state ?? "stopped", pendingRequests: snapshot?.pendingRequests ?? 0 }
  })],
  ["POST", /^\/api\/sessions$/, async (_, { agent, cwd, model }) => {
    const id = randomBytes(4).toString("hex")
    await core.sessions.create({ id, agent, cwd, ...(model ? { configuration: { model } } : {}) })
    publish({ type: "sessions" })
    return { id }
  }],
  ["GET", /^\/api\/sessions\/([\w-]+)\/transcript$/, ([id]) => {
    const file = join(transcripts, `${id}.ndjson`)
    const lines = existsSync(file) ? readFileSync(file, "utf8").split("\n") : []
    // A line cut short (a full disk) is skipped rather than failing the whole transcript.
    return lines.flatMap(line => { try { return [JSON.parse(line)] } catch { return [] } })
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/messages$/, async ([id], body) => {
    const text = textOf(body)
    // An open session comes back as it is; one that is not (a restart, an agent that died) is opened again.
    const session = await core.sessions.resume(id)
    publish({ type: "user", sessionId: id, text, ts: new Date().toISOString() })
    if (titles[id] === undefined) {
      titles[id] = text.slice(0, 60)
      // Written aside and renamed over: a crash mid-write cannot leave a file that stops the next start.
      writeFileSync(`${titlesFile}.tmp`, JSON.stringify(titles))
      renameSync(`${titlesFile}.tmp`, titlesFile)
      publish({ type: "sessions" })
    }
    const { messageId } = await session.send({ content: [{ type: "text", text }], whenBusy: "queue" })
    return { messageId }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/interrupt$/, async ([id]) => {
    const session = live(id)
    const result = await session.interrupt({ pending: "keep" })
    // An interrupt also pauses the queue: once the turn has stopped, queued and new messages run again.
    if (result.status !== "unconfirmed") session.pending.continue()
    return result
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/requests\/([^/]+)$/, async ([id, requestId], answer) => {
    await live(id).requests.respond(requestId, answer)
    return { ok: true }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/subagents\/([^/]+)\/stop$/, async ([id, subagentId]) => {
    await live(id).stopSubagent(subagentId)
    return { ok: true }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/subagents\/([^/]+)\/message$/, async ([id, subagentId], body) => {
    const { via } = await live(id).messageSubagent(subagentId, [{ type: "text", text: textOf(body) }])
    return { via }
  }],
  ["POST", /^\/api\/sessions\/([\w-]+)\/close$/, async ([id]) => {
    await core.sessions.close(id, { mode: "shutdown" })
    publish({ type: "sessions" })
    return { ok: true }
  }],
]

async function readBody(req: IncomingMessage) {
  let text = ""
  for await (const chunk of req) text += chunk
  try {
    const body = JSON.parse(text || "{}")
    if (body && typeof body === "object") return body
  } catch {}
  throw new CoreError("invalid_input", "The body must be a JSON object")
}

function reply(res: ServerResponse, status: number, body: unknown) {
  const json = JSON.stringify(body)
  res.writeHead(status, { "content-type": "application/json" }).end(json)
}

// Errors with a code (the core's, and this file's own) are the caller's: 4xx. Anything else is a 500.
const STATUS: Record<string, number> = {
  forbidden: 403, not_found: 404, session_not_found: 404, request_not_found: 404, subagent_not_found: 404,
}

const server = createServer(async (req, res) => {
  const { pathname } = new URL(req.url ?? "/", "http://127.0.0.1")
  try {
    // Only this server's own pages: not another site's, nor one that rebinds its name to 127.0.0.1.
    const here = [`127.0.0.1:${req.socket.localPort}`, `localhost:${req.socket.localPort}`]
    if (!here.includes(req.headers.host ?? "") || (req.headers.origin && !here.some(host => req.headers.origin === `http://${host}`))) {
      throw new CoreError("forbidden", "Requests must come from this server's own page")
    }
    if (req.method === "GET" && pathname === "/") {
      const page = readFileSync(new URL("index.html", import.meta.url))
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" }).end(page)
    } else if (req.method === "GET" && pathname === "/api/events") {
      res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" }).flushHeaders()
      streams.add(res)
      res.on("close", () => streams.delete(res))
    } else {
      const route = routes.find(([method, path]) => method === req.method && path.test(pathname))
      if (!route) throw new CoreError("not_found", `No route for ${req.method} ${pathname}`)
      const [, path, handle] = route
      const params = path.exec(pathname)!.slice(1).map(decodeURIComponent)
      reply(res, 200, await handle(params, req.method === "POST" ? await readBody(req) : {}))
    }
  } catch (error) {
    const code = error instanceof CoreError ? error.code : "internal"
    reply(res, code === "internal" ? 500 : (STATUS[code] ?? 400), { error: error instanceof Error ? error.message : String(error), code })
  }
})

// A comment every 15 s keeps an idle event stream open through proxies and timeouts.
setInterval(() => {
  for (const stream of streams) stream.write(": ping\n\n")
}, 15_000).unref()

// Re-attach to every saved session, in the background: one that cannot come back is listed as stopped.
for (const { id } of await core.sessions.list()) {
  core.sessions.resume(id).catch(error => console.error(`Session ${id} did not resume: ${error.message}`))
}

server.listen(Number(options.port), "127.0.0.1", () => {
  console.log(`listening on http://127.0.0.1:${(server.address() as AddressInfo).port}`)
})

// Ctrl-C detaches: the agents keep running behind their keepers, and the next start re-attaches to them.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, () => {
    core.close({ agents: "detach" }).then(() => process.exit(0), error => {
      console.error(error)
      process.exit(1)
    })
  })
}
