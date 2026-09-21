// Run from a project that depends on the installed package, or from this repository after
// `bun run build`. Example: node examples/custom-driver.mjs
// No vendor CLI, credentials, network, or live model. State lives under a unique temp dir, always removed.
import assert from "node:assert/strict"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "supermux-core"

function echoDriver() {
  const appliedBySession = new Map()
  return {
    id: "echo",
    applied(sessionId) {
      const value = appliedBySession.get(sessionId)
      return value ? { ...value } : undefined
    },
    async open(context) {
      context.signal.throwIfAborted()
      let applied = { ...(context.configuration ?? {}) }
      appliedBySession.set(context.sessionId, applied)
      return {
        agentSessionId: `echo-${context.sessionId}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        async prompt(content) {
          const text = content.map(block => (block.type === "text" ? block.text : "")).join("")
          context.onUpdate({ protocol: "native", value: { echo: text } })
          return { stopReason: "end_turn" }
        },
        async interrupt() {},
        async close() {},
        async configure(configuration) {
          applied = { ...configuration }
          appliedBySession.set(context.sessionId, applied)
        },
        configuration() {
          return { ...applied }
        },
      }
    },
  }
}

/** Opens, then fails leftover close once so sessions.close(id) is the recovery path. */
function stickyOpenDriver() {
  let failClose = true
  return {
    id: "sticky",
    async open(context) {
      context.signal.throwIfAborted()
      return {
        agentSessionId: `sticky-${context.sessionId}`,
        capabilities: { resume: false, steer: false, fork: false, detach: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {
          if (failClose) {
            failClose = false
            throw new Error("leftover close failed")
          }
        },
      }
    },
  }
}

const stateDirectory = await mkdtemp(join(tmpdir(), "supermux-core-custom-"))
const echo = echoDriver()
const core = createCore({
  stateDirectory,
  agents: [echo, stickyOpenDriver()],
  limits: { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
})
const chain = []
core.subscribe(event => {
  if (event.type === "message.accepted" || event.type === "message.started" || event.type === "message.completed") {
    chain.push(event.type)
  }
})

try {
  const session = await core.sessions.create({
    id: "echo-session",
    agent: "echo",
    cwd: process.cwd(),
    configuration: { model: "echo-1", reasoningEffort: "low" },
  })
  assert.deepEqual(session.configuration(), { model: "echo-1", reasoningEffort: "low" })
  assert.deepEqual(echo.applied(session.id), { model: "echo-1", reasoningEffort: "low" })

  const empty = await core.sessions.create({
    id: "echo-empty",
    agent: "echo",
    cwd: process.cwd(),
    configuration: {},
  })
  assert.deepEqual(empty.configuration(), {})
  assert.deepEqual(echo.applied(empty.id), {})

  await session.configure({ model: "echo-2", reasoningEffort: "high" })
  assert.deepEqual(session.configuration(), { model: "echo-2", reasoningEffort: "high" })
  assert.deepEqual(echo.applied(session.id), { model: "echo-2", reasoningEffort: "high" })
  const snapshot = echo.applied(session.id)
  snapshot.model = "mutated"
  assert.equal(echo.applied(session.id).model, "echo-2")

  const receipt = await session.send({
    content: [{ type: "text", text: "ping" }],
    whenBusy: "queue",
  })
  const result = await receipt.completed
  assert.equal(result.status, "completed")
  assert.deepEqual(chain, ["message.accepted", "message.started", "message.completed"])

  await session.close({ mode: "shutdown" })
  await empty.close({ mode: "shutdown" })

  const stickyId = "sticky-demo-1"
  let first
  try {
    await core.sessions.create({
      id: stickyId,
      agent: "sticky",
      cwd: process.cwd(),
      configuration: { model: "should-fail-after-open" },
    })
  } catch (error) {
    first = error
  }
  // Open failed (unsupported_operation) AND the leftover close failed: Core reports both
  // in one AggregateError and keeps the leftover owned until sessions.close(id) confirms.
  assert.ok(first instanceof AggregateError, "expected AggregateError from failed open + failed cleanup")
  assert.equal(first.errors[0]?.code, "unsupported_operation")
  assert.equal(first.errors[1]?.message, "leftover close failed")
  await assert.rejects(
    core.sessions.create({ id: stickyId, agent: "echo", cwd: process.cwd() }),
    error => error.code === "session_busy",
  )
  await core.sessions.close(stickyId, { mode: "shutdown" })
  const recovered = await core.sessions.create({ id: stickyId, agent: "echo", cwd: process.cwd() })
  assert.equal(recovered.id, stickyId)
  await recovered.close({ mode: "shutdown" })
} finally {
  await core.close({ agents: "shutdown" })
  await rm(stateDirectory, { recursive: true, force: true })
}
