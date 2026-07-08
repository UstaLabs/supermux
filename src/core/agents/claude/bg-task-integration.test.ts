// src/core/agents/claude/bg-task-integration.test.ts
// End-to-end broker pipeline: raw transcript lines → tailer → detector → store →
// agent-state frame. Wired exactly like main.ts ensureClaudeTailer, fed the same
// real-fixture lines as bg-task-detector.test.ts, asserting the user-visible
// outcome: waiting appears while tasks are open and clears on the wake.
import { describe, expect, test } from "bun:test"
import { TranscriptTailer } from "./transcript-tailer"
import { BgTaskDetector } from "./bg-task-detector"
import { BackgroundTaskStore } from "../../session-manager/background-task-store"
import { AgentStateStore } from "../../session-manager/agent-state-store"
import { toAgentStateFrame } from "../../session-manager/agent-state-frame"

const TS = "2026-07-08T05:00:00.000Z"
const S = "session-1"

const lines = {
  bashToolUse: JSON.stringify({
    type: "assistant", timestamp: TS,
    message: { content: [{ type: "tool_use", id: "toolu_01AAA", name: "Bash",
      input: { command: "gradlew build", description: "Recompile Android Kotlin", run_in_background: true } }] },
  }),
  bashResult: JSON.stringify({
    type: "user", timestamp: TS,
    message: { content: [{ type: "tool_result", tool_use_id: "toolu_01AAA",
      content: "Command running in background with ID: bxcdg51aa. Output is being written to: /tmp/x/tasks/bxcdg51aa.output" }] },
  }),
  agentToolUse: JSON.stringify({
    type: "assistant", timestamp: TS,
    message: { content: [{ type: "tool_use", id: "toolu_01BBB", name: "Agent",
      input: { description: "Research WebKit quirks", prompt: "Find out about..." } }] },
  }),
  agentResult: JSON.stringify({
    type: "user", timestamp: TS,
    message: { content: [{ type: "tool_result", tool_use_id: "toolu_01BBB",
      content: [{ type: "text", text: "Async agent launched successfully.\nagentId: a2bee0eded79e862d (internal ID)" }] }] },
  }),
  failNotification: JSON.stringify({
    type: "user", timestamp: TS,
    message: { content: "<task-notification>\n<task-id>bxcdg51aa</task-id>\n<tool-use-id>toolu_01AAA</tool-use-id>\n<output-file>/tmp/x.output</output-file>\n<status>failed</status>\n<summary>Background command failed with exit code 1</summary>\n</task-notification>" },
  }),
  doneNotification: JSON.stringify({
    type: "user", timestamp: TS,
    message: { content: "<task-notification>\n<task-id>a2bee0eded79e862d</task-id>\n<tool-use-id>toolu_01BBB</tool-use-id>\n<output-file>/tmp/y.output</output-file>\n<status>completed</status>\n<summary>Agent finished</summary>\n</task-notification>" },
  }),
}

function pipeline() {
  const bgTaskStore = new BackgroundTaskStore()
  const agentStateStore = new AgentStateStore()
  const detector = new BgTaskDetector({
    onOpen: (t) => bgTaskStore.upsertOpen(S, t),
    onClose: (c) => bgTaskStore.close(S, c),
    onWake: () => agentStateStore.applyEvent(S, "turn-start"),
  })
  const tailer = new TranscriptTailer({
    path: "/nonexistent",
    onLine: (line) => detector.feedLine(line),
    onEvent: (event) => {
      if (event.kind === "interrupt") agentStateStore.applyEvent(S, "interrupt")
    },
  })
  const frame = () => toAgentStateFrame(S, agentStateStore.get(S), bgTaskStore.openCount(S))
  return { tailer, bgTaskStore, agentStateStore, frame }
}

describe("bg-task pipeline (tailer → detector → store → frame)", () => {
  test("turn with two bg launches ends waiting; wake flips to thinking and closes tasks", () => {
    const { tailer, bgTaskStore, agentStateStore, frame } = pipeline()

    // Turn runs: claude launches a bg shell + a subagent, then the turn ends.
    agentStateStore.applyEvent(S, "UserPromptSubmit")
    tailer.ingest(lines.bashToolUse + "\n" + lines.bashResult + "\n")
    tailer.ingest(lines.agentToolUse + "\n" + lines.agentResult + "\n")
    expect(bgTaskStore.openCount(S)).toBe(2)
    expect(frame().waiting).toBe(false)          // still mid-turn → working, not waiting

    agentStateStore.applyEvent(S, "Stop")
    const idle = frame()
    expect(idle.waiting).toBe(true)              // the headline behavior
    expect(idle.bgOpen).toBe(2)
    expect(idle.state).toBe("idle")              // legacy clients unchanged

    // Build fails → notification wakes claude.
    tailer.ingest(lines.failNotification + "\n")
    expect(bgTaskStore.openCount(S)).toBe(1)
    expect(agentStateStore.get(S).phase).toBe("thinking")   // wake reflected instantly
    const woken = frame()
    expect(woken.waiting).toBe(false)
    expect(woken.working).toBe(true)

    const tasks = bgTaskStore.get(S)
    expect(tasks.find((t) => t.id === "bxcdg51aa")).toMatchObject({
      status: "failed", label: "Recompile Android Kotlin", kind: "shell",
    })

    // Claude reacts, goes idle again — still waiting on the agent task.
    agentStateStore.applyEvent(S, "Stop")
    expect(frame().waiting).toBe(true)
    expect(frame().bgOpen).toBe(1)

    // Agent finishes → wake → close; after the final Stop nothing is open.
    tailer.ingest(lines.doneNotification + "\n")
    expect(bgTaskStore.openCount(S)).toBe(0)
    agentStateStore.applyEvent(S, "Stop")
    const done = frame()
    expect(done.waiting).toBe(false)
    expect(done.state).toBe("idle")
    expect(bgTaskStore.get(S).every((t) => t.status !== "running")).toBe(true)
  })

  test("partial-line chunking across reads does not break detection", () => {
    const { tailer, bgTaskStore } = pipeline()
    const full = lines.bashToolUse + "\n" + lines.bashResult + "\n"
    for (let i = 0; i < full.length; i += 40) tailer.ingest(full.slice(i, i + 40))
    expect(bgTaskStore.openCount(S)).toBe(1)
  })
})
