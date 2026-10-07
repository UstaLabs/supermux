export type SubagentActionResult = { ok: true; via?: "direct" | "relay" } | { ok: false; status: number; error: string }

/**
 * HTTP result for a library error from messageSubagent/stopSubagent. "Not supported" and
 * "not possible yet" (a busy child, an OpenCode child that has not reported its session) are
 * 409; an unknown subagent is 404; bad input is 400; anything else is a real 500.
 */
export function subagentActionError(err: unknown): SubagentActionResult {
  const code = typeof err === "object" && err !== null && "code" in err ? String((err as { code: unknown }).code) : ""
  const message = err instanceof Error ? err.message : String(err)
  // "Not possible right now" in the agent's own words (subagent_unavailable carries its reason).
  if (code === "unsupported_operation" || code === "session_busy" || code === "subagent_unavailable" || code === "busy" || code === "runtime_closed") return { ok: false, status: 409, error: message }
  if (code === "subagent_not_found") return { ok: false, status: 404, error: message }
  if (code === "invalid_input") return { ok: false, status: 400, error: message }
  return { ok: false, status: 500, error: message }
}
