/** Chat transcript density: low | medium | high. */

export type ChatDetailLevel = "low" | "medium" | "high"

export const CHAT_DETAIL_LEVELS = ["low", "medium", "high"] as const satisfies readonly ChatDetailLevel[]

export const CHAT_DETAIL_DEFAULT: ChatDetailLevel = "medium"

export const CHAT_DETAIL_LABELS: Record<ChatDetailLevel, string> = {
  low: "Low",
  medium: "Medium",
  high: "High",
}

/** Levels that are fully implemented for rendering. */
export function isChatDetailImplemented(level: ChatDetailLevel): boolean {
  return level === "low" || level === "medium" || level === "high"
}

/** Effective render mode — all three levels are live. */
export function effectiveChatDetail(level: ChatDetailLevel): ChatDetailLevel {
  if (level === "low" || level === "high") return level
  return "medium"
}

/** Persistable levels — garbage clamps to medium; high is accepted. */
export function parseChatDetailLevel(raw: unknown): ChatDetailLevel {
  if (raw === "low" || raw === "medium" || raw === "high") return raw
  return "medium"
}

/** Turn boundary for "tools this turn": last outbound user message ts, else workingSince, else 0. */
export function turnBoundaryMs(
  messages: { direction: string; ts: string }[],
  workingSince?: number | null,
): number {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]!.direction === "outbound") {
      return new Date(messages[i]!.ts).getTime()
    }
  }
  return workingSince ?? 0
}

export function countToolsSince(
  acts: { kind: string; ts: string }[],
  sinceMs: number,
): number {
  return acts.filter((e) => e.kind === "tool" && new Date(e.ts).getTime() >= sinceMs).length
}

export type LowWorkingStatusInput = {
  /** Platform baseline, e.g. "Working…" */
  baseLabel: string
  detail?: "thinking" | "running" | null
  tool?: string | null
  toolCount: number
  /** Already formatted, e.g. formatDuration output */
  durationLabel: string
}

/**
 * Segments after baseLabel, joined with " · ".
 * - No tools segment when toolCount === 0
 * - Tool name only when detail === "running" && tool
 * - Duration last when non-empty
 */
export function formatLowWorkingStatus(input: LowWorkingStatusInput): string {
  const parts: string[] = [input.baseLabel]
  if (input.detail === "running" && input.tool) parts.push(input.tool)
  if (input.toolCount > 0) {
    parts.push(input.toolCount === 1 ? "1 tool" : `${input.toolCount} tools`)
  }
  if (input.durationLabel) parts.push(input.durationLabel)
  return parts.join(" · ")
}
