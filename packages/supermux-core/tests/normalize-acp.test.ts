import { describe, expect, test } from "bun:test"
import { createAcpNormalizer } from "../src/acp/normalize.js"
import type { AgentUpdate } from "../src/types.js"

const acp = (sessionUpdate: string, rest: Record<string, unknown> = {}, replay?: boolean): AgentUpdate => ({
  protocol: "acp",
  value: { sessionUpdate, ...rest },
  ...(replay ? { replay: true } : {}),
})

describe("acp normalizer", () => {
  test("agent_message_chunk delta and flush", () => {
    const n = createAcpNormalizer()
    expect(n(acp("agent_message_chunk", { messageId: "m", content: { type: "text", text: "Hi" } }))[0]).toMatchObject({ kind: "assistant-delta", messageId: "m", text: "Hi" })
    n(acp("agent_message_chunk", { messageId: "m", content: { type: "text", text: "!" } }))
    expect(n.flush()).toEqual([{ kind: "assistant-message", messageId: "m", text: "Hi!" }])
  })

  test("agent_thought_chunk delta and flush", () => {
    const n = createAcpNormalizer()
    n(acp("agent_thought_chunk", { messageId: "r", content: { type: "text", text: "hmm" } }))
    expect(n.flush()[0]).toMatchObject({ kind: "reasoning", reasoningId: "r", redacted: false, text: "hmm" })
  })

  test("tool_call and tool_call_update with diff and terminal", () => {
    const n = createAcpNormalizer()
    const started = n(acp("tool_call", { toolCallId: "t1", title: "Edit", name: "edit", kind: "edit", status: "in_progress", locations: [{ path: "a.ts" }], rawInput: { p: 1 } }))
    expect(started[0]).toMatchObject({ kind: "tool-call", callId: "t1", phase: "started", category: "edit" })
    const updated = n(acp("tool_call_update", {
      toolCallId: "t1",
      title: "Edit",
      status: "completed",
      content: [
        { type: "diff", path: "a.ts", diff: "-a\n+b" },
        { type: "terminal", output: "ok" },
      ],
    }))
    expect(updated.map(e => e.kind)).toEqual(["tool-call", "file-diff", "command-output"])
  })

  test("search kind also emits web-search", () => {
    const n = createAcpNormalizer()
    const out = n(acp("tool_call", { toolCallId: "s", title: "q", name: "search", kind: "search", status: "completed" }))
    expect(out.some(e => e.kind === "web-search")).toBe(true)
  })

  test("plan, commands, mode, session-info, usage, compaction", () => {
    const n = createAcpNormalizer()
    expect(n(acp("plan", { entries: [{ content: "x", status: "pending", priority: "high" }] }))[0]).toMatchObject({ kind: "plan", entries: [{ content: "x", status: "pending", priority: "high" }] })
    expect(n(acp("available_commands_update", { availableCommands: [{ name: "foo", description: "d" }] }))[0]).toMatchObject({ kind: "commands-update", commands: [{ name: "foo", description: "d" }] })
    expect(n(acp("current_mode_update", { currentModeId: "ask" }))[0]).toMatchObject({ kind: "mode-update", modeId: "ask" })
    expect(n(acp("config_option_update", { configOptions: [{ id: "model", value: "grok" }] }))[0]).toMatchObject({ kind: "mode-update", model: "grok" })
    expect(n(acp("session_info_update", { title: "T" }))[0]).toMatchObject({ kind: "session-info", title: "T" })
    expect(n(acp("usage_update", { used: 1, size: 2, cost: { amount: 0.1, currency: "USD" } }))[0]).toMatchObject({ kind: "usage", context: { used: 1, size: 2 }, cost: { amount: 0.1, currency: "USD" } })
    expect(n(acp("compaction_update", { compactionId: "c", status: "completed", summary: [{ type: "text", text: "sum" }] }))[0]).toMatchObject({ kind: "compaction", status: "completed", summary: "sum" })
    expect(n(acp("compaction_summary_chunk", { compactionId: "c", content: { type: "text", text: "ch" } }))[0]).toMatchObject({ kind: "compaction", status: "in_progress" })
  })

  test("plan_removed and plan_update", () => {
    const n = createAcpNormalizer()
    expect(n(acp("plan_removed"))[0]).toMatchObject({ kind: "plan", entries: [] })
    expect(n(acp("plan_update", { entries: [{ content: "y", status: "completed" }] }))[0]).toMatchObject({ kind: "plan" })
  })

  test("turn_completed and user_message_chunk ignored", () => {
    const n = createAcpNormalizer()
    expect(n(acp("turn_completed"))).toEqual([])
    expect(n(acp("user_message_chunk", { content: { type: "text", text: "u" } }))).toEqual([])
  })

  test("unknown sessionUpdate ignored", () => {
    const n = createAcpNormalizer()
    expect(n(acp("not_a_real_kind"))).toEqual([])
  })

  test("session/request_permission native frame", () => {
    const n = createAcpNormalizer()
    const out = n({ protocol: "native", value: { method: "session/request_permission", params: { toolCall: { toolCallId: "t" }, options: [{ optionId: "allow_once", kind: "allow_once", name: "Allow" }] } } })
    expect(out[0]).toMatchObject({ kind: "permission-request", options: [{ optionId: "allow_once", kind: "allow_once", label: "Allow" }] })
  })

  test("grok vendor wrapper unwraps inner update; turn_completed still ignored", () => {
    const n = createAcpNormalizer({ vendor: "grok" })
    const wrapped: AgentUpdate = {
      protocol: "native",
      value: { method: "_x.ai/session_notification", params: { update: { sessionUpdate: "agent_message_chunk", messageId: "m", content: { type: "text", text: "x" } } } },
    }
    expect(n(wrapped)[0]).toMatchObject({ kind: "assistant-delta", text: "x" })
    expect(n({ protocol: "native", value: { method: "_x.ai/session_notification", params: { update: { sessionUpdate: "turn_completed" } } } })).toEqual([])
    expect(createAcpNormalizer()(wrapped)).toEqual([])
  })

  test("replay flag is not interpreted by mapper (bodies still emitted)", () => {
    const n = createAcpNormalizer()
    expect(n(acp("available_commands_update", { availableCommands: [{ name: "a", description: "" }] }, true))[0].kind).toBe("commands-update")
  })
})
