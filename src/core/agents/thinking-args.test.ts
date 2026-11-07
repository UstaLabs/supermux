import { expect, test } from "bun:test"
import { claudeThinkingArgs, codexReasoningArgs } from "./thinking-args"

test("Claude streams summarized thinking unless told otherwise", () => {
  expect(claudeThinkingArgs({})).toEqual(["--thinking-display", "summarized"])
  expect(claudeThinkingArgs({ MUX_CLAUDE_THINKING_DISPLAY: "highlights" })).toEqual(["--thinking-display", "highlights"])
  expect(claudeThinkingArgs({ MUX_CLAUDE_THINKING_DISPLAY: "none" })).toEqual([])
  expect(claudeThinkingArgs({ MUX_CLAUDE_THINKING_DISPLAY: "bogus" })).toEqual([])
})

test("Codex writes reasoning summaries unless told otherwise", () => {
  expect(codexReasoningArgs({})).toEqual(["-c", 'model_reasoning_summary="auto"'])
  expect(codexReasoningArgs({ MUX_CODEX_REASONING_SUMMARY: "detailed" })).toEqual(["-c", 'model_reasoning_summary="detailed"'])
  expect(codexReasoningArgs({ MUX_CODEX_REASONING_SUMMARY: "none" })).toEqual([])
  expect(codexReasoningArgs({ MUX_CODEX_REASONING_SUMMARY: "bogus" })).toEqual([])
})
