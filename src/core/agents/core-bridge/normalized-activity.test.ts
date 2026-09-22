import { test, expect } from "bun:test"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { createNormalizedActivity } from "./normalized-activity"
import { createCodexNormalizer } from "../../../../packages/supermux-core/src/codex/normalize.js"
import { createAcpNormalizer } from "../../../../packages/supermux-core/src/acp/normalize.js"
import { createCursorNormalizer } from "../../../../packages/supermux-core/src/cursor/normalize.js"
import type { AgentUpdate } from "../../../../packages/supermux-core/src/types.js"
import type { NormalizedEvent } from "../../../../packages/supermux-core/src/events/normalized.js"
import type { ActivityEvent } from "../claude/activity-event"

const NOW = 1730000000000
const WD = "/w"

type ToolEv = { tool: string; phase: "started" | "completed" | "failed"; call_id: string; detail?: unknown }

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

function publicCard(c: ActivityEvent) {
  return {
    kind: c.kind,
    tool: c.tool,
    title: c.title,
    detail: c.detail,
    description: c.description,
    body: c.body,
    callId: c.callId,
  }
}

function envelope(agent: string, body: NormalizedEvent["event"], seq: number, payload: unknown): NormalizedEvent {
  return {
    sessionId: "s",
    agent,
    seq,
    ts: new Date(NOW).toISOString(),
    replay: false,
    origin: "live",
    native: { protocol: agent === "codex" ? "codex-app-server" : "acp", payload },
    event: body,
  }
}

function inferCodexType(tool: string, detail: Record<string, unknown>): string {
  if (typeof detail.type === "string" && detail.type) return detail.type
  const k = tool.toLowerCase()
  if (k.includes("shell") || k.includes("bash") || k.includes("command")) return "commandExecution"
  if (k.includes("file") || k.includes("edit")) return "fileChange"
  if (k.includes("mcp")) return "mcpToolCall"
  if (k.includes("web") || k.includes("search")) return "webSearch"
  return tool
}

function wrapCodex(ev: ToolEv): AgentUpdate {
  const detail = rec(ev.detail) ?? {}
  const item: Record<string, unknown> = { ...detail, id: ev.call_id, type: inferCodexType(ev.tool, detail) }
  if (ev.phase === "failed" && item.status == null) item.status = "failed"
  const method = ev.phase === "started" ? "item/started" : "item/completed"
  return { protocol: "native", value: { method, params: { item } } }
}

function wrapGrok(ev: ToolEv): AgentUpdate {
  const d = rec(ev.detail) ?? {}
  const sessionUpdate = ev.phase === "started" ? "tool_call" : "tool_call_update"
  const status = ev.phase === "started" ? "in_progress" : ev.phase === "failed" ? "failed" : "completed"
  let kind: string | undefined
  if (/write|replace|edit/i.test(ev.tool)) kind = "edit"
  else if (/bash|shell/i.test(ev.tool)) kind = "execute"
  return {
    protocol: "acp",
    value: <never>{
      sessionUpdate,
      toolCallId: ev.call_id,
      name: ev.tool,
      title: d.title,
      rawInput: d.rawInput,
      content: d.content,
      kind,
      status,
    },
  }
}

function newCards(agent: "codex" | "grok", ev: ToolEv, now = NOW, workdir: string | undefined = WD): ActivityEvent[] {
  const n = agent === "codex" ? createCodexNormalizer() : createAcpNormalizer({ vendor: "grok" })
  const update = agent === "codex" ? wrapCodex(ev) : wrapGrok(ev)
  const bodies = n(update)
  const act = createNormalizedActivity({ workdir })
  const out: ActivityEvent[] = []
  for (const [i, body] of bodies.entries()) {
    out.push(...act.handle(envelope(agent, body, i, (update as { value: unknown }).value), now))
  }
  return out
}

function assertParity(_name: string, agent: "codex" | "grok", ev: ToolEv, expected?: Record<string, unknown> | Record<string, unknown>[], opts?: { now?: number; workdir?: string | undefined }) {
  const now = opts?.now ?? NOW
  const workdir = opts?.workdir === undefined && opts && "workdir" in opts ? undefined : (opts?.workdir ?? WD)
  const nextCards = newCards(agent, ev, now, workdir).map(publicCard)
  expect(nextCards.length).toBeGreaterThanOrEqual(1)
  if (expected) {
    const exp = Array.isArray(expected) ? expected : [expected]
    expect(nextCards).toMatchObject(exp)
  }
}

// --- codex fixtures from adapter-activity.test.ts ---

test("parity: codex shell started", () => {
  assertParity("codex shell started", "codex", { tool: "shell", phase: "started", call_id: "c1", detail: { type: "command_execution", command: "npm test" } }, {
    kind: "tool", tool: "Bash", title: "Bash: npm test", detail: "npm test", body: { kind: "bash", command: "npm test" },
  })
})

test("parity: codex completed aggregated_output", () => {
  assertParity("codex completed", "codex", { tool: "shell", phase: "completed", call_id: "c1", detail: { type: "command_execution", aggregated_output: "ok" } })
})

test("parity: codex commandExecution multiline", () => {
  assertParity("codex multiline", "codex", { tool: "commandExecution", phase: "completed", call_id: "c1", detail: { type: "commandExecution", aggregatedOutput: "first\nsecond\n", exitCode: 0 } })
})

test("parity: codex failed exit code", () => {
  assertParity("codex failed exit", "codex", { tool: "commandExecution", phase: "failed", call_id: "c1", detail: { type: "commandExecution", aggregatedOutput: null, exitCode: 7 } })
})

test("parity: codex completed no output", () => {
  assertParity("codex no output", "codex", { tool: "shell", phase: "completed", call_id: "c1", detail: { type: "command_execution" } })
})

test("parity: codex failed empty", () => {
  assertParity("codex failed empty", "codex", { tool: "shell", phase: "failed", call_id: "c1", detail: {} })
})

test("parity: codex file_change path", () => {
  assertParity("codex file_change path", "codex", { tool: "file_change", phase: "started", call_id: "c2", detail: { type: "fileChange", path: "/w/a/b.ts" } })
})

test("parity: codex fileChange started diffs", () => {
  const changes = [
    { path: "/w/src/a.ts", kind: { type: "update", move_path: null }, diff: "@@ -1 +1 @@\n-old\n+new" },
    { path: "/w/src/b.ts", kind: { type: "add" }, diff: "+export {}" },
  ]
  assertParity("codex fileChange started", "codex", { tool: "fileChange", phase: "started", call_id: "c2", detail: { type: "fileChange", changes } })
})

test("parity: codex fileChange completed", () => {
  const changes = [{ path: "/w/src/a.ts", kind: { type: "update", move_path: null }, diff: "@@ -1 +1 @@" }]
  assertParity("codex fileChange completed", "codex", { tool: "fileChange", phase: "completed", call_id: "c2", detail: { type: "fileChange", changes } })
})

test("parity: codex web_search", () => {
  assertParity("codex web_search", "codex", { tool: "web_search", phase: "started", call_id: "c3", detail: { type: "webSearch", query: "how to npm" } })
})

test("parity: codex webSearch queries", () => {
  assertParity("codex webSearch queries", "codex", {
    tool: "webSearch",
    phase: "started",
    call_id: "c4",
    detail: {
      type: "webSearch",
      query: "first query ...",
      action: { type: "search", query: null, queries: ["first query", "second query"] },
    },
  })
})

test("parity: codex webSearch url", () => {
  assertParity("codex webSearch url", "codex", {
    tool: "webSearch",
    phase: "started",
    call_id: "c5",
    detail: {
      type: "webSearch",
      query: "https://developers.openai.com/codex/",
      action: { type: "openPage", url: "https://developers.openai.com/codex/" },
    },
  })
})

test("parity: codex mcp started", () => {
  assertParity("codex mcp started", "codex", { tool: "mcp_tool_call", phase: "started", call_id: "m1", detail: { type: "mcpToolCall", toolName: "spawn_session", arguments: { name: "test", workdir: "/tmp" } } })
})

test("parity: codex mcp no arg", () => {
  assertParity("codex mcp no arg", "codex", { tool: "mcp_tool_call", phase: "started", call_id: "m2", detail: { type: "mcpToolCall", toolName: "reply", arguments: {} } })
})

test("parity: codex mcp completed string", () => {
  assertParity("codex mcp completed string", "codex", { tool: "mcp_tool_call", phase: "completed", call_id: "m1", detail: { type: "mcpToolCall", toolName: "spawn_session", arguments: { name: "test" }, result: '{"session_id":"abc"}' } })
})

test("parity: codex mcp current started", () => {
  assertParity("codex mcp current started", "codex", { tool: "mcpToolCall", phase: "started", call_id: "m2", detail: { type: "mcpToolCall", server: "mux", tool: "reply", arguments: { text: "hello" } } })
})

test("parity: codex mcp current completed", () => {
  assertParity("codex mcp current completed", "codex", { tool: "mcpToolCall", phase: "completed", call_id: "m2", detail: { type: "mcpToolCall", tool: "reply", result: { content: [{ type: "text", text: "sent\nok" }], structuredContent: null } } })
})

test("parity: codex dynamic started", () => {
  assertParity("codex dynamic started", "codex", { tool: "Imagegen", phase: "started", call_id: "d1", detail: { type: "dynamicToolCall", tool: "Imagegen", arguments: { prompt: "draw a fox" } } })
})

test("parity: codex dynamic completed", () => {
  assertParity("codex dynamic completed", "codex", { tool: "Imagegen", phase: "completed", call_id: "d1", detail: { type: "dynamicToolCall", contentItems: [{ type: "inputText", text: "created\nasset" }] } })
})

test("parity: codex fileChange body diff", () => {
  const changes = [{ path: "/w/src/a.ts", kind: { type: "update" }, diff: "@@ -1 +1 @@\n-old\n+new" }]
  assertParity("codex fileChange body", "codex", { tool: "fileChange", phase: "started", call_id: "c2", detail: { type: "fileChange", changes } })
})

test("parity: codex shell description", () => {
  assertParity("codex description", "codex", { tool: "shell", phase: "started", call_id: "cx-d", detail: { type: "command_execution", command: "npm test", description: "Verify green before merge" } })
})

test("parity: grok write body", () => {
  assertParity("grok write", "grok", { tool: "write", phase: "started", call_id: "g1", detail: { title: "write", rawInput: { file_path: "/w/poem.txt", content: "hello\nworld" } } }, {
    tool: "Write",
    body: { kind: "write", path: "poem.txt", content: "hello\nworld" },
  })
})

test("parity: grok description", () => {
  assertParity("grok description", "grok", { tool: "bash", phase: "started", call_id: "g-d", detail: { title: "bash", rawInput: { command: "ls -la", description: "List project root contents" } } })
})

test("parity: grok search_replace", () => {
  assertParity("grok search_replace", "grok", {
    tool: "search_replace",
    phase: "started",
    call_id: "g-sr",
    detail: {
      title: "search_replace",
      rawInput: {
        path: "/w/hello.ts",
        old_string: "return `hello ${name}`",
        new_string: "return `Hello, ${name}!`",
      },
    },
  })
})

test("parity: grok write title", () => {
  assertParity("grok write title", "grok", {
    tool: "write",
    phase: "started",
    call_id: "c0",
    detail: { title: "write", rawInput: { file_path: "/w/poem.txt", content: "x" } },
  }, { tool: "Write", kind: "tool", title: "Write: poem.txt" }, { now: Date.parse("2026-07-13T00:00:00Z") })
})

test("normalized: grok completed content", () => {
  assertParity("grok completed", "grok", {
    tool: "edit", phase: "completed", call_id: "c0",
    detail: { title: "Write `/w/poem.txt`", status: "completed", content: [{ type: "content", content: { type: "text", text: "wrote 2 lines" } }] },
  }, { kind: "tool_result", title: "done", detail: "wrote 2 lines" })
})

test("normalized: grok failed content", () => {
  assertParity("grok failed", "grok", {
    tool: "write", phase: "failed", call_id: "c0",
    detail: { status: "failed", content: [{ type: "content", content: { type: "text", text: "permission denied" } }] },
  }, { title: "error", detail: "permission denied" })
})

test("normalized: codex missing detail", () => {
  assertParity("codex missing", "codex", { tool: "shell", phase: "started", call_id: "c1" }, {
    kind: "tool", tool: "Bash", title: "Bash", detail: "",
  })
})

test("normalized: codex null detail", () => {
  assertParity("codex null", "codex", { tool: "bash", phase: "started", call_id: "c1", detail: null }, {
    kind: "tool", tool: "Bash", title: "Bash", detail: "",
  })
})

const realDir = join(dirname(fileURLToPath(import.meta.url)), "../../../../packages/supermux-core/tests/fixtures/real")

function loadNdjson(name: string): Record<string, unknown>[] {
  return readFileSync(join(realDir, name), "utf8").split("\n").filter((l) => l.trim()).map((l) => JSON.parse(l) as Record<string, unknown>)
}

test("parity: real codex-turn.ndjson tool cards", () => {
  const frames = loadNdjson("codex-turn.ndjson")
  const n = createCodexNormalizer()
  const act = createNormalizedActivity({ workdir: WD })
  const next: ActivityEvent[] = []
  let seq = 0
  for (const frame of frames) {
    const method = frame.method as string | undefined
    const params = rec(frame.params) ?? {}
    const bodies = n({ protocol: "native", value: { method, params } })
    for (const body of bodies) {
      next.push(...act.handle(envelope("codex", body, seq++, frame), NOW))
    }
  }
  const cards = next.map(publicCard)
  expect(cards.some((c) => c.kind === "tool")).toBe(true)
  expect(cards.some((c) => c.kind === "tool_result")).toBe(true)
})

test("parity: real grok-turn.ndjson tool cards", () => {
  const frames = loadNdjson("grok-turn.ndjson")
  const n = createAcpNormalizer({ vendor: "grok" })
  const act = createNormalizedActivity({ workdir: WD })
  const next: ActivityEvent[] = []
  let seq = 0
  for (const frame of frames) {
    const update: AgentUpdate = frame.method === "session/update"
      ? { protocol: "acp", value: (rec(rec(frame.params)?.update) ?? {}) as never }
      : { protocol: "native", value: { method: frame.method, params: frame.params } }
    const bodies = n(update)
    for (const body of bodies) {
      next.push(...act.handle(envelope("grok", body, seq++, frame), NOW))
    }
  }
  const cards = next.map(publicCard)
  expect(cards.some((c) => c.kind === "tool")).toBe(true)
  expect(cards.some((c) => c.kind === "tool_result")).toBe(true)
})

test("reasoning redacted becomes Thinking (redacted) card", () => {
  const act = createNormalizedActivity({ workdir: WD })
  const cards = act.handle(envelope("grok", { kind: "reasoning", reasoningId: "x", redacted: true }, 1, {}), NOW)
  expect(cards[0]).toMatchObject({ kind: "reasoning", title: "Thinking (redacted)" })
})

test("plan and task become activity cards", () => {
  const act = createNormalizedActivity({ workdir: WD })
  const plan = act.handle(envelope("grok", {
    kind: "plan",
    entries: [{ content: "do it", status: "pending" }],
  }, 1, {}), NOW)
  expect(plan[0]?.kind).toBe("plan")
  const task = act.handle(envelope("grok", {
    kind: "task",
    taskId: "t1",
    taskKind: "shell",
    phase: "started",
    label: "build",
  }, 2, {}), NOW)
  expect(task[0]).toMatchObject({ kind: "task", title: "build" })
})

test("parity: real cursor-turn.ndjson tool cards", () => {
  const frames = loadNdjson("cursor-turn.ndjson")
  const n = createCursorNormalizer()
  const act = createNormalizedActivity({ workdir: WD })
  const next: ActivityEvent[] = []
  let seq = 0
  for (const frame of frames) {
    const bodies = n({ protocol: "native", value: frame })
    for (const body of bodies) {
      next.push(...act.handle(envelope("cursor", body, seq++, frame), NOW))
    }
  }
  const cards = next.map(publicCard)
  expect(cards.some((c) => c.kind === "tool")).toBe(true)
  expect(cards.some((c) => c.kind === "tool_result")).toBe(true)
})
