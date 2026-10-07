import { describe, expect, test } from "bun:test"
import { createClaudeNormalizer } from "../src/claude/normalize.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import { assertLifecycle, claudeUpdates, mainText, run, subagentEvents, TERMINAL } from "./subagent-fixtures.js"

const replay = (name: string) => run(createClaudeNormalizer(), claudeUpdates(name))
const toolCalls = (events: NormalizedBody[]) => events.filter((e): e is Extract<NormalizedBody, { kind: "tool-call" }> => e.kind === "tool-call")

describe("claude subagents (real captures)", () => {
  test("single foreground subagent: lifecycle, attribution, result, one final text", () => {
    const events = replay("claude-single.ndjson")
    const id = "af3c70a348a6a6b7a"
    const sub = subagentEvents(events, id)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({
      phase: "started",
      parentCallId: "toolu_01FY1DZSocZ1MNN2Rp2jmdfj",
      name: "general-purpose",
      description: "Inspect work dir",
      background: false,
      messaging: "relay",
    })
    expect(sub[0]!.prompt).toContain("ls -R")
    const activities = sub.filter(e => e.phase === "progress").map(e => e.activity)
    expect(activities).toContain("Running List all files and directories recursively")
    expect(activities).toContain("Reading sub/secret.txt")
    expect(sub.find(e => e.activity === "Reading sub/secret.txt")?.stats).toMatchObject({ toolCalls: 2, tokens: 13031, durationMs: 4386 })
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toHaveLength(1)
    expect(terminal[0]).toMatchObject({ phase: "completed" })
    expect(terminal[0]!.result).toContain("PELICAN")

    // Child tool calls carry the subagent id; the Agent call itself is main-thread.
    const bash = toolCalls(events).filter(e => e.tool === "Bash")
    expect(bash.length).toBeGreaterThan(0)
    for (const call of bash) expect(call.subagentId).toBe(id)
    expect(toolCalls(events).filter(e => e.tool === "Read").every(e => e.subagentId === id)).toBe(true)
    expect(toolCalls(events).filter(e => e.tool === "Agent").every(e => e.subagentId === undefined)).toBe(true)
    expect(events.some(e => e.kind === "command-output" && e.subagentId === id)).toBe(true)
    expect(events.some(e => e.kind === "task")).toBe(false)

    // Duplicate-final fix: the streamed "PELICAN" block yields exactly one final message.
    const finals = events.filter(e => e.kind === "assistant-message" && !e.subagentId)
    expect(finals.filter(e => (e as { text: string }).text === "PELICAN")).toHaveLength(1)
    const deltaIds = new Set(events.filter(e => e.kind === "assistant-delta").map(e => (e as { messageId: string }).messageId))
    for (const final of finals) expect(deltaIds.size === 0 || deltaIds.has((final as { messageId: string }).messageId)).toBe(true)
  })

  test("parallel subagents keep their own tool calls", () => {
    const events = replay("claude-parallel.ndjson")
    const notes = "ada0a21fd61790186", config = "a80e35b728cf172b1"
    for (const id of [notes, config]) {
      const sub = subagentEvents(events, id)
      assertLifecycle(sub)
      expect(sub.filter(e => TERMINAL.has(e.phase))).toHaveLength(1)
    }
    expect(subagentEvents(events, notes)[0]).toMatchObject({ description: "Read notes file" })
    expect(toolCalls(events).find(e => e.tool === "Read" && e.phase === "started")?.subagentId).toBe(notes)
    expect(toolCalls(events).find(e => e.tool === "Bash" && e.phase === "started")?.subagentId).toBe(config)
    expect(subagentEvents(events, config).at(-1)?.result).toContain("apple")
  })

  test("background agent and background bash; child text never reaches the main thread", () => {
    const events = replay("claude-background.ndjson")
    const agent = "a671a6d5bcca52ff1"
    const sub = subagentEvents(events, agent)
    assertLifecycle(sub)
    expect(sub[0]).toMatchObject({ background: true, description: "Background secret reader" })
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal).toHaveLength(1)
    expect(terminal[0]!.result).toContain("PELICAN")
    // Child text is a subagent-attributed message, not parent text.
    expect(events.some(e => e.kind === "assistant-message" && e.subagentId === agent && e.text.includes("Done. The secret word"))).toBe(true)
    expect(mainText(events)).not.toContain("Done. The secret word")
    expect(mainText(events)).toContain("launched")
    expect(mainText(events)).toContain("Agent completed")

    const bash = events.filter((e): e is Extract<NormalizedBody, { kind: "task" }> => e.kind === "task" && e.taskId === "b3153wm3n")
    expect(bash.map(e => e.phase)).toEqual(["started", "completed"])
    expect(bash[0]).toMatchObject({ taskKind: "shell", label: "Sleep then print marker", parentCallId: "toolu_01SfgRF7HwaV2Ap7LXohjnLz" })
    expect(events.some(e => e.kind === "task" && (e.taskKind === "agent" || e.taskKind === "subagent"))).toBe(false)
  })

  test("SendMessage resumes a finished subagent under the same id", () => {
    const events = replay("claude-resume.ndjson")
    const id = "a47ce4c320c9a4f07"
    const sub = subagentEvents(events, id)
    assertLifecycle(sub)
    expect(sub.map(e => e.phase).filter(p => p !== "progress")).toEqual(["started", "completed", "resumed", "completed"])
    const resumed = sub.find(e => e.phase === "resumed")!
    expect(resumed).toMatchObject({ parentCallId: "toolu_01MHfRMzhNHyXkKZXJPUtrdb", prompt: "Now also Read notes.txt and report its first line." })
    expect(sub.at(-1)!.result).toContain("alpha")
    // The resumed child's Read is still attributed (its frames reuse the original Agent call id).
    expect(toolCalls(events).find(e => e.tool === "Read" && JSON.stringify(e.input ?? "").includes("notes.txt"))?.subagentId).toBe(id)
    expect(mainText(events)).not.toContain("To summarize both findings")
  })

  test("stopped subagent is cancelled exactly once; backgrounding is a progress", () => {
    const events = replay("claude-stop.ndjson")
    const sub = subagentEvents(events, "a8cf04e1a7620a931")
    assertLifecycle(sub)
    expect(sub.some(e => e.phase === "progress" && e.background === true)).toBe(true)
    const terminal = sub.filter(e => TERMINAL.has(e.phase))
    expect(terminal.map(e => e.phase)).toEqual(["cancelled"])
  })

  test("subagent file edits carry subagentId", () => {
    const events = replay("claude-permissions.ndjson")
    const id = "a8cc44fde3a4f1b0a"
    assertLifecycle(subagentEvents(events, id))
    const diffs = events.filter(e => e.kind === "file-diff")
    expect(diffs.length).toBeGreaterThan(0)
    for (const diff of diffs) expect(diff.subagentId).toBe(id)
    expect(toolCalls(events).filter(e => e.tool === "Write" || e.tool === "Edit").every(e => e.subagentId === id)).toBe(true)
  })

  test("child frame before task_started falls back to the tool_use id consistently", () => {
    const n = createClaudeNormalizer()
    const out: NormalizedBody[] = []
    out.push(...n({ protocol: "native", value: { type: "assistant", parent_tool_use_id: "toolu_X", message: { id: "c1", content: [{ type: "tool_use", id: "t1", name: "Read", input: { file_path: "a" } }] } } }))
    out.push(...n({ protocol: "native", value: { type: "system", subtype: "task_started", task_id: "task9", tool_use_id: "toolu_X", description: "d", subagent_type: "general-purpose", task_type: "local_agent", prompt: "p" } }))
    out.push(...n({ protocol: "native", value: { type: "user", parent_tool_use_id: "toolu_X", message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "x" }] } } }))
    out.push(...n({ protocol: "native", value: { type: "system", subtype: "task_notification", task_id: "task9", tool_use_id: "toolu_X", status: "completed", summary: "ok" } }))
    const ids = new Set(out.filter(e => e.subagentId).map(e => e.subagentId))
    expect([...ids]).toEqual(["toolu_X"])
    const sub = subagentEvents(out)
    assertLifecycle(sub)
    expect(sub.at(-1)).toMatchObject({ phase: "completed", result: "ok", nativeId: "task9" })
  })
})
