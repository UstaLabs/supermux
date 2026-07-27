import { describe, expect, test } from "bun:test"
import {
  buildHandoffPrefill,
  defaultContinueAgent,
  isContinueAgent,
} from "./handoff-prefill"

describe("buildHandoffPrefill", () => {
  test("name + id + read_session only", () => {
    const text = buildHandoffPrefill({ name: "Fix auth race", id: "sess-abc" })
    expect(text).toContain("Continue work from the prior supermux session")
    expect(text).toContain("Session: Fix auth race")
    expect(text).toContain("Source session id: sess-abc")
    expect(text).toContain('read_session with session_id "sess-abc"')
    expect(text).toContain("workspace files as authoritative")
    expect(text).not.toContain("Original agent")
    expect(text).not.toContain("working directory")
    expect(text).not.toContain("status hints")
  })

  test("falls back when name empty", () => {
    const text = buildHandoffPrefill({ name: "  ", id: "x" })
    expect(text).toContain("Session: previous session")
    expect(text).toContain('read_session with session_id "x"')
  })
})

describe("defaultContinueAgent", () => {
  test("keeps source agent when known", () => {
    expect(defaultContinueAgent("codex")).toBe("codex")
  })
  test("defaults to claude for unknown", () => {
    expect(defaultContinueAgent("mystery")).toBe("claude")
    expect(defaultContinueAgent(undefined)).toBe("claude")
  })
  test("isContinueAgent", () => {
    expect(isContinueAgent("cursor")).toBe(true)
    expect(isContinueAgent("nope")).toBe(false)
  })
})
