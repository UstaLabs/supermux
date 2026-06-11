import type { AgentEvent } from "../types"

// Translate a raw Claude lifecycle-hook name into a canonical AgentEvent.
// Returns null for hooks that carry no turn-state meaning (e.g. SessionStart),
// preserving the previous no-op behavior. PreToolUse/PostToolUse carry no
// call_id from Claude's hooks, so call_id is "".
export function claudeHookToAgentEvent(
  hookEvent: string,
  opts?: { tool?: string; errorType?: string; errorMessage?: string },
): AgentEvent | null {
  switch (hookEvent) {
    case "UserPromptSubmit":
      return { kind: "turn-start" }
    case "PreToolUse":
      return { kind: "tool-call", tool: opts?.tool ?? "", phase: "started", call_id: "" }
    case "PostToolUse":
      return { kind: "tool-call", tool: opts?.tool ?? "", phase: "completed", call_id: "" }
    case "Stop":
      return { kind: "turn-complete" }
    case "StopFailure":
      return {
        kind: "error",
        error: new Error(opts?.errorMessage ?? "Agent turn failed"),
        errorType: opts?.errorType ?? "error",
      }
    default:
      return null
  }
}
