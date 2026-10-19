import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
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
  let server
  let id
  try {
    server = await startServer(script, ["--state", state, "--agents", "claude,codex"], env)
    assert.deepEqual(await server.get("/api/agents"), ["claude", "codex"])
    assert.match(await server.get("/"), /id="composer"/)
    let events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/sessions", { agent: "claude", cwd: dir }))
    await server.post(`/api/sessions/${id}/messages`, { text: "hello" })
    const reply = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed")
    assert.equal(reply.result.status, "completed")
    assert.ok(events.seen.some(m => m.type === "event" && m.sessionId === id))
    // The claude stub answered, not a real CLI.
    assert.deepEqual(server.stubs.launched(), ["claude"])

    await server.post(`/api/sessions/${id}/messages`, { text: "permission" })
    const request = await events.next(m => m.type === "event" && m.sessionId === id && m.event.kind === "permission-request")
    assert.deepEqual(await server.get("/api/sessions"), [{ id, agent: "claude", cwd: dir, title: "hello", state: "running", pendingRequests: 1 }])
    const allow = request.event.options.find(o => o.kind === "allow_once")
    await server.post(`/api/sessions/${id}/requests/${request.event.requestId}`, { optionId: allow.id })
    const answered = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed" && events.seen.indexOf(m) > events.seen.indexOf(request))
    assert.equal(answered.result.status, "completed")
    events.close()

    // SIGTERM detaches: the agent keeps running behind its keeper, and the next server re-attaches to it.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    server = await startServer(script, ["--state", state, "--agents", "claude,codex"], env)
    const transcript = await server.get(`/api/sessions/${id}/transcript`)
    assert.ok(transcript.some(entry => entry.type === "user" && entry.text === "hello"))
    const sessions = await server.get("/api/sessions")
    assert.equal(sessions[0].title, "hello")
    events = await openEvents(server.url + "/api/events")
    await server.post(`/api/sessions/${id}/messages`, { text: "hello again" })
    const again = await events.next(m => m.type === "message" && m.sessionId === id && m.phase === "completed")
    assert.equal(again.result.status, "completed")
    assert.deepEqual(server.stubs.launched(), [], "the restart launched the agent again instead of re-attaching to it")
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

    // The Claude fixture ends a "hang" turn only when it is interrupted.
    const hang = await send("hang")
    await phase("started", hang)
    assert.deepEqual(await server.post(`/api/sessions/${id}/interrupt`), { status: "stopped" })
    assert.equal((await phase("completed", hang)).result.status, "cancelled")
    assert.equal((await phase("completed", await send("hello"))).result.status, "completed")

    // It exits on "disconnect", which fails the session; the next message launches the agent again.
    await send("disconnect")
    await events.next(m => m.type === "failed" && m.sessionId === id)
    assert.equal((await phase("completed", await send("hello"))).result.status, "completed")
    assert.deepEqual(server.stubs.launched(), ["claude", "claude"])

    await assert.rejects(server.post("/api/sessions/nosuch/messages", { text: "hi" }), /404: .*"code":"session_not_found"/)
    events.close()
  } finally {
    if (id) await server.post(`/api/sessions/${id}/close`).catch(() => {})
    await server?.stop()
    rmSync(dir, { recursive: true, force: true })
  }
})
