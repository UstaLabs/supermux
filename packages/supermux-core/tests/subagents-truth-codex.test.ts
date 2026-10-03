// Truth table (live capture 2026-10-03, /tmp/claude-1000/subagent-truth/codex): Codex's own
// signals per subagent state, and what the normalizer reports from them.
import { describe, expect, test } from "bun:test"
import { createCodexNormalizer, type CodexNormalizer } from "../src/codex/normalize.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import { SUBAGENT_STATE_METHOD } from "../src/subagent-actions.js"
import { rows, subagentEvents, type SubagentBody } from "./subagent-fixtures.js"

const SUB1 = "01a1031e-5d5d-7281-8186-11b1834153dd" // "Hubble": completed, then messaged
const SUB2 = "01a1031f-70f1-7ac2-830d-ef4479e02511" // stopped by the client (turn/interrupt)
const SUB3 = "01a1031f-eb03-7b03-8a34-8b5156c330b2" // "Sartre": closed by the parent (close_agent)
/** What thread/read answered for the children (truth capture codex/thread-read.txt). */
const NICKNAMES: Record<string, string> = { [SUB1]: "Hubble", [SUB3]: "Sartre" }

function replay(name: string, normalizer: CodexNormalizer = createCodexNormalizer()): NormalizedBody[] {
  const out: NormalizedBody[] = []
  const read = new Set<string>()
  for (const row of rows(name)) {
    if ((row.d as string) === "x") {
      if (row.m.action === "stop" && normalizer.running(row.m.subagentId)) normalizer.markClientStop(row.m.subagentId)
      continue
    }
    if (row.d !== "a") continue
    out.push(...normalizer({ protocol: "native", value: row.m }))
    // The driver reads each new child once (thread/read) and re-emits its state.
    for (const body of out) {
      if (body.kind !== "subagent" || body.phase !== "started" || read.has(body.subagentId)) continue
      read.add(body.subagentId)
      normalizer.setNative(body.subagentId, { nickname: NICKNAMES[body.subagentId], canAcceptDirectInput: true })
      out.push(...normalizer({ protocol: "native", value: { method: SUBAGENT_STATE_METHOD, params: { subagentId: body.subagentId } } }))
    }
  }
  out.push(...normalizer.flush())
  return out
}

const terminal = (events: SubagentBody[]) => events.filter(e => e.phase === "completed" || e.phase === "failed" || e.phase === "cancelled")

describe("codex subagent actions (truth table)", () => {
  const events = replay("codex-truth-main.ndjson")

  test("spawned: the nickname arrives from thread/read; Stop follows the child's own running turn", () => {
    const sub = subagentEvents(events, SUB1)
    expect(sub[0]).toMatchObject({ phase: "started", canMessage: true })
    const named = sub.find(e => e.name === "Hubble")!
    expect(named).toMatchObject({ canMessage: true, actionsSource: "native" })
    expect(sub.some(e => e.phase !== "started" && e.canStop === true)).toBe(true)
    expect(terminal(sub)[0]).toMatchObject({ phase: "completed", endedBy: "self", name: "Hubble", canMessage: true, canStop: false })
  })

  test("client stop: interrupted turn → cancelled, endedBy client, still messageable", () => {
    const stopped = terminal(subagentEvents(events, SUB2))[0]!
    expect(stopped).toMatchObject({ phase: "cancelled", endedBy: "client", canMessage: true, canStop: false, cannotStopReason: "It has already stopped" })
    expect(subagentEvents(events, SUB2).find(e => e.phase === "resumed")).toMatchObject({ canStop: true })
  })

  test("parent close_agent: cancelled with endedBy parent (not the client), and Message stays on", () => {
    const sub = subagentEvents(events, SUB3)
    expect(terminal(sub)[0]).toMatchObject({ phase: "cancelled", endedBy: "parent", name: "Sartre", canMessage: true, canStop: false })
  })

  test("after a restart: remembered children keep their names and are messageable", () => {
    const normalizer = createCodexNormalizer()
    const restored = normalizer.restore([
      { subagentId: SUB3, status: "cancelled", endedBy: "parent", name: "Sartre", canMessage: true, actionsSource: "native", messaging: "direct", updatedAt: 1 },
    ], { stillRunning: false })
    expect(restored[0]).toMatchObject({ status: "cancelled", endedBy: "parent", name: "Sartre", canMessage: true, canStop: false, actionsSource: "native" })
    const after = replay("codex-truth-resume.ndjson", normalizer)
    const sub3 = subagentEvents(after, SUB3)
    expect(sub3[0]).toMatchObject({ phase: "resumed", name: "Sartre" })
    expect(terminal(sub3)[0]).toMatchObject({ phase: "completed", result: "PEAR", endedBy: "self" })
  })

  test("multi_agent v2 (real capture): Codex says no direct input → Message off, native", () => {
    const v2 = createCodexNormalizer()
    const out: NormalizedBody[] = []
    for (const row of rows("codex-single-v2.ndjson")) if (row.d === "a" && typeof row.m.method === "string") out.push(...v2({ protocol: "native", value: { method: row.m.method, params: row.m.params } }))
    const sub = subagentEvents(out)
    expect(sub.length).toBeGreaterThan(0)
    for (const body of sub) expect(body).toMatchObject({ canMessage: false, cannotMessageReason: "Codex doesn't accept messages for this subagent", actionsSource: "native" })
  })
})
