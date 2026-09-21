import { describe, expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { createAcpNormalizer } from "../src/acp/normalize.js"
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
})
