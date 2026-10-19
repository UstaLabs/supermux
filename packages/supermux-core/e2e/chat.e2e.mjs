import { test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync } from "node:fs"
import { homedir, tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { fileURLToPath } from "node:url"
import { startServer } from "./support/server.mjs"
import { openEvents } from "./support/sse.mjs"

const script = fileURLToPath(new URL("../examples/chat/server.ts", import.meta.url))
// startServer() puts the stub agent CLIs on PATH, and they are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
// The Claude fixture takes an allow only when told to expect one: otherwise it exits, and the turn fails.
const env = { EXPECT_PERM: "allow" }

/**
 * A directory for a test's state directory and chat folders. Under the home, not /tmp: the server refuses a state
 * directory inside a temporary directory, which agents can write to.
 */
const mkDir = () => mkdtempSync(join(homedir(), ".chat-e2e-"))

/** A folder for a chat to work in, made beside the state directory: the server refuses a folder that holds its state. */
function workFolder(dir) {
  const work = join(dir, "work")
  mkdirSync(work)
  return work
}

/** What startServer() fails with, for a start that must be refused. A server that starts after all is stopped, and fails the test. */
async function refusedStart(args, extraEnv) {
  const outcome = await startServer(script, args, extraEnv).catch(error => error)
  if (outcome instanceof Error) return outcome.message
  await outcome.stop()
  assert.fail(`the server started with ${args.join(" ")}`)
}

/** Closes every chat `server` has: a test that fails must not leave an agent running behind its keeper. */
async function closeAll(server) {
  for (const { id } of await server.get("/api/sessions").catch(() => [])) await server.post(`/api/sessions/${id}/close`).catch(() => {})
}

test("chat: create a session, stream a reply, answer a permission prompt, survive a restart", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  const state = join(dir, "state")
  const trace = join(dir, "claude-launch.json")
  // The agent list is trimmed, and --model reaches the CLI (the Claude fixture writes its argv to ENV_TRACE).
  const args = ["--state", state, "--agents", "claude, codex", "--model", "claude=x"]
  let server
  let id
  try {
    server = await startServer(script, args, { ...env, ENV_TRACE: trace })
    assert.deepEqual(await server.get("/api/agents"), ["claude", "codex"])
    // No other site may frame the page, to trick a click on "Always allow".
    const page = await server.fetch("/")
    const framing = ["x-frame-options", "content-security-policy"].map(name => page.headers.get(name))
    assert.deepEqual(framing, ["DENY", "frame-ancestors 'none'"])
    assert.match(await page.text(), /id="composer"/)
    let events = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: work }))
    await server.post(`/api/sessions/${id}/messages`, { text: "hello" })
    const reply = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed")
    assert.equal(reply.result.status, "completed")
    assert.ok(events.seen.some(m => m.type === "event" && m.sessionId === id))
    // The claude stub answered, not a real CLI, and it was launched with the model.
    assert.deepEqual(server.stubs.launched(), ["claude"])
    const { argv } = JSON.parse(readFileSync(trace, "utf8"))
    assert.deepEqual(argv.slice(argv.indexOf("--model"), argv.indexOf("--model") + 2), ["--model", "x"])

    await server.post(`/api/sessions/${id}/messages`, { text: "permission" })
    const request = await events.next(m => m.type === "event" && m.sessionId === id && m.event.kind === "permission-request")
    assert.deepEqual(await server.get("/api/sessions"), [{ id, agent: "claude", cwd: work, title: "hello", state: "running", pendingRequests: 1 }])
    const allow = request.event.options.find(o => o.kind === "allow_once")
    await server.post(`/api/sessions/${id}/requests/${request.event.requestId}`, { optionId: allow.id })
    const answered = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed" && events.seen.indexOf(m) > events.seen.indexOf(request))
    assert.equal(answered.result.status, "completed")
    // A session the user closes is gone for good: no restart lists it, nor launches its agent again.
    const closed = await server.post("/api/sessions", { agent: "claude", cwd: work })
    await server.post(`/api/sessions/${closed.id}/close`)
    events.close()

    // SIGTERM detaches: the agent keeps running behind its keeper, and the next server re-attaches to it.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    server = await startServer(script, args, env)
    const transcript = await server.get(`/api/sessions/${id}/transcript`)
    assert.ok(transcript.some(entry => entry.type === "user" && entry.text === "hello"))
    const sessions = await server.get("/api/sessions")
    assert.equal(sessions.find(session => session.id === id).title, "hello")
    events = await server.events()
    await server.post(`/api/sessions/${id}/messages`, { text: "hello again" })
    const again = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed")
    assert.equal(again.result.status, "completed")
    // The transcript goes on where it was: its lines run 1, 2, 3, ... across the restart.
    const lines = (await server.get(`/api/sessions/${id}/transcript`)).map(entry => entry.line)
    assert.deepEqual(lines, lines.map((_, index) => index + 1))
    // Time for a wrongful relaunch of the closed session to reach the stub log.
    await sleep(1_000)
    assert.deepEqual(server.stubs.launched(), [], "the restart launched an agent: the closed session's, or this one's again")
    assert.deepEqual((await server.get("/api/sessions")).map(session => session.id), [id])
    events.close()
    await server.post(`/api/sessions/${id}/close`)
  } finally {
    // A failure above must not leave the agent running behind its keeper.
    if (id) await server.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: after Stop the next message runs, and a message to an agent that died starts it again", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  let server
  let id
  try {
    server = await startServer(script, ["--state", join(dir, "state"), "--agents", "claude"], env)
    const events = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: work }))
    const send = text => server.post(`/api/sessions/${id}/messages`, { text })
    const phase = (name, { messageId }) => events.next(m => m.type === "message" && m.messageId === messageId && m.phase === name)

    // The Claude fixture ends a "hang" turn only when it is interrupted. Stop also cancels the message queued
    // behind it, and the next one runs on its own.
    const hang = await send("hang")
    await phase("started", hang)
    const queued = await send("queued")
    assert.deepEqual(await server.post(`/api/sessions/${id}/interrupt`), { status: "stopped" })
    assert.equal((await phase("completed", hang)).result.status, "cancelled")
    assert.equal((await phase("completed", queued)).result.status, "cancelled")
    // The transcript keeps a completion with the line of the message it answers: a page loaded later still shows it.
    const kept = (await server.get(`/api/sessions/${id}/transcript`))
      .find(entry => entry.type === "message" && entry.messageId === queued.messageId)
    assert.deepEqual([kept.userLine, kept.result.status], [queued.line, "cancelled"])
    const next = await send("hello")
    assert.equal((await phase("completed", next)).result.status, "completed")
    const started = events.seen.filter(m => m.type === "message" && m.phase === "started").map(m => m.messageId)
    assert.deepEqual(started, [hang.messageId, next.messageId])

    // It exits on "disconnect", which fails the session; the next message launches the agent again.
    await send("disconnect")
    await events.next(m => m.type === "failed" && m.sessionId === id)
    assert.equal((await phase("completed", await send("hello"))).result.status, "completed")
    assert.deepEqual(server.stubs.launched(), ["claude", "claude"])

    // Claude takes a message for a subagent through its main thread: the relay is a message of its own.
    const relay = await server.post(`/api/sessions/${id}/subagents/task-1/message`, { text: "hi" })
    assert.equal(relay.via, "relay")
    assert.equal((await phase("completed", relay)).result.status, "completed")
    events.close()
  } finally {
    if (id) await server.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: a turn still running when the server stops is not labelled cancelled", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  const args = ["--state", join(dir, "state"), "--agents", "claude"]
  let server
  let id
  try {
    server = await startServer(script, args, env)
    const events = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: work }))
    const send = text => server.post(`/api/sessions/${id}/messages`, { text })
    // The Claude fixture ends a "hang" turn only when it is interrupted: it still runs when the server stops.
    const hang = await send("hang")
    await events.next(m => m.type === "message" && m.messageId === hang.messageId && m.phase === "started")
    const queued = await send("queued")
    events.close()
    // SIGTERM detaches: the agent goes on with the turn, and the next server re-attaches to it. The message that was
    // running is not labelled cancelled; the one queued behind it is, as the queue does not outlive the server.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    server = await startServer(script, args, env)
    const outcomes = (await server.get(`/api/sessions/${id}/transcript`)).filter(entry => entry.type === "message")
    assert.deepEqual(outcomes.map(entry => [entry.userLine, entry.result.status]), [[queued.line, "cancelled"]])
  } finally {
    if (id) await server?.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: transcript lines let a page load, skip what it has from the live stream, and catch up after a reconnect", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  let server
  let id
  try {
    server = await startServer(script, ["--state", join(dir, "state"), "--agents", "codex"], env)
    // `watch` sees every turn end; `page` stands for a browser tab, whose stream will drop.
    const watch = await server.events()
    const page = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "codex", cwd: work }))
    const transcript = (after = 0) => server.get(`/api/sessions/${id}/transcript?after=${after}`)
    const turn = async text => {
      const { messageId } = await server.post(`/api/sessions/${id}/messages`, { text })
      const done = await watch.next(m => m.type === "message" && m.messageId === messageId && m.phase === "completed")
      assert.equal(done.result.status, "completed")
    }

    // What the transcript keeps, the stream carries too, with the same line: 1, 2, 3, ... Only the stream carries
    // text deltas (the final message has their text), states, and the message phases before the completion.
    await turn("hello")
    const stored = await transcript()
    assert.deepEqual(stored.map(entry => entry.line), stored.map((_, index) => index + 1))
    // The user's message comes before the turn it starts.
    const turnStart = stored.findIndex(entry => entry.type === "event" && entry.event.kind === "turn-start")
    assert.ok(turnStart >= 0 && stored.findIndex(entry => entry.type === "user") < turnStart, JSON.stringify(stored.map(e => e.event?.kind ?? e.type)))
    await page.next(m => m.line === stored.at(-1).line)
    assert.deepEqual(page.seen.filter(m => m.line !== undefined), stored)
    assert.ok(page.seen.some(m => m.type === "event" && m.event.kind === "assistant-delta" && m.line === undefined))
    assert.ok(stored.some(entry => entry.type === "event" && entry.event.kind === "assistant-message"))
    assert.ok(stored.every(entry => entry.type !== "event" || (!entry.event.kind.endsWith("-delta") && !("native" in entry.event))))

    // The tab loses its stream while a turn runs. It reconnects, asks for the lines after the last one it has, and
    // takes from the new stream only lines past those: it ends up with every line, once.
    page.close()
    await turn("hello again")
    const reconnected = await server.events()
    const missed = await transcript(stored.at(-1).line)
    assert.ok(missed.some(entry => entry.type === "user" && entry.text === "hello again"))
    await turn("and again")
    const all = await transcript()
    await reconnected.next(m => m.line === all.at(-1).line)
    assert.deepEqual([...stored, ...missed, ...reconnected.seen.filter(m => m.line > missed.at(-1).line)], all)
    watch.close()
    reconnected.close()
  } finally {
    if (id) await server.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: a large message arrives whole, and what the API cannot take it refuses, saying why", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  const state = join(dir, "state")
  let server
  let id
  try {
    // This Claude fixture never confirms an interrupt: a stopped turn stays "interrupting".
    server = await startServer(script, ["--state", state, "--agents", "claude"], { ...env, MODE: "interrupt-hang" })
    const events = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: work }))
    const send = (text, headers = {}) => server.fetch(`/api/sessions/${id}/messages`, { method: "POST", headers, body: JSON.stringify({ text }) })
    const refusal = async response => [response.status, (await response.json()).code]

    // 540 KB of two-, three- and four-byte characters: the body arrives in chunks that split characters.
    const text = "ğ€😀".repeat(60_000)
    const { messageId } = await (await send(text)).json()
    await events.next(m => m.type === "message" && m.messageId === messageId && m.phase === "completed")
    assert.equal(events.seen.find(m => m.type === "user").text, text)
    assert.equal((await server.get(`/api/sessions/${id}/transcript`)).find(entry => entry.type === "user").text, text)
    // A title is the first message, cut by character (an emoji stays whole) or after a word, with an ellipsis.
    const title = async chat => (await server.get("/api/sessions")).find(session => session.id === chat).title
    assert.equal(await title(id), "ğ€😀".repeat(20) + "…")
    const worded = await server.post("/api/sessions", { agent: "claude", cwd: work })
    const long = "Find where the users route reads the email,\nand add a check for it before the insert"
    await server.post(`/api/sessions/${worded.id}/messages`, { text: long })
    assert.equal(await title(worded.id), "Find where the users route reads the email, and add a check…")
    await server.post(`/api/sessions/${worded.id}/close`)
    assert.deepEqual(await refusal(await send(text + text)), [413, "too_large"])

    // Another site's page, a broken escape, a line that is not a number, a session that does not exist.
    assert.deepEqual(await refusal(await send("hi", { origin: "http://evil.example" })), [403, "forbidden"])
    assert.deepEqual(await refusal(await server.fetch(`/api/sessions/${id}/subagents/%E0%A4%A/stop`, { method: "POST" })), [400, "invalid_input"])
    assert.deepEqual(await refusal(await server.fetch(`/api/sessions/${id}/transcript?after=x`)), [400, "invalid_input"])
    assert.deepEqual(await refusal(await server.fetch("/api/sessions/nosuch/transcript")), [404, "session_not_found"])
    const stranger = await server.fetch("/api/sessions/nosuch/messages", { method: "POST", body: JSON.stringify({ text: "hi" }) })
    assert.deepEqual(await refusal(stranger), [404, "session_not_found"])

    // A message the session refuses (its queue is full: 128 wait behind a turn) is taken back: a user-rejected
    // entry, kept after it, names its line.
    const hang = await (await send("hang")).json()
    await events.next(m => m.type === "message" && m.messageId === hang.messageId && m.phase === "started")
    const queued = []
    for (let i = 0; i < 128; i++) queued.push(await (await send(`queued ${i}`)).json())
    assert.deepEqual(await refusal(await send("one too many")), [429, "queue_full"])
    const refused = await events.next(m => m.type === "user" && m.text === "one too many")
    const rejection = await events.next(m => m.type === "user-rejected" && m.userLine === refused.line)
    assert.deepEqual(await server.get(`/api/sessions/${id}/transcript?after=${refused.line - 1}`), [refused, rejection])

    // Stop cancels every queued message at once. This agent never confirms it: while its turn is still ending, a
    // message is refused, saying to send again or close the session, and leaves nothing behind.
    const stopping = server.post(`/api/sessions/${id}/interrupt`).catch(error => error)
    for (const { messageId } of queued) {
      const done = await events.next(m => m.type === "message" && m.messageId === messageId && m.phase === "completed")
      assert.equal(done.result.status, "cancelled")
    }
    await events.next(m => m.type === "state" && m.sessionId === id && m.state === "interrupting")
    const early = await send("too soon")
    assert.equal(early.status, 409)
    assert.match((await early.json()).error, /still stopping.*Close the session/)
    assert.ok(!(await server.get(`/api/sessions/${id}/transcript`)).some(entry => entry.type === "user" && entry.text === "too soon"))
    await server.post(`/api/sessions/${id}/close`)
    await stopping

    // A second server on the same state directory says so in one line, not a stack trace, and exits.
    await assert.rejects(startServer(script, ["--state", state, "--agents", "claude"], env), error => {
      assert.match(error.message, /server exited with code 1\n[^]*is in use by another chat server/)
      assert.doesNotMatch(error.message, /\n\s+at /)
      return true
    })
    events.close()
  } finally {
    if (id) await server.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: a folder that holds the state directory is refused, however it is named; a folder beside it is not", { skip }, async () => {
  const dir = mkDir()
  const state = join(dir, "state")
  const work = workFolder(dir)
  // A folder whose name only begins like the state directory's does not hold it.
  const stat = join(dir, "stat")
  mkdirSync(stat)
  // A link to the folder around the state directory is that folder: real paths are compared.
  const link = join(dir, "link")
  symlinkSync(dir, link)
  let server
  try {
    server = await startServer(script, ["--state", state, "--agents", "claude"], env)
    const create = cwd => server.fetch("/api/sessions", { method: "POST", body: JSON.stringify({ agent: "claude", cwd }) })

    // An agent can write the folder it works in, and a symlink it planted in the state directory would make a keeper write
    // outside the sandbox: the folder around the state directory, a link to it and the state directory itself are refused.
    for (const cwd of [dir, link, state, `${work}/../state`]) {
      const response = await create(cwd)
      assert.equal(response.status, 400, cwd)
      assert.match((await response.json()).error, /state directory is inside this folder; start the server with --state outside it/, cwd)
    }
    assert.deepEqual(await server.get("/api/sessions"), [])
    assert.deepEqual(server.stubs.launched(), [])
    // A folder that is not there is refused as it always was, in the core's words.
    const missing = await create(join(dir, "missing"))
    assert.deepEqual([missing.status, (await missing.json()).error], [400, "cwd must be an existing absolute directory"])

    // Beside the state directory, or under a name that only begins like it, a chat starts.
    for (const cwd of [work, stat]) await server.post("/api/sessions", { agent: "claude", cwd })
    assert.deepEqual((await server.get("/api/sessions")).map(session => session.cwd).sort(), [stat, work].sort())
  } finally {
    if (server) await closeAll(server)
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: the state lives in the home directory unless --state says otherwise, and the home directory is no folder for a chat", { skip }, async () => {
  // A HOME of its own, empty, outside the temporary directories (where startServer() makes the stubs'): the default state
  // directory is made in it.
  const home = mkDir()
  let server
  try {
    server = await startServer(script, ["--agents", "claude"], { ...env, HOME: home })
    assert.ok(existsSync(join(home, ".supermux-chat", "transcripts")))
    const response = await server.fetch("/api/sessions", { method: "POST", body: JSON.stringify({ agent: "claude", cwd: home }) })
    assert.equal(response.status, 400)
    assert.match((await response.json()).error, /state directory is inside this folder/)
  } finally {
    if (server) await closeAll(server)
    await server?.stop()
    rmSync(home, { recursive: true, force: true })
  }
})

test("chat: a state directory inside a temporary directory is refused, under any name, and nothing is made in it", { skip }, async () => {
  // The next start trusts what it finds there, and a sandboxed agent can write to the temporary directories.
  const dir = mkDir()
  const temp = mkdtempSync(join(tmpdir(), "chat-e2e-"))
  // A link from the home into a temporary directory is that directory: real paths are compared.
  const link = join(dir, "link")
  symlinkSync(temp, link)
  // And a TMPDIR that is a link (as /tmp is one, to /private/tmp, on macOS) holds what the directory it names holds.
  const real = join(dir, "real-tmp")
  mkdirSync(real)
  const alias = join(dir, "tmp-alias")
  symlinkSync(real, alias)
  try {
    const refused = [[join(temp, "state"), {}], [join(link, "state"), {}], [join(real, "state"), { TMPDIR: alias }]]
    for (const [state, extraEnv] of refused) {
      const message = await refusedStart(["--state", state, "--agents", "claude"], { ...env, ...extraEnv })
      assert.match(message, /server exited with code 1\n[^]*is inside a temporary directory agents can write to: pass a --state outside it/)
      assert.doesNotMatch(message, /\n\s+at /)
      assert.equal(existsSync(state), false, state)
    }
    // The default one too, in a HOME in a temporary directory (the one startServer() gives).
    assert.match(await refusedStart(["--agents", "claude"], env), /is inside a temporary directory/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
    rmSync(temp, { recursive: true, force: true })
  }
})

test("chat: every request needs the token the server printed, as a bearer token or as the cookie its URL sets", { skip }, async () => {
  const dir = mkDir()
  const args = ["--state", join(dir, "state"), "--agents", "claude"]
  let server
  try {
    server = await startServer(script, args, env)
    // A new token at each start, 32 random bytes in hex, which only the terminal that started the server sees.
    assert.match(server.token, /^[0-9a-f]{64}$/)
    const status = async (path, init) => (await fetch(server.url + path, init)).status
    // Any process on the machine can reach 127.0.0.1: without the token, or with another, the API and the event stream
    // say 401, and nothing is made.
    const port = new URL(server.url).port
    const wrong = [{}, { authorization: "Bearer nope" }, { authorization: `Bearer ${server.token}0` },
      { cookie: `supermux-chat-${port}=${"0".repeat(64)}` }]
    for (const headers of wrong) {
      for (const path of ["/api/agents", "/api/sessions", "/api/events"]) assert.equal(await status(path, { headers }), 401, `${path} ${JSON.stringify(headers)}`)
      const create = await fetch(`${server.url}/api/sessions`, { method: "POST", headers, body: JSON.stringify({ agent: "claude", cwd: dir }) })
      assert.deepEqual([create.status, (await create.json()).code], [401, "unauthorized"])
    }
    assert.deepEqual(await server.get("/api/sessions"), [])
    // A browser without the cookie gets a page that says where the token is, and a wrong token in the URL sets no cookie.
    for (const path of ["/", "/?token=nope"]) {
      const page = await fetch(server.url + path, { redirect: "manual" })
      assert.equal(page.status, 401, path)
      assert.equal(page.headers.get("set-cookie"), null, path)
      assert.match(await page.text(), /Open the URL the server printed in its terminal/, path)
    }

    // The printed URL sets the cookie, HttpOnly and SameSite=Strict, and sends the browser on to the page without the token.
    const signIn = await fetch(`${server.url}/?token=${server.token}`, { redirect: "manual" })
    assert.deepEqual([signIn.status, signIn.headers.get("location")], [302, "/"])
    const cookie = signIn.headers.get("set-cookie")
    assert.equal(cookie, `supermux-chat-${port}=${server.token}; HttpOnly; SameSite=Strict; Path=/`)
    const jar = { cookie: cookie.split(";")[0] }
    // From then on the page, the API and the event stream answer the cookie.
    const page = await fetch(server.url + "/", { headers: jar })
    assert.equal(page.status, 200)
    assert.match(await page.text(), /id="composer"/)
    assert.deepEqual(await (await fetch(`${server.url}/api/agents`, { headers: jar })).json(), ["claude"])
    const events = await openEvents(`${server.url}/api/events`, { headers: jar })
    events.close()
    // A script sends the token as a bearer token (what server.get() does).
    assert.deepEqual(await server.get("/api/agents"), ["claude"])
    // The token is no way past the Origin check: another site's page that had it is still refused.
    assert.equal(await status("/api/agents", { headers: { ...jar, origin: "http://evil.example" } }), 403)
    await server.stop()

    // --token keeps one token across starts, so a page stays signed in; one too short to be a secret is refused.
    const fixed = "a-token-of-my-own-1234"
    server = await startServer(script, [...args, "--token", fixed], env)
    assert.equal(server.token, fixed)
    assert.equal(await status("/api/agents", { headers: { cookie: `supermux-chat-${new URL(server.url).port}=${fixed}` } }), 200)
    await server.stop()
    await assert.rejects(startServer(script, [...args, "--token", "short"], env), error => {
      assert.match(error.message, /server exited with code 1\n[^]*--token takes 16 or more/)
      return true
    })
  } finally {
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: Close stops the agent and forgets the session, its keeper's journal included; its transcript stays", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  const state = join(dir, "state")
  let server
  let id
  try {
    server = await startServer(script, ["--state", state, "--agents", "claude"], env)
    const events = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: work }))
    const { messageId } = await server.post(`/api/sessions/${id}/messages`, { text: "hello" })
    await events.next(m => m.type === "message" && m.messageId === messageId && m.phase === "completed")
    events.close()
    // What the core keeps for a session: its record, and its keeper's directory (the journal grows to 64 MB).
    const record = join(state, "sessions", `${id}.json`)
    const keeper = join(state, "keepers", id)
    assert.ok(existsSync(record) && existsSync(keeper))

    assert.deepEqual(await server.post(`/api/sessions/${id}/close`), { ok: true })
    assert.equal(existsSync(record), false, "the session's record outlived Close")
    assert.equal(existsSync(keeper), false, "the keeper's directory outlived Close")
    assert.deepEqual(await server.get("/api/sessions"), [])
    assert.deepEqual(JSON.parse(readFileSync(join(state, "chat.json"), "utf8")), { titles: {}, open: [] })
    // The transcript is the chat's own: it stays, in the state directory.
    const transcript = readFileSync(join(state, "transcripts", `${id}.ndjson`), "utf8")
    assert.ok(transcript.split("\n").some(line => line && JSON.parse(line).text === "hello"))
    // The session is no more: no message reaches it, and a second Close finds nothing to do.
    const message = await server.fetch(`/api/sessions/${id}/messages`, { method: "POST", body: JSON.stringify({ text: "hi" }) })
    assert.deepEqual([message.status, (await message.json()).code], [404, "session_not_found"])
    assert.deepEqual(await server.post(`/api/sessions/${id}/close`), { ok: true })
    id = undefined
  } finally {
    if (id) await server?.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("chat: a server that detaches tells no page that its chats stopped: their agents go on", { skip }, async () => {
  const dir = mkDir()
  const work = workFolder(dir)
  const args = ["--state", join(dir, "state"), "--agents", "claude"]
  let server
  let id
  try {
    server = await startServer(script, args, env)
    const events = await server.events()
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: work }))
    // The Claude fixture ends a "hang" turn only when it is interrupted: it still runs when the server stops.
    const { messageId } = await server.post(`/api/sessions/${id}/messages`, { text: "hang" })
    await events.next(m => m.type === "message" && m.messageId === messageId && m.phase === "started")
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    await events.next(() => false).catch(() => {}) // until the stream ends with the server
    // Detaching closes this server's hold on each session, not the session: a page keeps the state it last had.
    const states = events.seen.filter(m => m.type === "state" && m.sessionId === id).map(m => m.state)
    assert.deepEqual(states.slice(-1), ["running"])
  } finally {
    // The agent still runs behind its keeper: the next server stops it.
    server = await startServer(script, args, env).catch(() => undefined)
    if (id) await server?.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})
