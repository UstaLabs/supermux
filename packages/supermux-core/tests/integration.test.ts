import { expect, test, setDefaultTimeout } from "bun:test"
import { TEST_LIMITS, nextId } from "./helpers.js"

// Real ACP subprocess: handshake + resume across two cores; explicit budget for loaded hosts.
setDefaultTimeout(30_000)
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { fileURLToPath } from "node:url"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import { acp } from "../src/acp/index.js"

test("public core lifecycle works through a real ACP subprocess", async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), "core-integration-"))
  const agent = acp({
    id: "fixture",
    command: process.execPath,
    args: [fileURLToPath(new URL("./fixtures/acp-agent.mjs", import.meta.url))],
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 5000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper: { stateDirectory, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } },
  })
  const core = createCore({
    limits: { ...TEST_LIMITS, interruptTimeoutMs: 5000 }, stateDirectory, agents: [agent] }) // real subprocess: 30 ms interrupt budget is a flake under load
  let next: ReturnType<typeof createCore> | undefined
  try {
    const session = await core.sessions.create({ id: nextId(), agent: "fixture", cwd: tmpdir() })
    const receipt = await session.send({ content: [{ type: "text", text: "hello" }], whenBusy: "queue" })
    expect(await receipt.completed).toEqual({ status: "completed", stopReason: "end_turn" })
    const active = await session.send({ content: [{ type: "text", text: "hang" }], whenBusy: "queue" })
    expect(await session.interrupt({ pending: "discard" })).toEqual({ status: "stopped" })
    expect(await active.completed).toEqual({ status: "cancelled" })
    await core.close({ agents: "shutdown" })
    next = createCore({
    limits: { ...TEST_LIMITS, interruptTimeoutMs: 5000 }, stateDirectory, agents: [agent] }) // real subprocess: 30 ms interrupt budget is a flake under load
    const resumed = await next.sessions.resume(session.id)
    expect(resumed.snapshot().agentSessionId).toBe(session.snapshot().agentSessionId)
    const second = await resumed.send({ content: [{ type: "text", text: "again" }], whenBusy: "queue" })
    expect((await second.completed).status).toBe("completed")
    await resumed.close({ mode: "shutdown" })
    await next.sessions.forget(session.id)
    expect(await next.sessions.list()).toEqual([])
  } finally {
    await core.close({ agents: "shutdown" })
    await next?.close({ agents: "shutdown" })
    await rm(stateDirectory, { recursive: true, force: true })
  }
})
