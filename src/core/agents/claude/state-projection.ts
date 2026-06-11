import type { AgentAdapter } from "../types"

// Project a Claude adapter's canonical *state* events onto the broker's turn-state
// store + error notifier. State-ONLY by design: Claude activity comes from the
// transcript tailer (richer than hook tool names), and Claude replies go through
// the direct onOutbound → onAssistantMessage path (so its delivery-failure throw
// reaches the shim). So this intentionally does NOT touch activity or
// assistant-message — only the turn-phase state and error surfacing.
export function wireClaudeStateEvents(
  adapter: AgentAdapter,
  deps: {
    onState: (event: "UserPromptSubmit" | "PreToolUse" | "PostToolUse" | "Stop", tool?: string) => void
    onError: (errorType: string, message: string) => void
  },
): void {
  adapter.on("turn-start", () => deps.onState("UserPromptSubmit"))
  adapter.on("tool-call", (ev: any) => {
    if (ev?.phase === "started") deps.onState("PreToolUse", ev.tool)
    else deps.onState("PostToolUse")
  })
  adapter.on("turn-complete", () => deps.onState("Stop"))
  adapter.on("error", (ev: any) => {
    deps.onError(String(ev?.errorType ?? "error"), String(ev?.error?.message ?? ev?.error ?? "agent error"))
  })
}
