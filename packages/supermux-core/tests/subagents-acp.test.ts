import { describe, expect, test } from "bun:test"
import { createAcpNormalizer } from "../src/acp/normalize.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import type { AgentUpdate } from "../src/types.js"
import { acpMainSession, assertLifecycle, mainText, rows, run, subagentEvents, TERMINAL, type Row } from "./subagent-fixtures.js"

/** Drop what a client-side session/load replays (history, not live activity) and everything after `stopAt`. */
function liveRows(name: string, stopAt?: (row: Row) => boolean): Row[] {
  const out: Row[] = []
  let loading: unknown
  for (const row of rows(name)) {
    if (stopAt?.(row)) break
    if (row.d === "c" && row.m.method === "session/load") { loading = row.m.id; continue }
    if (loading !== undefined) {
      if (row.d === "a" && row.m.id === loading && !row.m.method) loading = undefined
      continue
    }
    out.push(row)
  }
  return out
}

function updates(list: Row[]): AgentUpdate[] {
  const out: AgentUpdate[] = []
  for (const r of list) {
    if (r.d !== "a" || typeof r.m.method !== "string") continue
    if (r.m.method === "session/update") out.push({ protocol: "acp", value: r.m.params.update, sessionId: r.m.params.sessionId })
    else out.push({ protocol: "native", value: { method: r.m.method, params: r.m.params } })
  }
  return out
}

function normalizer(name: string, vendor: "grok" | "cursor" | "opencode", extra: { cursorSubagents?: boolean } = {}) {
  const main = acpMainSession(name)
  return createAcpNormalizer({ vendor, mainSessionId: () => main, ...(extra.cursorSubagents !== undefined ? { cursorSubagents: () => extra.cursorSubagents! } : {}) })
}

const toolCalls = (events: NormalizedBody[]) => events.filter((e): e is Extract<NormalizedBody, { kind: "tool-call" }> => e.kind === "tool-call")
const textOf = (events: NormalizedBody[], id: string) => events.filter(e => e.kind === "assistant-message" && e.subagentId === id).map(e => (e as { text: string }).text).join("\n")

describe("grok subagents (real captures)", () => {
  test("single: vendor notifications drive the lifecycle; child session frames are attributed", () => {
    const n = normalizer("grok-single.ndjson", "grok")
    const events = run(n, updates(liveRows("grok-single.ndjson")))
    const child = "01a0e78f-8d47-7243-b77c-ea742b4b43f4"
    const sub = subagentEvents(events, child)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({
      phase: "started",
      parentCallId: "call-d3bdc9c2-287c-4173-86da-c9e2556d393f-0",
      name: "general-purpose",
      description: "Read files and count lines",
      model: "grok-4.7-build-fast",
      background: true,
      messaging: "direct",
    })
    expect(sub[0]!.prompt).toContain("alpha.txt")
    expect(sub.some(e => e.phase === "progress" && e.stats?.toolCalls === 2)).toBe(true)
    expect(sub.some(e => e.phase === "progress" && !!e.activity)).toBe(true)
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toHaveLength(1)
    expect(terminal[0]).toMatchObject({ phase: "completed", stats: { toolCalls: 3, turns: 1 } })
    expect(terminal[0]!.result).toContain("alpha.txt")
    const childCalls = toolCalls(events).filter(e => e.subagentId === child)
    expect(childCalls.length).toBeGreaterThan(0)
    expect(toolCalls(events).find(e => e.tool === "spawn_subagent")?.subagentId).toBeUndefined()
    expect(textOf(events, child).length).toBeGreaterThan(0)
    for (const line of textOf(events, child).split("\n").filter(l => l.length > 20)) expect(mainText(events)).not.toContain(line)
    // Child message ids never collide with the parent's.
    const mainIds = new Set(events.filter(e => e.kind === "assistant-message" && !e.subagentId).map(e => (e as { messageId: string }).messageId))
    for (const e of events) if (e.kind === "assistant-message" && e.subagentId) expect(mainIds.has(e.messageId)).toBe(false)
  })

  test("parallel: two children keep separate text; resume_from reopens the same subagent", () => {
    const name = "grok-parallel.ndjson"
    const n = normalizer(name, "grok")
    const events = run(n, updates(liveRows(name)))
    const a = "01a0e790-e6c5-7363-bf8f-9e59b501eb3f", b = "01a0e790-e6c6-7a03-a2fe-e2f14c5aeb8d"
    for (const id of [a, b]) assertLifecycle(subagentEvents(events, id))
    expect(textOf(events, a)).not.toContain("beta one")
    expect(textOf(events, b)).not.toContain("alpha line1")
    const subA = subagentEvents(events, a)
    expect(subA.map(e => e.phase).filter(p => p !== "progress")).toEqual(["started", "completed", "resumed", "completed"])
    expect(subA.find(e => e.phase === "resumed")).toMatchObject({ nativeId: "01a0e791-284f-7c63-aea0-13e35c5bce5c", prompt: "What was the second line of the first file you read?" })
    expect(subA.at(-1)!.result).toContain("alpha line2")
    expect(subagentEvents(events, "01a0e791-284f-7c63-aea0-13e35c5bce5c")).toHaveLength(0)
  })

  test("a child's tool call id maps back to its subagent (permission attribution)", () => {
    const name = "grok-direct.ndjson"
    const n = normalizer(name, "grok")
    run(n, updates(liveRows(name, r => r.d === "c" && r.m.method === "session/load")))
    expect(n.subagentForTool("call-f15e4c01-bd86-4b2a-8434-9420652687c6-0")).toBe("01a0e79c-0dc6-7c11-be7e-6f610217410c")
    expect(n.subagentForTool("call-b25d3bcb-d620-49bf-afb3-ef0867546fa5-0")).toBeUndefined()
  })
})

describe("opencode subagents (real captures)", () => {
  test("single: task tool call is the subagent; result from <task_result>, child session as nativeId", () => {
    const n = normalizer("opencode-single.ndjson", "opencode")
    const events = run(n, updates(liveRows("opencode-single.ndjson")))
    const id = "toolu_3ac969404e154c51b3b4a251"
    const sub = subagentEvents(events, id)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({ phase: "started", parentCallId: id, name: "general", description: "Read files and report line counts", messaging: "direct" })
    expect(sub[0]!.prompt).toContain("read alpha.txt")
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toEqual([expect.objectContaining({ phase: "completed", nativeId: "ses_f186edfc0ffe26P3snFAok3fWN" })])
    expect(terminal[0]!.result).toBe("- alpha.txt: 2 lines\n- beta.txt: 3 lines\n- Files in directory: alpha.txt, beta.txt, gamma.txt")
    expect(n.subagent(id)?.nativeId).toBe("ses_f186edfc0ffe26P3snFAok3fWN")
  })

  test("parallel + task_id resume reopens the first subagent; direct child frames map by child session", () => {
    const name = "opencode-parallel.ndjson"
    const n = normalizer(name, "opencode")
    const events = run(n, updates(liveRows(name)))
    const first = "toolu_8d7699c34b0c40d5adbdd7dc"
    const sub = subagentEvents(events, first)
    assertLifecycle(sub)
    expect(sub.map(e => e.phase).filter(p => p !== "progress")).toEqual(["started", "completed", "resumed", "completed"])
    expect(sub.find(e => e.phase === "resumed")).toMatchObject({ parentCallId: "toolu_a75c704de1904a4e9725b297" })
    expect(subagentEvents(events, "toolu_a75c704de1904a4e9725b297")).toHaveLength(0)
    // The direct prompt to the child session streamed under its own session id.
    expect(events.some(e => e.kind === "assistant-delta" && e.subagentId === first)).toBe(true)
  })
})

describe("cursor subagents (real captures)", () => {
  test("with the subagents capability: spawned/state_update + child stream + cursor/task", () => {
    const name = "cursor-single.ndjson"
    const n = normalizer(name, "cursor", { cursorSubagents: true })
    const events = run(n, updates(liveRows(name)))
    const id = "462f4e89-c491-4083-a70f-74cf3fb5cc6d"
    const sub = subagentEvents(events, id)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({ phase: "started", name: "generalPurpose", model: "default", messaging: "relay", parentCallId: "call-30734ab2-41e0-49a2-8110-445be971455f-0\nfc_4711f528-a9f5-9f35-aeec-330b38ca6d3e_0" })
    expect(sub.some(e => e.description === "Read files, report counts")).toBe(true)
    expect(sub.some(e => e.phase === "progress" && e.activity === "Read alpha.txt")).toBe(true)
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toHaveLength(1)
    expect(terminal[0]).toMatchObject({ phase: "completed", stats: { durationMs: 19891 } })
    expect(terminal[0]!.result).toContain("alpha.txt")
    expect(toolCalls(events).filter(e => e.subagentId === id).length).toBeGreaterThanOrEqual(3)
    expect(mainText(events)).not.toContain("**Summary**")
  })

  test("parallel children do not merge; Task resume (<id>.2) is a resume of the same subagent", () => {
    const name = "cursor-parallel.ndjson"
    const n = normalizer(name, "cursor", { cursorSubagents: true })
    const events = run(n, updates(liveRows(name)))
    const a = "f061c95f-03ac-4e52-82c3-50d89900860d", b = "8d7fcd3c-dc3a-4bfc-b067-567d6af92f6f"
    for (const id of [a, b]) assertLifecycle(subagentEvents(events, id))
    expect(textOf(events, a)).not.toContain("beta")
    const subA = subagentEvents(events, a)
    expect(subA.map(e => e.phase).filter(p => p !== "progress")).toEqual(["started", "completed", "resumed", "completed"])
    expect(subA.find(e => e.phase === "resumed")).toMatchObject({ nativeId: `${a}.2`, prompt: "What was the second line of alpha.txt?" })
    expect(subagentEvents(events, `${a}.2`)).toHaveLength(0)
  })

  test("baseline (no capability): the Task call is the subagent, finished by cursor/task", () => {
    const name = "cursor-baseline.ndjson"
    const n = normalizer(name, "cursor", { cursorSubagents: false })
    const events = run(n, updates(liveRows(name)))
    const id = "call-12a9bd3f-f585-488d-a37d-851c15980e55-0\nfc_1de06a53-13f1-96b0-8433-f783c17490bc_0"
    const sub = subagentEvents(events, id)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({ phase: "started", description: "Read files, report counts", messaging: "relay", parentCallId: id })
    expect(sub.filter(e => TERMINAL.has(e.phase))).toEqual([expect.objectContaining({ phase: "completed", stats: { durationMs: 31979 } })])
  })
})
