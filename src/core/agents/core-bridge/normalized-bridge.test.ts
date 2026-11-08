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

test("models-update reaches onModels, a replayed one too (#102)", () => {
  const seen: unknown[] = []
  const b = createNormalizedBridge({ agent: "cursor", emit: () => {}, onModels: (models) => seen.push(models) })
  const models = [{ value: "default[]", name: "Auto" }, { value: "composer-1[fast=true]", name: "composer-1" }]
  b.handle(env({ kind: "models-update", models }))
  b.handle(env({ kind: "models-update", models, replay: true }))
  expect(seen).toEqual([models, models])
})

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

test("antigravity tool-call detail is the normalized input, not an ACP frame", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "antigravity", emit: (e) => events.push(e) })
  const input = { CommandLine: "ls", Cwd: "/w" }
  b.handle(env({ kind: "tool-call", callId: "c1", tool: "run_command", phase: "started", input, native: { protocol: "native", payload: { step_type: "tool" } } }))
  expect(events).toEqual([{ kind: "tool-call", tool: "run_command", phase: "started", call_id: "c1", detail: input }])
})

test("an antigravity denial warning becomes the visible reply, after any buffered text", () => {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent: "antigravity", emit: (e) => events.push(e) })
  b.handle(env({ kind: "assistant-delta", messageId: "m", text: "Trying." }))
  b.handle(env({ kind: "warning", source: "antigravity", message: "Antigravity denied: run_command — switch permission mode to allow" }))
  // The buffered text streamed live (card #76): it is stored under its stream, then the denial follows.
  const stream = (events[0] as any).stream
  expect(events).toEqual([
    { kind: "live-delta", stream, streamKind: "reply", text: "Trying." },
    { kind: "live-end", stream, final: true },
    { kind: "assistant-message", text: "Trying.", stream },
    { kind: "assistant-message", text: "Antigravity denied: run_command — switch permission mode to allow" },
  ])
  // Warnings from other sources stay silent.
  b.handle(env({ kind: "warning", source: "acp", message: "meh" }))
  expect(events).toHaveLength(4)
  // The denial counts as the last reply: an identical final message is not repeated.
  b.handle(env({ kind: "assistant-message", messageId: "m2", text: "Antigravity denied: run_command — switch permission mode to allow" }))
  expect(events).toHaveLength(4)
})

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
  // The parent's own buffered text is untouched by the child's deltas (and streams live, card #76).
  b.handle(env({ kind: "assistant-delta", messageId: "p", text: "PELICAN" }))
  b.flush()
  const stream = (events[0] as any).stream
  expect(events).toEqual([
    { kind: "live-delta", stream, streamKind: "reply", text: "PELICAN" },
    { kind: "live-end", stream, final: true },
    { kind: "assistant-message", text: "PELICAN", stream },
  ])
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

// Card #76: live text.
function live(agent = "claude") {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent, emit: (e) => events.push(e) })
  return { b, events }
}

test("a reply streams under one id and its stored message carries it", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "Hel" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "lo" }))
  b.handle(env({ kind: "assistant-message", messageId: "m:1", text: "Hello" }))
  const stream = (events[0] as any).stream
  expect(typeof stream).toBe("string")
  expect(events).toEqual([
    { kind: "live-delta", stream, streamKind: "reply", text: "Hel" },
    { kind: "live-delta", stream, streamKind: "reply", text: "lo" },
    { kind: "live-end", stream, final: true },
    { kind: "assistant-message", text: "Hello", stream },
  ])
})

test("each text block gets its own stream", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "a" }))
  b.handle(env({ kind: "assistant-message", messageId: "m:1", text: "a" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:2", text: "b" }))
  const ids = events.filter(e => e.kind === "live-delta").map(e => (e as any).stream)
  expect(ids.length).toBe(2)
  expect(ids[0]).not.toBe(ids[1])
})

test("thinking streams; an empty thinking delta opens nothing; the reasoning final closes it", () => {
  const { b, events } = live()
  b.handle(env({ kind: "reasoning-delta", reasoningId: "m:0", text: "" }))
  expect(events).toEqual([])
  b.handle(env({ kind: "reasoning-delta", reasoningId: "m:0", text: "Plan" }))
  const stream = (events[0] as any).stream
  expect(events[0]).toEqual({ kind: "live-delta", stream, streamKind: "thinking", text: "Plan" })
  expect(b.handle(env({ kind: "reasoning", reasoningId: "m:0", text: "Plan", redacted: false }))).toBe(stream)
  expect(events[1]).toEqual({ kind: "live-end", stream, final: true })
  expect(b.handle(env({ kind: "reasoning", reasoningId: "other", redacted: true }))).toBeUndefined()
})

test("subagent and replayed text never streams", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "child", subagentId: "sub1" }))
  b.handle(env({ kind: "reasoning-delta", reasoningId: "r", text: "child", subagentId: "sub1" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:2", text: "old", replay: true }))
  expect(events).toEqual([])
})

test("a final the bridge drops (same text again) ends its stream without a final", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-message", messageId: "m:1", text: "same" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:2", text: "same" }))
  b.handle(env({ kind: "assistant-message", messageId: "m:2", text: "same" }))
  const stream = (events[1] as any).stream
  expect(events.slice(1)).toEqual([
    { kind: "live-delta", stream, streamKind: "reply", text: "same" },
    { kind: "live-end", stream, final: false },
  ])
})

test("flush stores the pending text under its stream and ends the rest", () => {
  const { b, events } = live("grok")
  b.handle(env({ kind: "reasoning-delta", reasoningId: "reasoning:1", text: "hm" }))
  b.handle(env({ kind: "assistant-delta", messageId: "assistant:1", text: "partial" }))
  b.flush()
  const thinking = (events[0] as any).stream
  const reply = (events[1] as any).stream
  expect(events.slice(2)).toEqual([
    { kind: "live-end", stream: reply, final: true },
    { kind: "assistant-message", text: "partial", stream: reply },
    { kind: "live-end", stream: thinking, final: false },
  ])
})
