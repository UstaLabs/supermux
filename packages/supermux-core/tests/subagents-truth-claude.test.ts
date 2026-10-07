// Truth table (live capture 2026-10-03, /tmp/claude-1000/subagent-truth/claude): what Claude
// really allows per subagent state, and what the normalizer must therefore report.
import { describe, expect, test } from "bun:test"
import { createClaudeNormalizer, type ClaudeNormalizer } from "../src/claude/normalize.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import type { SubagentSnapshot } from "../src/types.js"
import { rows, subagentEvents, type SubagentBody } from "./subagent-fixtures.js"

const plumbing = new Set(["control_request", "control_response", "control_cancel_request", "keep_alive"])
const SUB1 = "afb1c518e747b0076" // completed, messaged (resumes)
const SUB2 = "a919e2e43e34b9721" // stopped by the CLIENT (stop_task) → Claude refuses messages
const SUB3 = "aa9ab13c6121ce36a" // stopped by the PARENT (TaskStop) → messaging resumes it

/** Replay a truth capture; client Stop markers do what the driver's stopSubagent does. */
function replay(name: string, normalizer: ClaudeNormalizer = createClaudeNormalizer()): NormalizedBody[] {
  const out: NormalizedBody[] = []
  for (const row of rows(name)) {
    if ((row.d as string) === "x") {
      if (row.m.action === "stop" && normalizer.subagent(row.m.subagentId)?.open) normalizer.markClientStop(row.m.subagentId)
      continue
    }
    if (row.d !== "a" || plumbing.has(row.m.type)) continue
    out.push(...normalizer({ protocol: "native", value: row.m }))
  }
  out.push(...normalizer.flush())
  return out
}

const terminal = (events: SubagentBody[]) => events.filter(e => e.phase === "completed" || e.phase === "failed" || e.phase === "cancelled")

/** The registry Core would have saved at the end of the main run (folded like Session does). */
function registry(events: NormalizedBody[]): SubagentSnapshot[] {
  const map = new Map<string, SubagentSnapshot>()
  for (const body of subagentEvents(events)) {
    const snap = map.get(body.subagentId) ?? { subagentId: body.subagentId, status: "running" as const, updatedAt: 0 }
    if (body.phase === "started" || body.phase === "resumed") {
      snap.status = "running"; delete snap.endedBy
      if (body.parentCallId) { snap.parentCallId = body.parentCallId; snap.spawnCallId ??= body.parentCallId }
    } else if (body.phase !== "progress") { snap.status = body.phase; if (body.endedBy) snap.endedBy = body.endedBy }
    for (const key of ["nativeId", "name", "description", "messaging", "actionsSource", "cannotMessageReason", "cannotStopReason"] as const) if (body[key]) (snap as any)[key] = body[key]
    if (typeof body.canMessage === "boolean") snap.canMessage = body.canMessage
    if (typeof body.canStop === "boolean") snap.canStop = body.canStop
    if (typeof body.background === "boolean") snap.background = body.background
    map.set(body.subagentId, snap)
  }
  return [...map.values()]
}

describe("claude subagent actions (truth table)", () => {
  const events = replay("claude-truth-main.ndjson")

  test("running: Message (relay, queued for the next tool round) and Stop", () => {
    const started = subagentEvents(events, SUB1)[0]!
    expect(started).toMatchObject({ phase: "started", canMessage: true, canStop: true, actionsSource: "derived" })
    expect(started.cannotMessageReason).toBeUndefined()
    // The relay to the running agent was queued by Claude: delivered.
    const delivered = subagentEvents(events, SUB1).filter(e => e.delivery)
    expect(delivered[0]!.delivery).toEqual({ status: "delivered" })
  })

  test("completed: endedBy self, still messageable (SendMessage resumes it), not stoppable", () => {
    const done = terminal(subagentEvents(events, SUB1))[0]!
    expect(done).toMatchObject({ phase: "completed", endedBy: "self", canMessage: true, canStop: false, cannotStopReason: "It has already finished" })
    const resumed = subagentEvents(events, SUB1).find(e => e.phase === "resumed")!
    expect(resumed).toMatchObject({ canMessage: true, canStop: true })
    expect(resumed.prompt).toContain("MESSAGE-COMPLETED")
  })

  test("stopped by the client: endedBy client, Message off with Claude's rule — and the refusal confirms it", () => {
    const sub = subagentEvents(events, SUB2)
    const stopped = terminal(sub)[0]!
    expect(stopped).toMatchObject({
      phase: "cancelled", endedBy: "client", canMessage: false, canStop: false,
      cannotMessageReason: "Stopped by you — Claude can't resume it",
    })
    // BUG 7: the relay that went out anyway is refused by SendMessage; that must surface.
    const refusal = sub.find(e => e.delivery?.status === "refused")!
    expect(refusal).toMatchObject({ phase: "progress", canMessage: false, delivery: { status: "refused", reason: "Stopped by you — Claude can't resume it" } })
    expect(sub.some(e => e.phase === "resumed")).toBe(false)
  })

  test("stopped by the parent (TaskStop): endedBy parent, messaging resumes it", () => {
    const sub = subagentEvents(events, SUB3)
    expect(terminal(sub)[0]).toMatchObject({ phase: "cancelled", endedBy: "parent", canMessage: true, canStop: false, cannotStopReason: "It has already stopped" })
    const resumed = sub.find(e => e.phase === "resumed")!
    expect(resumed.prompt).toContain("MESSAGE-AFTER-PARENT-CLOSE")
    expect(terminal(sub).at(-1)).toMatchObject({ phase: "completed", result: "KIWI2", endedBy: "self" })
  })

  test("after a restart with the remembered registry: resumed (not started), no phantom toolu_ subagent", () => {
    const saved = registry(events)
    const normalizer = createClaudeNormalizer()
    const restored = normalizer.restore(saved, { stillRunning: false })
    expect(restored.find(s => s.subagentId === SUB2)).toMatchObject({ status: "cancelled", endedBy: "client", canMessage: false })
    expect(restored.find(s => s.subagentId === SUB3)).toMatchObject({ status: "completed", canMessage: true, canStop: false })
    const after = replay("claude-truth-resume.ndjson", normalizer)
    const ids = new Set(subagentEvents(after).map(e => e.subagentId))
    expect([...ids].filter(id => id.startsWith("toolu_"))).toEqual([])
    const sub1 = subagentEvents(after, SUB1)
    expect(sub1[0]).toMatchObject({ phase: "resumed", canMessage: true, canStop: true })
    expect(after.filter(e => e.kind === "assistant-message" && e.subagentId === SUB1).map(e => (e as { text: string }).text).join(" ")).toContain("DONE-ONE")
    expect(subagentEvents(after, SUB3)[0]).toMatchObject({ phase: "resumed" })
    expect(after.some(e => e.kind === "assistant-message" && e.subagentId === SUB3 && (e as { text: string }).text === "PEAR")).toBe(true)
    // The client-stopped agent stays refused after the restart (Claude persists that too).
    expect(subagentEvents(after, SUB2).find(e => e.delivery)).toMatchObject({ canMessage: false, delivery: { status: "refused" } })
  })

  test("after a restart WITHOUT a registry: the SendMessage resume still reads as resumed and child frames find their agent", () => {
    const after = replay("claude-truth-resume.ndjson")
    const ids = new Set(subagentEvents(after).map(e => e.subagentId))
    expect([...ids].filter(id => id.startsWith("toolu_"))).toEqual([])
    expect(subagentEvents(after, SUB1)[0]).toMatchObject({ phase: "resumed" })
    expect(subagentEvents(after, SUB1).some(e => e.phase === "started")).toBe(false)
    expect(after.some(e => e.kind === "assistant-message" && e.subagentId === SUB3 && (e as { text: string }).text === "PEAR")).toBe(true)
  })

  test("a fresh process does not still run what the old one ran; a re-attached one does", () => {
    const saved: SubagentSnapshot[] = [{ subagentId: "a1", status: "running", spawnCallId: "toolu_x", updatedAt: 1 }]
    expect(createClaudeNormalizer().restore(saved, { stillRunning: false })[0]).toMatchObject({ status: "cancelled", canMessage: true, canStop: false })
    expect(createClaudeNormalizer().restore(saved, { stillRunning: true })[0]).toMatchObject({ status: "running", canMessage: true, canStop: true })
  })
})
