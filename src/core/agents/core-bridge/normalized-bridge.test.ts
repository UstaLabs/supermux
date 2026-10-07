import { test, expect } from "bun:test"
import { createNormalizedBridge } from "./normalized-bridge"
import type { AgentEvent } from "../types"
import type { CoreNormalizedEvent } from "./normalized-bridge"

function env(body: Record<string, unknown>): CoreNormalizedEvent {
  return {
    sessionId: "s",
    agent: "grok",
    seq: 1,
    ts: "t",
    replay: false,
    origin: "live",
    native: { protocol: "acp", payload: {} },
    ...body,
  } as CoreNormalizedEvent
}

test("maps permission-request to request-open", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "grok", emit: (e) => events.push(e) })
  b.handle(env({
    kind: "permission-request",
    requestId: "r1",
    toolCall: { callId: "c1", tool: "execute", title: "Bash", input: { command: "ls" } },
    options: [
      { id: "allow_once", kind: "allow_once", label: "Allow once" },
      { id: "reject_once", kind: "reject_once", label: "Reject" },
    ],
    detail: { command: "ls" },
  }))
  expect(events[0]).toMatchObject({
    kind: "request-open",
    requestId: "r1",
    requestKind: "permission",
    title: "Bash",
    body: "execute ls",
    allowFreeText: false,
    blocking: true,
  })
})

test("maps user-question questions[] into body", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "grok", emit: (e) => events.push(e) })
  const questions = [{
    id: "q1",
    prompt: "Color?",
    header: "Pick",
    multiSelect: false,
    allowFreeText: true,
    options: [{ id: "o1", label: "Blue" }],
  }]
  b.handle(env({
    kind: "user-question",
    requestId: "r2",
    blocking: true,
    questions,
  }))
  expect(events[0]).toMatchObject({
    kind: "request-open",
    requestKind: "question",
    title: "Pick",
    body: JSON.stringify(questions),
    allowFreeText: true,
    blocking: true,
  })
})

test("maps request-resolved to request-closed", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "grok", emit: (e) => events.push(e) })
  b.handle(env({ kind: "request-resolved", requestId: "r1", outcome: "answered" }))
  expect(events[0]).toEqual({ kind: "request-closed", requestId: "r1", outcome: "answered" })
})

test("request-closed names the option that was actually chosen", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "grok", emit: (e) => events.push(e) })
  const open = {
    kind: "permission-request",
    requestId: "r1",
    toolCall: { callId: "c1", tool: "execute", title: "Bash", input: { command: "ls" } },
    options: [
      { id: "allow_once", kind: "allow_once", label: "Allow once" },
      { id: "allow_always", kind: "allow_always", label: "Allow always" },
      { id: "reject_once", kind: "reject_once", label: "Reject" },
    ],
    detail: { command: "ls" },
  }
  b.handle(env(open))
  b.handle(env({
    kind: "request-resolved",
    requestId: "r1",
    outcome: "answered",
    answer: { optionId: "allow_always" },
  }))
  expect(events[1]).toEqual({
    kind: "request-closed",
    requestId: "r1",
    outcome: "answered",
    answerLabel: "Allow always",
  })

  // A reject that carries a note keeps the note in the label.
  b.handle(env({ ...open, requestId: "r2" }))
  b.handle(env({
    kind: "request-resolved",
    requestId: "r2",
    outcome: "answered",
    answer: { optionId: "reject_once", message: "too risky" },
  }))
  expect(events[3]).toMatchObject({ answerLabel: "Reject — too risky" })

  // A question answers with labels already; a decline says so.
  b.handle(env({
    kind: "user-question",
    requestId: "r3",
    blocking: true,
    questions: [{ id: "q1", prompt: "Color?", multiSelect: false, allowFreeText: false, options: [{ id: "o1", label: "Blue" }] }],
  }))
  b.handle(env({ kind: "request-resolved", requestId: "r3", outcome: "answered", answer: { answers: { q1: "Blue" } } }))
  expect(events[5]).toMatchObject({ answerLabel: "Blue" })
})

// ── Subagents (S2): child bodies never reach the parent's chat/agent state ─────────────────────

test("child assistant text, tool calls and errors never reach the parent stream", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "claude", emit: (e) => events.push(e) })
  b.handle(env({ kind: "assistant-delta", messageId: "m", text: "child words", subagentId: "a1" }))
  b.handle(env({ kind: "assistant-message", messageId: "m", text: "child final", subagentId: "a1" }))
  b.handle(env({ kind: "tool-call", callId: "c1", tool: "Bash", phase: "started", subagentId: "a1" }))
  b.handle(env({ kind: "tool-call", callId: "c1", tool: "Bash", phase: "completed", subagentId: "a1" }))
  b.handle(env({ kind: "error", message: "child blew up", subagentId: "a1" }))
  b.flush()
  expect(events).toEqual([])
  // The parent's own buffered text is untouched by the child's deltas.
  b.handle(env({ kind: "assistant-delta", messageId: "p", text: "PELICAN" }))
  b.flush()
  expect(events).toEqual([{ kind: "assistant-message", text: "PELICAN" }])
})

test("codex child tool calls do not drive the parent's tool-call events", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "codex", emit: (e) => events.push(e) })
  b.handle(env({
    kind: "tool-call", callId: "exec-1", tool: "commandExecution", phase: "started", subagentId: "t1",
    native: { protocol: "codex-app-server", payload: { method: "item/started", params: { item: { type: "commandExecution", id: "exec-1", command: "pwd" } } } },
  }))
  expect(events).toEqual([])
})

test("subagent bodies become subagent events and label a child's permission request", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "claude", emit: (e) => events.push(e) })
  b.handle(env({ kind: "subagent", subagentId: "a1", phase: "started", name: "general-purpose", description: "Write files", parentCallId: "toolu_1" }))
  b.handle(env({
    kind: "permission-request", requestId: "r9", subagentId: "a1",
    toolCall: { callId: "c2", tool: "Write", title: "Write" },
    options: [{ id: "allow", kind: "allow_once", label: "Allow" }],
  }))
  expect(events[0]).toEqual({
    kind: "subagent",
    body: { kind: "subagent", subagentId: "a1", phase: "started", name: "general-purpose", description: "Write files", parentCallId: "toolu_1" },
  })
  expect(events[1]).toMatchObject({
    kind: "request-open", requestId: "r9", subagentId: "a1", subagentName: "general-purpose", subagentDescription: "Write files",
  })
  expect(b.decorateRequest({ requestId: "r9", kind: "permission", title: "Write", body: "Write", options: [], allowFreeText: false, blocking: true, subagentId: "a1" }))
    .toMatchObject({ subagentName: "general-purpose", subagentDescription: "Write files" })
})

test("a child's auto-approved permission row carries subagentId", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "grok", emit: (e) => events.push(e) })
  b.handle(env({ kind: "permission-auto", subagentId: "g1", toolCall: { callId: "c1", tool: "read_file", title: "read" }, optionId: "allow" }))
  expect(events[0]).toMatchObject({ kind: "activity", events: [{ callId: "c1", subagentId: "g1" }] })
})

test("task bodies surface as task events (background shell)", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "claude", emit: (e) => events.push(e) })
  b.handle(env({ kind: "task", taskId: "b31", taskKind: "shell", phase: "started", label: "Sleep", parentCallId: "toolu_2" }))
  expect(events).toEqual([{ kind: "task", taskId: "b31", taskKind: "shell", phase: "started", label: "Sleep", parentCallId: "toolu_2" }])
})

test("replayed subagent bodies are ignored like other replay", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "claude", emit: (e) => events.push(e) })
  b.handle(env({ kind: "subagent", subagentId: "a1", phase: "started", replay: true }))
  expect(events).toEqual([])
})
