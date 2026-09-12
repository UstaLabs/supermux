// Codex thread-item types that ARE tools (→ activity tool-cards). Everything
// else (userMessage, agentMessage, reasoning, error, todo, tokenCount, …) is
// NOT a tool. Allowlist (not blocklist) so unknown non-tool item types can
// never leak in as junk cards. Extend this when Codex adds a tool type.
export const CODEX_TOOL_ITEM_TYPES = new Set<string>([
  "command_execution", "commandExecution",
  "fileChange", "file_change",
  "webSearch", "web_search",
  "mcpToolCall", "mcp_tool_call",
  "dynamicToolCall", "dynamic_tool_call",
])

export function isCodexToolItem(type: string | undefined): boolean {
  return typeof type === "string" && CODEX_TOOL_ITEM_TYPES.has(type)
}

export function isCodexWebSearchItem(type: string | undefined): boolean {
  return type === "webSearch" || type === "web_search"
}

export function hasCodexWebSearchPreview(item: Record<string, unknown>): boolean {
  if (typeof item.query === "string" && item.query.trim()) return true
  const action = item.action
  if (!action || typeof action !== "object") return false
  const row = action as Record<string, unknown>
  return [row.query, row.url, row.pattern].some((value) => typeof value === "string" && value.trim())
    || (Array.isArray(row.queries) && row.queries.some((value) => typeof value === "string" && value.trim()))
}

export function codexToolName(item: { type?: string; tool?: unknown }): string {
  return item.type === "dynamicToolCall" || item.type === "dynamic_tool_call"
    ? String(item.tool || item.type)
    : String(item.type)
}

export function isCodexToolFailed(item: Record<string, unknown>): boolean {
  const exitCode = item.exitCode ?? item.exit_code
  return item.status === "failed" || item.status === "declined"
    || (exitCode != null && exitCode !== 0)
}

export type CodexNativeItemEvents = {
  emitTool: (event: {
    kind: "tool-call"
    tool: string
    phase: "started" | "completed" | "failed"
    call_id: string
    detail: unknown
  }) => void
  emitAssistant?: (text: string) => void
}

export type CodexNativeItemState = {
  deferredWebSearchStarts: Set<string>
}

export function createCodexNativeItemState(): CodexNativeItemState {
  return { deferredWebSearchStarts: new Set() }
}

export function handleCodexItemStarted(
  item: Record<string, unknown> | undefined,
  state: CodexNativeItemState,
  events: CodexNativeItemEvents,
): void {
  if (!isCodexToolItem(item?.type as string | undefined) || !item) return
  const callId = String(item.id ?? "")
  if (isCodexWebSearchItem(item.type as string) && !hasCodexWebSearchPreview(item)) {
    state.deferredWebSearchStarts.add(callId)
    return
  }
  events.emitTool({
    kind: "tool-call",
    tool: codexToolName(item),
    phase: "started",
    call_id: callId,
    detail: item,
  })
}

export function handleCodexItemCompleted(
  item: Record<string, unknown> | undefined,
  state: CodexNativeItemState,
  events: CodexNativeItemEvents,
): void {
  if (!item) return
  if (item.type === "agentMessage" && typeof item.text === "string") {
    events.emitAssistant?.(item.text)
  }
  if (!isCodexToolItem(item.type as string | undefined)) return
  const callId = String(item.id ?? "")
  const tool = codexToolName(item)
  const failed = isCodexToolFailed(item)
  if (state.deferredWebSearchStarts.delete(callId)) {
    events.emitTool({ kind: "tool-call", tool, phase: "started", call_id: callId, detail: item })
  }
  events.emitTool({
    kind: "tool-call",
    tool,
    phase: failed ? "failed" : "completed",
    call_id: callId,
    detail: item,
  })
}
