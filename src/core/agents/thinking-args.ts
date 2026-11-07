// Card #76: an agent's thinking text reaches supermux only when the agent is asked for it. Both switches are
// process flags: they apply when a session's CLI next starts, and a keeper-held session runs on unchanged
// until then. "none" (or any value the CLI does not know) leaves the agent's own default.

const CLAUDE_DISPLAYS = new Set(["summarized", "omitted", "highlights"])
const CODEX_SUMMARIES = new Set(["auto", "concise", "detailed"])

/**
 * Claude Code's hidden `--thinking-display`. Without it, its stream-json carries thinking deltas with no
 * text. A changed display costs one prompt-cache miss on the session's first request after a restart.
 */
export function claudeThinkingArgs(env: Record<string, string | undefined> = process.env): string[] {
  const value = (env.MUX_CLAUDE_THINKING_DISPLAY ?? "summarized").trim()
  return CLAUDE_DISPLAYS.has(value) ? ["--thinking-display", value] : []
}

/** Codex writes reasoning summaries (item/reasoning/summaryTextDelta) only when configured to. */
export function codexReasoningArgs(env: Record<string, string | undefined> = process.env): string[] {
  const value = (env.MUX_CODEX_REASONING_SUMMARY ?? "auto").trim()
  return CODEX_SUMMARIES.has(value) ? ["-c", `model_reasoning_summary=${JSON.stringify(value)}`] : []
}
