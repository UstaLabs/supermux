// Core's side of truthful subagent actions: the registry folded from subagent bodies, the
// refusal guard, relay delivery outcomes, persistence across a resume, and protocol labels.
import { afterEach, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext } from "../src/types.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  for (const core of cores.splice(0)) await core.close({ agents: "shutdown" }).catch(() => {})
  for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }).catch(() => {})
})

type Fake = { context?: DriverContext; stops: string[]; relays: string[] }

/** A runtime whose native frames ARE normalized bodies; prompts end immediately. */
function fakeDriver(fake: Fake, during?: (context: DriverContext) => void): AgentDriver {
  return {
    id: "fake",
    async open(context) {
      fake.context = context
      const runtime: AgentRuntime = {
        agentSessionId: "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        nativeProtocol: "claude-stream-json",
        ...(context.subagents ? { restoredSubagents: context.subagents.map(s => ({ ...s, status: s.status === "running" ? "cancelled" as const : s.status })) } : {}),
        normalize: update => update.protocol === "native" && (update.value as { body?: NormalizedBody }).body ? [(update.value as { body: NormalizedBody }).body] : [],
        async prompt() { during?.(context); return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
        async messageSubagent(id, content) { fake.relays.push(id); return { via: "relay", relay: content } },
        async stopSubagent(id) { fake.stops.push(id) },
      }
      return runtime
    },
  }
}

const sub = (body: Partial<Extract<NormalizedBody, { kind: "subagent" }>>) => ({ protocol: "native" as const, value: { type: "x", body: { kind: "subagent", subagentId: "s1", phase: "progress", ...body } } })
const text = (t: string) => [{ type: "text" as const, text: t }]

async function setup(during?: (context: DriverContext) => void) {
  const dir = await mkdtemp(join(tmpdir(), "subagent-session-"))
  dirs.push(dir)
  const fake: Fake = { stops: [], relays: [] }
  const core = createCore({ stateDirectory: dir, agents: [fakeDriver(fake, during)], limits: TEST_LIMITS })
  cores.push(core)
  const events: any[] = []
  core.subscribe((e: CoreEvent) => { if (e.type === "session.event") events.push(e.event) })
  const id = nextId("sub-")
  const session = await core.sessions.create({ id, agent: "fake", cwd: dir })
  return { dir, fake, core, events, session, id }
}

test("native events are labelled by the runtime's protocol (not codex-app-server)", async () => {
  const { fake, events } = await setup()
  fake.context!.onUpdate(sub({ phase: "started", canMessage: true, canStop: true, actionsSource: "derived" }))
  await new Promise(r => setTimeout(r, 10))
  expect(events.at(-1).native.protocol).toBe("claude-stream-json")
  fake.context!.onUpdate({ protocol: "native", value: { method: "supermux/subagent-state", body: { kind: "subagent", subagentId: "s1", phase: "progress" } } })
  await new Promise(r => setTimeout(r, 10))
  expect(events.at(-1).native.protocol).toBe("core")
})

test("the registry refuses what the agent said is impossible, with the agent's reason", async () => {
  const { fake, session } = await setup()
  fake.context!.onUpdate(sub({ phase: "started", name: "Anscombe", canMessage: true, canStop: true, actionsSource: "native" }))
  fake.context!.onUpdate(sub({ phase: "cancelled", endedBy: "client", canMessage: false, cannotMessageReason: "Stopped by you — Claude can't resume it", canStop: false, cannotStopReason: "It has already stopped" }))
  expect(session.subagents()[0]).toMatchObject({ subagentId: "s1", status: "cancelled", endedBy: "client", name: "Anscombe", canMessage: false, canStop: false })
  await expect(session.messageSubagent("s1", text("hi"))).rejects.toMatchObject({ code: "subagent_unavailable", message: "Stopped by you — Claude can't resume it" })
  await expect(session.stopSubagent("s1")).rejects.toMatchObject({ code: "subagent_unavailable", message: "It has already stopped" })
  expect(fake.relays).toEqual([])
  expect(fake.stops).toEqual([])
})

test("a relay settles with the parent's verdict: refused flips Message off", async () => {
  let refuse = false
  const { fake, session } = await setup(context => {
    if (refuse) context.onUpdate(sub({ canMessage: false, cannotMessageReason: "Stopped by you — Claude can't resume it", delivery: { status: "refused", reason: "Stopped by you — Claude can't resume it" } }))
  })
  fake.context!.onUpdate(sub({ phase: "completed", endedBy: "self", canMessage: true, canStop: false }))
  refuse = true
  const sent = await session.messageSubagent("s1", text("hi"))
  expect(sent.via).toBe("relay")
  expect(await sent.delivery).toEqual({ status: "refused", reason: "Stopped by you — Claude can't resume it" })
  expect(session.subagents()[0]).toMatchObject({ canMessage: false })
  await expect(session.messageSubagent("s1", text("again"))).rejects.toMatchObject({ code: "subagent_unavailable" })
})

test("a relay the parent never forwarded is unconfirmed, not delivered", async () => {
  const { fake, session } = await setup()
  fake.context!.onUpdate(sub({ phase: "completed", canMessage: true, canStop: false }))
  const sent = await session.messageSubagent("s1", text("hi"))
  expect(await sent.delivery).toMatchObject({ status: "unconfirmed" })
})

test("the registry is saved and handed back to the driver on resume", async () => {
  const { fake, core, session, id } = await setup()
  fake.context!.onUpdate(sub({ phase: "started", parentCallId: "toolu_spawn", nativeId: "child-session", description: "sleeper", canMessage: false, cannotMessageReason: "Grok can message it once it finishes", canStop: true, actionsSource: "derived" }))
  await new Promise(r => setTimeout(r, 50))
  await session.close({ mode: "shutdown" })
  const resumed = await core.sessions.resume(id)
  expect(fake.context!.subagents?.[0]).toMatchObject({ subagentId: "s1", status: "running", spawnCallId: "toolu_spawn", nativeId: "child-session", description: "sleeper" })
  // The driver's view of a fresh process wins (what ran in the old one is over).
  expect(resumed.subagents()[0]).toMatchObject({ subagentId: "s1", status: "cancelled" })
})
