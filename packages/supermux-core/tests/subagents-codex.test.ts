import { describe, expect, test } from "bun:test"
import { createCodexNormalizer } from "../src/codex/normalize.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import { assertLifecycle, codexUpdates, mainText, run, subagentEvents, TERMINAL } from "./subagent-fixtures.js"

const replay = (name: string) => run(createCodexNormalizer(), codexUpdates(name))
const nonProgress = (events: ReturnType<typeof subagentEvents>) => events.map(e => e.phase).filter(p => p !== "progress")

describe("codex subagents (real captures)", () => {
  test("v1 single: spawnAgent starts it, child items are attributed, child turn end closes it", () => {
    const events = replay("codex-single.ndjson")
    const child = "01a0e787-c710-7bb0-bad2-9add4711b713"
    const sub = subagentEvents(events, child)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({
      phase: "started",
      parentCallId: "exec-ba7689ac-fa72-4411-a730-afafcdb308aa",
      model: "gpt-5.6-luna",
      messaging: "direct",
    })
    expect(sub[0]!.prompt).toContain("echo SUBAGENT_OK")
    const activities = sub.filter(e => e.phase === "progress").map(e => e.activity)
    expect(activities).toContain("Running pwd")
    expect(activities).toContain("Running echo SUBAGENT_OK")
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toHaveLength(1)
    expect(terminal[0]).toMatchObject({ phase: "completed" })
    expect(terminal[0]!.result).toContain("SUBAGENT_OK")
    expect(terminal[0]!.stats?.toolCalls).toBe(3)

    const commands = events.filter((e): e is Extract<NormalizedBody, { kind: "tool-call" }> => e.kind === "tool-call" && e.tool === "commandExecution")
    expect(commands.length).toBeGreaterThan(0)
    expect(commands.every(e => e.subagentId === child)).toBe(true)
    expect(events.filter(e => e.kind === "command-output").every(e => e.subagentId === child)).toBe(true)
    expect(events.some(e => e.kind === "assistant-message" && e.subagentId === child && e.text.includes("run the three commands"))).toBe(true)
    expect(mainText(events)).not.toContain("run the three commands")
    expect(mainText(events)).toContain("Reported:")
    expect(events.some(e => e.kind === "task")).toBe(false)
    // Child token usage is not the parent's usage.
    expect(events.filter(e => e.kind === "usage" && !e.subagentId).length).toBeGreaterThan(0)
  })

  test("v1 parallel: two children, results from their own final answers", () => {
    const events = replay("codex-parallel.ndjson")
    const alpha = "01a0e78a-e586-7e52-86ae-2d672d1f3782", beta = "01a0e78a-e8be-74d2-86df-f5262d37982c"
    for (const id of [alpha, beta]) {
      assertLifecycle(subagentEvents(events, id))
      expect(subagentEvents(events, id).filter(e => TERMINAL.has(e.phase))).toHaveLength(1)
    }
    expect(subagentEvents(events, alpha).at(-1)!.result).toContain("ALPHA")
    expect(subagentEvents(events, beta).at(-1)!.result).toContain("BETA")
    expect(mainText(events)).not.toContain("ALPHA  \n")
  })

  test("v1 direct messaging: a new child turn after completion is a resume", () => {
    const events = replay("codex-message.ndjson")
    const child = "01a0e789-3982-7f20-9a82-6a928de9c924"
    const sub = subagentEvents(events, child)
    assertLifecycle(sub)
    expect(nonProgress(sub)).toEqual(["started", "completed", "resumed", "completed"])
    expect(sub.filter(e => e.phase === "completed")[0]!.result).toContain("PINEAPPLE")
    expect(sub.at(-1)!.result).toBe("CHILD_DIRECT_OK")
    // Steered input shows up as the child's, never as parent text.
    expect(mainText(events)).not.toContain("CHILD_DIRECT_OK")
  })

  test("v2: subAgentActivity started/completed close by agentThreadId; not directly addressable", () => {
    const events = replay("codex-single-v2.ndjson")
    const child = "01a0e78a-c94a-7a30-9978-67e993ec02d1"
    const sub = subagentEvents(events, child)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({ phase: "started", name: "/root/shell_report", messaging: "none", parentCallId: "call_nW4n4ZwuHITowU06y4Cwc3SK" })
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toHaveLength(1)
    expect(terminal[0]!.result).toContain("SUBAGENT_OK")
    expect(events.filter(e => e.kind === "tool-call" && e.tool === "commandExecution").every(e => e.subagentId === child)).toBe(true)

    const v2 = replay("codex-message-v2.ndjson")
    const second = subagentEvents(v2, "01a0e790-c598-7dd3-af56-16eb515ae291")
    assertLifecycle(second)
    expect(second.filter(e => TERMINAL.has(e.phase))).toHaveLength(1)
    expect(second.at(-1)!.result).toContain("FINISHED")
  })

  test("child partial text flushed at turn end stays attributed", () => {
    const n = createCodexNormalizer()
    n({ protocol: "native", value: { method: "item/completed", params: { threadId: "main", turnId: "t", item: { type: "collabAgentToolCall", id: "c1", tool: "spawnAgent", status: "completed", receiverThreadIds: ["kid"], prompt: "p", model: "m", agentsStates: {} } } } })
    n({ protocol: "native", value: { method: "item/agentMessage/delta", params: { threadId: "kid", turnId: "k", itemId: "x", delta: "child words" } } })
    const flushed = n.flush()
    expect(flushed).toEqual([{ kind: "assistant-message", messageId: "x", text: "child words", subagentId: "kid" }])
  })
})
