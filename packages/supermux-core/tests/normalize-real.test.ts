import { describe, expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { existsSync } from "node:fs"
import { createAcpNormalizer } from "../src/acp/normalize.js"
import { createClaudeNormalizer } from "../src/claude/normalize.js"
import { createCodexNormalizer } from "../src/codex/normalize.js"
import type { AgentUpdate } from "../src/types.js"
import type { NormalizedBody } from "../src/events/normalized.js"

const dir = dirname(fileURLToPath(import.meta.url))
const fixture = (name: string) => readFileSync(join(dir, "fixtures/real", name), "utf8")
  .split("\n")
  .filter(line => line.trim())
  .map(line => JSON.parse(line) as Record<string, unknown>)

function replay(normalize: (update: AgentUpdate) => NormalizedBody[], updates: AgentUpdate[]): NormalizedBody[] {
  const out: NormalizedBody[] = []
  for (const update of updates) out.push(...normalize(update))
  const flush = (normalize as typeof normalize & { flush: () => NormalizedBody[] }).flush
  out.push(...flush())
  return out
}

function wrapCodex(frame: Record<string, unknown>): AgentUpdate {
  return { protocol: "native", value: { method: frame.method, params: frame.params } }
}

function wrapNative(frame: Record<string, unknown>): AgentUpdate {
  return { protocol: "native", value: frame }
}

function assertRealTurn(events: NormalizedBody[]) {
  expect(events.some(e => e.kind === "assistant-message" && e.text.length > 0)).toBe(true)
  const finals = new Set<string>()
  for (const ev of events) {
    if (ev.kind === "assistant-message") finals.add(ev.messageId)
    if (ev.kind === "reasoning") finals.add(ev.reasoningId)
    if (ev.kind === "tool-call") expect(ev.callId).toBeTruthy()
  }
  for (const ev of events) {
    if (ev.kind === "assistant-delta") expect(finals.has(ev.messageId)).toBe(true)
    if (ev.kind === "reasoning-delta") expect(finals.has(ev.reasoningId)).toBe(true)
  }
}

function wrapAcp(frame: Record<string, unknown>): AgentUpdate {
  const method = frame.method
  if (method === "session/update") {
    const params = frame.params as Record<string, unknown> | undefined
    return { protocol: "acp", value: params?.update }
  }
  return { protocol: "native", value: { method, params: frame.params } }
}

describe("real wire captures", () => {
  test("codex-turn.ndjson", () => {
    const n = createCodexNormalizer()
    const events = replay(n, fixture("codex-turn.ndjson").map(wrapCodex))

    for (const ev of events) {
      if (ev.kind === "tool-call") {
        expect(ev.tool).not.toBe("userMessage")
        expect(ev.tool).not.toBe("agentMessage")
      }
    }

    const tools = events.filter(e => e.kind === "tool-call")
    expect(tools.some(e => e.kind === "tool-call" && e.tool === "commandExecution" && e.phase === "started")).toBe(true)
    expect(tools.some(e => e.kind === "tool-call" && e.tool === "commandExecution" && e.phase === "completed" && e.exitCode === 0)).toBe(true)

    const assistant = events.filter(e => e.kind === "assistant-message")
    expect(assistant).toHaveLength(2)
    const ids = new Set(assistant.map(e => e.kind === "assistant-message" ? e.messageId : ""))
    for (const ev of events) {
      if (ev.kind === "assistant-delta") expect(ids.has(ev.messageId)).toBe(true)
    }

    expect(events.some(e => e.kind === "command-output" && e.delta.includes("the answer is 42"))).toBe(true)
    expect(events.some(e => e.kind === "usage" && e.tokens != null && e.context != null)).toBe(true)
  })

  test("grok-turn.ndjson", () => {
    const n = createAcpNormalizer({ vendor: "grok" })
    const events = replay(n, fixture("grok-turn.ndjson").map(wrapAcp))

    const reasoning = events.filter(e => e.kind === "reasoning")
    const firstReasoning = events.findIndex(e => e.kind === "reasoning")
    const toolStarted = events.findIndex(e => e.kind === "tool-call" && e.phase === "started")
    const toolDone = events.findIndex(e => e.kind === "tool-call" && (e.phase === "completed" || e.phase === "updated"))
    expect(reasoning.length).toBeGreaterThanOrEqual(1)
    expect(firstReasoning).toBeGreaterThanOrEqual(0)
    expect(firstReasoning).toBeLessThan(toolStarted)

    const assistant = events.filter(e => e.kind === "assistant-message")
    expect(assistant).toHaveLength(1)
    expect(assistant[0]!.kind === "assistant-message" && assistant[0]!.text.includes("42")).toBe(true)
    const assistantIdx = events.findIndex(e => e.kind === "assistant-message")
    expect(assistantIdx).toBeGreaterThan(toolDone)

    expect(events.filter(e => e.kind === "commands-update")).toHaveLength(1)

    const seq = events
      .filter(e => e.kind === "reasoning-delta" || e.kind === "reasoning" || e.kind === "tool-call" || e.kind === "assistant-delta" || e.kind === "assistant-message")
      .map(e => (e.kind === "tool-call" ? `tool-call:${e.phase}` : e.kind))

    const first = seq.indexOf("reasoning")
    const start = seq.indexOf("tool-call:started")
    const done = seq.findIndex((k, i) => i > start && (k === "tool-call:completed" || k === "tool-call:updated"))
    const asstDelta = seq.findIndex((k, i) => i > done && k === "assistant-delta")
    const asst = seq.lastIndexOf("assistant-message")
    expect(seq.slice(0, first).every(k => k === "reasoning-delta")).toBe(true)
    expect(start).toBeGreaterThan(first)
    expect(seq[start]).toBe("tool-call:started")
    expect(done).toBeGreaterThan(start)
    expect(seq.slice(start, done + 1).every(k => k.startsWith("tool-call:"))).toBe(true)
    expect(asstDelta).toBeGreaterThan(done)
    expect(seq.slice(asstDelta, asst).every(k => k === "assistant-delta")).toBe(true)
    expect(seq[asst]).toBe("assistant-message")
    expect(asst).toBe(seq.length - 1)
  })

  // Real `opencode acp` RESUME (opencode-go/glm-5.3-flash) captured from the keeper
  // journal of the broker's resume probe: session/load replays turn 1 (user
  // message, read tool call + update, thought, "42") and then the live turn 2
  // (user message, thought, "42"); commands and usage follow; no vendor frames.
  test("opencode-turn.ndjson", () => {
    const n = createAcpNormalizer({})
    const events = replay(n, fixture("opencode-turn.ndjson").map(wrapAcp))
    const kinds = events.map(e => (e.kind === "tool-call" ? `tool-call:${e.phase}` : e.kind))
    const toolStarted = kinds.indexOf("tool-call:started")
    const toolDone = kinds.findIndex((k, i) => i > toolStarted && (k === "tool-call:completed" || k === "tool-call:updated"))
    expect(toolStarted).toBeGreaterThanOrEqual(0)
    expect(toolDone).toBeGreaterThan(toolStarted)
    expect(events.some(e => e.kind === "reasoning")).toBe(true)
    const assistant = events.filter(e => e.kind === "assistant-message")
    expect(assistant).toHaveLength(2)
    expect(assistant.every(a => a.kind === "assistant-message" && a.text.trim() === "42")).toBe(true)
    expect(kinds.indexOf("assistant-message")).toBeGreaterThan(toolDone)
    expect(events.filter(e => e.kind === "commands-update")).toHaveLength(1)
    expect(events.some(e => e.kind === "usage")).toBe(true)
    expect(events.some(e => e.kind === "warning")).toBe(false)
  })

  const claudePath = join(dir, "fixtures/real/claude-turn.ndjson")
  if (existsSync(claudePath)) {
    test("claude-turn.ndjson", () => {
      const n = createClaudeNormalizer()
      const bodies = replay(n, fixture("claude-turn.ndjson").map(wrapNative))
      assertRealTurn(bodies)
      // Partial stream + full assistant frame describe the SAME block: exactly one final per id.
      const finals = bodies.filter((b: any) => b.kind === "assistant-message").map((b: any) => b.messageId)
      expect(new Set(finals).size).toBe(finals.length)
      expect(bodies.some((b: any) => b.kind === "tool-call" && b.tool === "Read" && b.phase === "started")).toBe(true)
      expect(bodies.some((b: any) => b.kind === "tool-call" && (b.phase === "completed" || b.phase === "failed"))).toBe(true)
    })
  }

  test("cursor-turn.ndjson", () => {
    const n = createAcpNormalizer({})
    const events = replay(n, fixture("cursor-turn.ndjson").map(wrapAcp))
    const kinds = events.map(e => (e.kind === "tool-call" ? `tool-call:${e.phase}` : e.kind))
    const toolStarted = kinds.indexOf("tool-call:started")
    const toolDone = kinds.findIndex((k, i) => i > toolStarted && (k === "tool-call:completed" || k === "tool-call:updated"))
    expect(toolStarted).toBeGreaterThanOrEqual(0)
    expect(toolDone).toBeGreaterThan(toolStarted)
    expect(events.some(e => e.kind === "reasoning")).toBe(true)
    const assistant = events.filter(e => e.kind === "assistant-message")
    expect(assistant.length).toBeGreaterThanOrEqual(1)
    expect(assistant.some(a => a.kind === "assistant-message" && a.text.includes("42"))).toBe(true)
    expect(kinds.indexOf("assistant-message")).toBeGreaterThan(toolDone)
  })
})
