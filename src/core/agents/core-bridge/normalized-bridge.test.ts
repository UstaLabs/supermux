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
