/**
 * Shared vocabulary for what a client may do with a subagent. Each driver decides the values from
 * its OWN agent's signals (see the per-driver `actions()` functions); this module only holds the
 * shape, the user-facing reasons and the change check, so the wording stays consistent.
 */
import type { NormalizedBody, SubagentActionsSource } from "./events/normalized.js"

export type SubagentActions = {
  canMessage: boolean
  canStop: boolean
  actionsSource: SubagentActionsSource
  cannotMessageReason?: string
  cannotStopReason?: string
}

/**
 * Driver-synthesized native frame: re-emit a subagent's current state (flags changed outside any
 * lifecycle frame, e.g. the client stopped it or a native capability arrived). `{ method, params:
 * { subagentId } }` for every driver; Claude frames also carry `type` so its mapper sees them.
 */
export const SUBAGENT_STATE_METHOD = "supermux/subagent-state"

export const REASON = {
  finished: "It has already finished",
  stopped: "It has already stopped",
  ended: "It has already ended",
  notRunning: "It isn't running",
  claudeClientStopped: "Stopped by you — Claude can't resume it",
  cursorNoStop: "Cursor can't stop subagents",
  grokRunning: "Grok can message it once it finishes",
  busy: "It is still answering your last message",
  codexNoInput: "Codex doesn't accept messages for this subagent",
  opencodeNoSession: "OpenCode hasn't reported its session yet",
} as const

/** Why Stop is off for a run that already ended. */
export function endedReason(status: "completed" | "failed" | "cancelled" | string | undefined): string {
  if (status === "completed") return REASON.finished
  if (status === "cancelled") return REASON.stopped
  return REASON.ended
}

/** The body fields for a set of actions (reasons only when the action is off). */
export function actionFields(actions: SubagentActions): Partial<Extract<NormalizedBody, { kind: "subagent" }>> {
  return {
    canMessage: actions.canMessage,
    canStop: actions.canStop,
    actionsSource: actions.actionsSource,
    ...(!actions.canMessage && actions.cannotMessageReason ? { cannotMessageReason: actions.cannotMessageReason } : {}),
    ...(!actions.canStop && actions.cannotStopReason ? { cannotStopReason: actions.cannotStopReason } : {}),
  }
}

export function actionsKey(actions: SubagentActions | undefined): string {
  if (!actions) return ""
  return JSON.stringify(actionFields(actions))
}

/** Keep a reason short and on one line (an agent's refusal text can be a paragraph). */
export function shortReason(text: string, max = 160): string {
  const line = text.replace(/\s+/g, " ").trim()
  if (line.length <= max) return line
  const sentence = line.slice(0, max).match(/^(.*?[.!?])\s/)
  return sentence ? sentence[1]! : `${line.slice(0, max - 1)}…`
}
