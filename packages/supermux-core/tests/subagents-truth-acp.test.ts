// Truth table (live capture 2026-10-03, /tmp/claude-1000/subagent-truth/{grok,opencode,cursor}):
// each ACP agent's own signals per subagent state, and what the normalizer reports from them.
import { describe, expect, test } from "bun:test"
import { createAcpNormalizer, type AcpNormalizer, type AcpVendor } from "../src/acp/normalize.js"
import { createGrokClassifyActivity } from "../src/agents/index.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import type { AgentUpdate } from "../src/types.js"
import { rows, subagentEvents, type SubagentBody } from "./subagent-fixtures.js"

const MAIN: Record<AcpVendor, string> = {
  grok: "01a10321-76e7-7fe0-9723-6fc707c57072",
  opencode: "ses_efce199c8ffeN62dgDM46AdxHx",
  cursor: "84255af2-e57b-4447-8f27-c42767be81db",
}

function updates(name: string): (AgentUpdate | { stop: string })[] {
  const out: (AgentUpdate | { stop: string })[] = []
  for (const row of rows(name)) {
    if ((row.d as string) === "x") { if (row.m.action === "stop") out.push({ stop: row.m.subagentId }); continue }
    if (row.d !== "a" || typeof row.m.method !== "string") continue
    if (row.m.method === "session/update") out.push({ protocol: "acp", value: row.m.params.update, sessionId: row.m.params.sessionId })
    else out.push({ protocol: "native", value: { method: row.m.method, params: row.m.params } })
  }
  return out
}

function replay(vendor: AcpVendor, name: string, normalizer?: AcpNormalizer): NormalizedBody[] {
  const n = normalizer ?? createAcpNormalizer({ vendor, mainSessionId: () => MAIN[vendor], cursorSubagents: () => true })
  const out: NormalizedBody[] = []
  for (const update of updates(name)) {
    if ("stop" in update) { if (n.subagent(update.stop)?.open) n.markStopRequested(update.stop); continue }
    out.push(...n(update))
  }
  out.push(...n.flush())
  return out
}

const terminal = (events: SubagentBody[]) => events.filter(e => e.phase === "completed" || e.phase === "failed" || e.phase === "cancelled")

describe("grok subagent actions (truth table, grok 1.0.46)", () => {
  const events = replay("grok", "grok-truth-main.ndjson")
  const SUB1 = "01a10321-808f-7b30-ba27-5be1d1cae8fb"
  const SUB2 = "01a10322-5c71-7f60-8cf5-b9929770d502"
  const SUB3 = "01a10323-d9f6-7683-8452-9ecfad38615a"

  test("BUG 3: a RUNNING child is not messageable (loading it mid-run breaks it); Stop is offered", () => {
    expect(subagentEvents(events, SUB1)[0]).toMatchObject({
      phase: "started", canMessage: false, cannotMessageReason: "Grok can message it once it finishes", canStop: true, actionsSource: "derived",
    })
  })

  test("finished: messageable (load + prompt), not stoppable; endedBy self", () => {
    expect(terminal(subagentEvents(events, SUB1))[0]).toMatchObject({ phase: "completed", endedBy: "self", canMessage: true, canStop: false, cannotStopReason: "It has already finished" })
  })

  test("a direct message run: busy while it answers, then messageable again", () => {
    const sub = subagentEvents(events, SUB2)
    expect(sub.find(e => e.phase === "resumed")).toMatchObject({ canMessage: false, cannotMessageReason: "It is still answering your last message", canStop: true })
    expect(terminal(sub).at(-1)).toMatchObject({ phase: "completed", result: "KIWI", canMessage: true })
  })

  test("parent kill_command_or_subagent: cancelled with endedBy parent, still messageable", () => {
    expect(terminal(subagentEvents(events, SUB3))[0]).toMatchObject({ phase: "cancelled", endedBy: "parent", canMessage: true, canStop: false })
  })

  test("BUG 4: a restored registry keeps children addressable after a resume", () => {
    const n = createAcpNormalizer({ vendor: "grok", mainSessionId: () => MAIN.grok })
    const restored = n.restore([{ subagentId: SUB1, status: "completed", endedBy: "self", description: "sleeper one", messaging: "direct", updatedAt: 1 }], { stillRunning: false })
    expect(restored[0]).toMatchObject({ status: "completed", canMessage: true, canStop: false })
    expect(n.subagent(SUB1)).toMatchObject({ id: SUB1, open: false })
  })

  test("BUG 5: Grok's wake turn after a background finish is a real turn (start + its own turn_completed)", () => {
    const classify = createGrokClassifyActivity()
    const hints = updates("grok-truth-main.ndjson").filter((u): u is AgentUpdate => !("stop" in u))
      .filter(u => u.protocol === "native" || !u.sessionId || u.sessionId === MAIN.grok)
      .map(u => classify(u)).filter(Boolean)
    const wake = `subagent-completed-${SUB1}`
    const started = hints.findIndex(h => h!.phase === "started" && h!.id === wake)
    const completed = hints.findIndex(h => h!.phase === "completed" && h!.id === wake)
    expect(started).toBeGreaterThanOrEqual(0)
    expect(completed).toBeGreaterThan(started)
  })
})

describe("opencode subagent actions (truth table)", () => {
  const events = replay("opencode", "opencode-truth-main.ndjson")
  const SUB1 = "toolu_3fbb511532f54a5c84d79b37"
  const SUB2 = "toolu_f6d79fbb3a4b4b308823284f"

  test("running: Message and Stop once OpenCode reported the child session", () => {
    const sub = subagentEvents(events, SUB1)
    expect(sub[0]).toMatchObject({ phase: "started", canMessage: false, cannotMessageReason: "OpenCode hasn't reported its session yet" })
    expect(sub.find(e => e.nativeId)).toMatchObject({ canMessage: true, canStop: true })
    expect(terminal(sub)[0]).toMatchObject({ phase: "completed", endedBy: "self", canMessage: true, canStop: false })
  })

  test("client stop: cancelled endedBy client, still messageable; BUG 8: the child's abort is the subagent's error, not the main thread's", () => {
    const sub = subagentEvents(events, SUB2)
    expect(terminal(sub)[0]).toMatchObject({ phase: "cancelled", endedBy: "client", canMessage: true, canStop: false })
    const errors = events.filter(e => e.kind === "error")
    expect(errors.find(e => (e as { message: string }).message === "Aborted process")?.subagentId).toBe(SUB2)
    expect(errors.filter(e => !e.subagentId)).toEqual([])
  })
})

describe("cursor subagent actions (truth table)", () => {
  const events = replay("cursor", "cursor-truth-main.ndjson")
  const SUB1 = "8f11f2cb-5cdf-45b2-9352-c191ad89d904"

  test("relay Message always (derived); Stop never (Cursor offers no cancel)", () => {
    for (const body of subagentEvents(events, SUB1)) {
      expect(body).toMatchObject({ canMessage: true, canStop: false, cannotStopReason: "Cursor can't stop subagents", actionsSource: "derived" })
    }
  })

  test("after a restart, a relayed Task resume reads as resumed, not started", () => {
    const n = createAcpNormalizer({ vendor: "cursor", mainSessionId: () => MAIN.cursor, cursorSubagents: () => true })
    n.restore([{ subagentId: SUB1, status: "completed", endedBy: "self", messaging: "relay", name: "generalPurpose", updatedAt: 1 }], { stillRunning: false })
    const after = replay("cursor", "cursor-truth-resume.ndjson", n)
    const sub = subagentEvents(after, SUB1)
    expect(sub[0]).toMatchObject({ phase: "resumed" })
    expect(sub.some(e => e.phase === "started")).toBe(false)
    expect(terminal(sub).at(-1)).toMatchObject({ phase: "completed", result: "LEMON" })
  })
})
