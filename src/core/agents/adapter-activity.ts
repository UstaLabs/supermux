import type { AgentKind } from "./types"
import type { ActivityEvent } from "./claude/activity-event"
import { clipToolBody } from "./activity-body"
import { normalizeToolName } from "./tool-normalize"
import { clip, firstLine, pickString } from "./activity-format"
import { relativizePath } from "./path-relativize"

const TITLE_MAX = 120
/** Medium expand preview cap (body carries the full High payload). */
const DETAIL_MAX = 2000

interface ToolCallEventLike { tool: string; phase: "started" | "completed" | "failed"; call_id: string; detail?: unknown }

type DetailSummary = {
  summary: string
  rawSummary: string
  resultDetail: string
  inputDetail?: string
  /** Human "why" label when the agent provides one. */
  description?: string
}

function summarizeDetail(_agent: AgentKind, ev: ToolCallEventLike, workdir: string | undefined): DetailSummary {
  const obj = ev.detail && typeof ev.detail === "object" ? ev.detail as Record<string, unknown> : undefined
  if (!obj) return { summary: "", rawSummary: "", resultDetail: "" }

  // claude stream path (rare via adapter): extract from transcript-like blocks
  const rawPicked = pickString(obj, ["command", "path", "file", "name", "query", "pattern"])
  const summary = relativizePath(rawPicked, workdir)
  return { summary, rawSummary: rawPicked, resultDetail: "" }
}

export function toActivityEvents(agent: AgentKind, ev: ToolCallEventLike, now: number, workdir: string | undefined): ActivityEvent[] {
  const ts = new Date(now).toISOString()
  const callId = ev.call_id || undefined
  const {
    summary: relativeSummary,
    rawSummary,
    resultDetail,
    inputDetail,
    description,
  } = summarizeDetail(agent, ev, workdir)
  const { body, truncated: bodyTrunc } = clipToolBody(undefined)

  if (ev.phase === "started") {
    const tool = normalizeToolName(agent, ev.tool)
    const summary = firstLine(relativeSummary)
    const titleRaw = summary ? `${tool}: ${summary}` : tool
    const title = clip(titleRaw, TITLE_MAX)
    // Prefer full command / multi-line input for medium expand (not firstLine alone).
    const detailSrc = inputDetail ?? rawSummary
    const detail = clip(detailSrc, DETAIL_MAX)
    const truncated = title.truncated || detail.truncated || bodyTrunc
    return [{
      ts,
      kind: "tool",
      tool,
      title: title.text,
      detail: detail.text,
      phase: "started",
      ...(callId ? { callId } : {}),
      ...(truncated ? { truncated: true } : {}),
      ...(description ? { description } : {}),
      ...(body ? { body } : {}),
    }]
  }

  // Full multiline result for medium expand + High body (no firstLine destruction).
  const detail = clip(resultDetail.trim(), DETAIL_MAX)
  const truncated = detail.truncated || bodyTrunc
  return [{
    ts,
    kind: "tool_result",
    title: ev.phase === "failed" ? "error" : "done",
    detail: detail.text,
    phase: "completed",
    ...(truncated ? { truncated: true } : {}),
    ...(callId ? { callId } : {}),
    // Results rarely re-send "why"; keep description if the agent included one.
    ...(description ? { description } : {}),
    ...(body ? { body } : {}),
  }]
}
