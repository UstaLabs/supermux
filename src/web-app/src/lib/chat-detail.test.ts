import { expect, test } from "bun:test"
import {
  countToolsSince,
  effectiveChatDetail,
  formatLowWorkingStatus,
  isChatDetailImplemented,
  parseChatDetailLevel,
  turnBoundaryMs,
} from "./chat-detail"

test("parseChatDetailLevel accepts low/medium and clamps high/garbage", () => {
  expect(parseChatDetailLevel("low")).toBe("low")
  expect(parseChatDetailLevel("medium")).toBe("medium")
  expect(parseChatDetailLevel("high")).toBe("medium")
  expect(parseChatDetailLevel("nope")).toBe("medium")
  expect(parseChatDetailLevel(null)).toBe("medium")
  expect(parseChatDetailLevel(undefined)).toBe("medium")
})

test("effectiveChatDetail collapses high to medium", () => {
  expect(effectiveChatDetail("low")).toBe("low")
  expect(effectiveChatDetail("medium")).toBe("medium")
  expect(effectiveChatDetail("high")).toBe("medium")
})

test("isChatDetailImplemented", () => {
  expect(isChatDetailImplemented("low")).toBe(true)
  expect(isChatDetailImplemented("medium")).toBe(true)
  expect(isChatDetailImplemented("high")).toBe(false)
})

test("turnBoundaryMs uses last outbound message, not workingSince", () => {
  const msgs = [
    { direction: "outbound", ts: "2026-01-01T00:00:00.000Z" },
    { direction: "inbound", ts: "2026-01-01T00:00:10.000Z" },
    { direction: "outbound", ts: "2026-01-01T00:01:00.000Z" },
  ]
  const laterWorkingSince = Date.parse("2026-01-01T00:05:00.000Z")
  expect(turnBoundaryMs(msgs, laterWorkingSince)).toBe(Date.parse("2026-01-01T00:01:00.000Z"))
})

test("turnBoundaryMs falls back to workingSince when no outbound", () => {
  const msgs = [{ direction: "inbound", ts: "2026-01-01T00:00:00.000Z" }]
  expect(turnBoundaryMs(msgs, 12345)).toBe(12345)
  expect(turnBoundaryMs([], null)).toBe(0)
})

test("countToolsSince counts tools after boundary including waiting→resume gap", () => {
  const since = Date.parse("2026-01-01T00:01:00.000Z")
  const acts = [
    { kind: "tool", ts: "2026-01-01T00:00:30.000Z" }, // before user message
    { kind: "tool", ts: "2026-01-01T00:01:10.000Z" }, // A
    { kind: "tool", ts: "2026-01-01T00:01:20.000Z" }, // B
    { kind: "thinking", ts: "2026-01-01T00:01:25.000Z" },
    { kind: "tool_result", ts: "2026-01-01T00:01:30.000Z" },
    // wait gap — new workingSince would be later; tools still count
    { kind: "tool", ts: "2026-01-01T00:05:10.000Z" }, // C
    { kind: "tool", ts: "2026-01-01T00:05:20.000Z" }, // D
  ]
  expect(countToolsSince(acts, since)).toBe(4)
})

test("formatLowWorkingStatus matrix", () => {
  expect(formatLowWorkingStatus({
    baseLabel: "Working…",
    detail: "thinking",
    toolCount: 0,
    durationLabel: "12 seconds",
  })).toBe("Working… · 12 seconds")

  expect(formatLowWorkingStatus({
    baseLabel: "Working…",
    detail: "thinking",
    toolCount: 3,
    durationLabel: "45 seconds",
  })).toBe("Working… · 3 tools · 45 seconds")

  expect(formatLowWorkingStatus({
    baseLabel: "Working…",
    detail: "running",
    tool: "Bash",
    toolCount: 1,
    durationLabel: "4 seconds",
  })).toBe("Working… · Bash · 1 tool · 4 seconds")

  expect(formatLowWorkingStatus({
    baseLabel: "Working…",
    detail: "running",
    tool: "Edit",
    toolCount: 5,
    durationLabel: "1 minute 2 seconds",
  })).toBe("Working… · Edit · 5 tools · 1 minute 2 seconds")

  expect(formatLowWorkingStatus({
    baseLabel: "Working…",
    detail: "running",
    tool: null,
    toolCount: 2,
    durationLabel: "9 seconds",
  })).toBe("Working… · 2 tools · 9 seconds")
})
