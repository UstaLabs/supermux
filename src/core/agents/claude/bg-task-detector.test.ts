// src/core/agents/claude/bg-task-detector.test.ts
// Fixtures are faithful reductions of real transcript lines captured 2026-07-08.
import { describe, expect, test } from "bun:test"
import { BgTaskDetector } from "./bg-task-detector"
import type { BgTaskOpen, BgTaskClose } from "../../session-manager/background-task-store"

function harness() {
  const opens: BgTaskOpen[] = []
  const closes: BgTaskClose[] = []
  let wakes = 0
  const d = new BgTaskDetector({
    onOpen: (t) => opens.push(t),
    onClose: (c) => closes.push(c),
    onWake: () => wakes++,
  })
  return { d, opens, closes, wakes: () => wakes }
}

const TS = "2026-07-08T05:00:00.000Z"
const TS_MS = Date.parse(TS)

const bashToolUse = JSON.stringify({
  type: "assistant", timestamp: TS,
  message: { content: [{ type: "tool_use", id: "toolu_01AAA", name: "Bash",
    input: { command: "gradlew :android:compileDebugKotlin\nmore", description: "Recompile Android Kotlin", run_in_background: true } }] },
})
const bashToolUseNoDesc = JSON.stringify({
  type: "assistant", timestamp: TS,
  message: { content: [{ type: "tool_use", id: "toolu_01AAA", name: "Bash",
    input: { command: "gradlew :android:compileDebugKotlin\nmore", run_in_background: true } }] },
})
const bashResult = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: [{ type: "tool_result", tool_use_id: "toolu_01AAA",
    content: "Command running in background with ID: bxcdg51aa. Output is being written to: /tmp/x/tasks/bxcdg51aa.output" }] },
})
const agentToolUse = JSON.stringify({
  type: "assistant", timestamp: TS,
  message: { content: [{ type: "tool_use", id: "toolu_01BBB", name: "Agent",
    input: { description: "Research WebKit quirks", prompt: "Find out about..." } }] },
})
const agentResult = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: [{ type: "tool_result", tool_use_id: "toolu_01BBB",
    content: [{ type: "text", text: "Async agent launched successfully.\nagentId: a2bee0eded79e862d (internal ID - do not mention to user)" }] }] },
})
const failNotification = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: "<task-notification>\n<task-id>bxcdg51aa</task-id>\n<tool-use-id>toolu_01AAA</tool-use-id>\n<output-file>/tmp/x/tasks/bxcdg51aa.output</output-file>\n<status>failed</status>\n<summary>Background command \"Recompile Android Kotlin\" failed with exit code 1</summary>\n</task-notification>" },
})
const doneNotification = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: "<task-notification>\n<task-id>a2bee0eded79e862d</task-id>\n<tool-use-id>toolu_01BBB</tool-use-id>\n<output-file>/tmp/x/tasks/a2bee0eded79e862d.output</output-file>\n<status>completed</status>\n<summary>Agent finished</summary>\n</task-notification>" },
})

describe("BgTaskDetector", () => {
  test("opens a shell task with label from the paired tool_use description", () => {
    const { d, opens } = harness()
    d.feedLine(bashToolUse)
    d.feedLine(bashResult)
    expect(opens).toEqual([{ id: "bxcdg51aa", kind: "shell", label: "Recompile Android Kotlin", ts: TS_MS, callId: "toolu_01AAA" }])
  })

  test("falls back to first command line when Bash has no description", () => {
    const { d, opens } = harness()
    d.feedLine(bashToolUseNoDesc)
    d.feedLine(bashResult)
    expect(opens[0]!.label).toBe("gradlew :android:compileDebugKotlin")
  })

  test("opens an agent task from the Async-agent-launched result", () => {
    const { d, opens } = harness()
    d.feedLine(agentToolUse)
    d.feedLine(agentResult)
    expect(opens).toEqual([{ id: "a2bee0eded79e862d", kind: "agent", label: "Research WebKit quirks", ts: TS_MS, callId: "toolu_01BBB" }])
  })

  test("unmatched tool_result without pending tool_use still opens with id as label", () => {
    const { d, opens } = harness()
    d.feedLine(bashResult)
    expect(opens).toEqual([{ id: "bxcdg51aa", kind: "shell", label: "bxcdg51aa", ts: TS_MS, callId: "toolu_01AAA" }])
  })

  test("task-notification closes with status + summary and emits wake", () => {
    const { d, closes, wakes } = harness()
    d.feedLine(failNotification)
    d.feedLine(doneNotification)
    expect(closes).toEqual([
      { id: "bxcdg51aa", status: "failed", summary: 'Background command "Recompile Android Kotlin" failed with exit code 1', ts: TS_MS },
      { id: "a2bee0eded79e862d", status: "completed", summary: "Agent finished", ts: TS_MS },
    ])
    expect(wakes()).toBe(2)
  })

  test("garbage and irrelevant lines are ignored", () => {
    const { d, opens, closes, wakes } = harness()
    d.feedLine("not json")
    d.feedLine(JSON.stringify({ type: "assistant", message: { content: [{ type: "text", text: "hello" }] } }))
    d.feedLine(JSON.stringify({ type: "user", message: { content: "plain reply" } }))
    expect(opens).toHaveLength(0)
    expect(closes).toHaveLength(0)
    expect(wakes()).toBe(0)
  })
})
