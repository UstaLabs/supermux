import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
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

test("chat: create a session, stream a reply, answer a permission prompt, survive a restart", { skip }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "chat-e2e-"))
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
    const page = await fetch(server.url + "/")
    const framing = ["x-frame-options", "content-security-policy"].map(name => page.headers.get(name))
    assert.deepEqual(framing, ["DENY", "frame-ancestors 'none'"])
    assert.match(await page.text(), /id="composer"/)
    let events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: dir }))
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
    assert.deepEqual(await server.get("/api/sessions"), [{ id, agent: "claude", cwd: dir, title: "hello", state: "running", pendingRequests: 1 }])
    const allow = request.event.options.find(o => o.kind === "allow_once")
    await server.post(`/api/sessions/${id}/requests/${request.event.requestId}`, { optionId: allow.id })
    const answered = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed" && events.seen.indexOf(m) > events.seen.indexOf(request))
    assert.equal(answered.result.status, "completed")
    // A session the user closes stays closed: no restart launches its agent again.
    const closed = await server.post("/api/sessions", { agent: "claude", cwd: dir })
    await server.post(`/api/sessions/${closed.id}/close`)
    events.close()

    // SIGTERM detaches: the agent keeps running behind its keeper, and the next server re-attaches to it.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    server = await startServer(script, args, env)
    const transcript = await server.get(`/api/sessions/${id}/transcript`)
    assert.ok(transcript.some(entry => entry.type === "user" && entry.text === "hello"))
    const sessions = await server.get("/api/sessions")
    assert.equal(sessions.find(session => session.id === id).title, "hello")
    events = await openEvents(server.url + "/api/events")
    await server.post(`/api/sessions/${id}/messages`, { text: "hello again" })
    const again = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed")
    assert.equal(again.result.status, "completed")
    // The transcript goes on where it was: its lines run 1, 2, 3, ... across the restart.
    const lines = (await server.get(`/api/sessions/${id}/transcript`)).map(entry => entry.line)
    assert.deepEqual(lines, lines.map((_, index) => index + 1))
    // Time for a wrongful relaunch of the closed session to reach the stub log.
    await sleep(1_000)
    assert.deepEqual(server.stubs.launched(), [], "the restart launched an agent: the closed session's, or this one's again")
    assert.equal((await server.get("/api/sessions")).find(session => session.id === closed.id).state, "stopped")
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
  const dir = mkdtempSync(join(tmpdir(), "chat-e2e-"))
  let server
  let id
  try {
    server = await startServer(script, ["--state", join(dir, "state"), "--agents", "claude"], env)
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: dir }))
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
  const dir = mkdtempSync(join(tmpdir(), "chat-e2e-"))
  const args = ["--state", join(dir, "state"), "--agents", "claude"]
  let server
  let id
  try {
    server = await startServer(script, args, env)
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: dir }))
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
  const dir = mkdtempSync(join(tmpdir(), "chat-e2e-"))
  let server
  let id
  try {
    server = await startServer(script, ["--state", join(dir, "state"), "--agents", "codex"], env)
    // `watch` sees every turn end; `page` stands for a browser tab, whose stream will drop.
    const watch = await openEvents(server.url + "/api/events")
    const page = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/sessions", { agent: "codex", cwd: dir }))
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
    const reconnected = await openEvents(server.url + "/api/events")
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
  const dir = mkdtempSync(join(tmpdir(), "chat-e2e-"))
  const state = join(dir, "state")
  let server
  let id
  try {
    // This Claude fixture never confirms an interrupt: a stopped turn stays "interrupting".
    server = await startServer(script, ["--state", state, "--agents", "claude"], { ...env, MODE: "interrupt-hang" })
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: dir }))
    const send = (text, headers = {}) => fetch(`${server.url}/api/sessions/${id}/messages`, { method: "POST", headers, body: JSON.stringify({ text }) })
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
    const worded = await server.post("/api/sessions", { agent: "claude", cwd: dir })
    const long = "Find where the users route reads the email,\nand add a check for it before the insert"
    await server.post(`/api/sessions/${worded.id}/messages`, { text: long })
    assert.equal(await title(worded.id), "Find where the users route reads the email, and add a check…")
    await server.post(`/api/sessions/${worded.id}/close`)
    assert.deepEqual(await refusal(await send(text + text)), [413, "too_large"])

    // Another site's page, a broken escape, a line that is not a number, a session that does not exist.
    assert.deepEqual(await refusal(await send("hi", { origin: "http://evil.example" })), [403, "forbidden"])
    assert.deepEqual(await refusal(await fetch(`${server.url}/api/sessions/${id}/subagents/%E0%A4%A/stop`, { method: "POST" })), [400, "invalid_input"])
    assert.deepEqual(await refusal(await fetch(`${server.url}/api/sessions/${id}/transcript?after=x`)), [400, "invalid_input"])
    assert.deepEqual(await refusal(await fetch(`${server.url}/api/sessions/nosuch/transcript`)), [404, "session_not_found"])
    const stranger = await fetch(`${server.url}/api/sessions/nosuch/messages`, { method: "POST", body: JSON.stringify({ text: "hi" }) })
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
