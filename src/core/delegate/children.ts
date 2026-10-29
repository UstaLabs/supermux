// Delegated children (spec 2026-10-28 §3, §3.1). A child session's card in its parent's subagent
// list, kept in step with the child's own turns, replies and agent state; and supermux's notices to
// the parent when the child crashes, is killed, or a turn of it fails.
import type { Session } from "../session-manager/types"
import type { SubagentStore } from "../session-manager/subagent-store"

/** supermux's own notices come from this sender (spec §1.1). */
export const SUPERMUX_SENDER = { name: "supermux", id: "supermux" } as const

export type DelegatedChildrenDeps = {
  /** A session row, archived ones included. */
  session(id: string): Session | undefined
  /** True while the session is not archived. */
  isLive(id: string): boolean
  subagents: Pick<SubagentStore, "applyBody">
  /** The child's newest assistant reply (MessageStore.lastReply). */
  lastReply(sessionId: string): string | undefined
  /** "Codex · GPT-6": a card's description. */
  describe(child: Session): string
  /** A message to a session's inbox from SUPERMUX_SENDER. */
  notify(sessionId: string, text: string): void
  /** A council member has the council card, not a subagent card (spec §3.1, §5). */
  isCouncilMember(id: string): boolean
}

export class DelegatedChildren {
  constructor(private readonly deps: DelegatedChildrenDeps) {}

  /** True when `subagentId` in `parentId`'s subagent list is a delegated child session. */
  isChild(parentId: string, subagentId: string): boolean {
    if (this.deps.isCouncilMember(subagentId)) return false
    return this.deps.session(subagentId)?.parent_session_id === parentId
  }

  /** delegate created `child`: its card in the parent's subagent list (spec §3.1). */
  started(child: Session, task: string, callId?: string): void {
    if (!child.parent_session_id) return
    this.deps.subagents.applyBody(child.parent_session_id, {
      ...this.card(child),
      phase: "started",
      prompt: task,
      ...(callId ? { parentCallId: callId } : {}),
    })
  }

  /** A turn started: the card runs again (a message woke the child). */
  turnStarted(childId: string): void {
    const child = this.childOf(childId)
    // The full card: a finished one can have been evicted (FINISHED_KEEP) and is recreated from this body.
    if (child) this.deps.subagents.applyBody(child.parent_session_id!, { ...this.card(child), phase: "resumed" })
  }

  turnCompleted(childId: string): void {
    this.fold(childId, { phase: "completed" })
  }

  /** The child's newest reply is the card's result. */
  replied(childId: string, text: string): void {
    this.fold(childId, { phase: "progress", result: text })
  }

  /** What the child is doing now (its agent state: a tool name, "Thinking"). */
  activity(childId: string, activity: string): void {
    this.fold(childId, { phase: "progress", activity })
  }

  /** The child's core session failed. */
  crashed(childId: string): void {
    const child = this.childOf(childId)
    if (!child) return
    // The full card, as in turnStarted: an evicted one is recreated whole.
    this.deps.subagents.applyBody(child.parent_session_id!, { ...this.card(child), phase: "failed" })
    this.notice(child, "crashed")
  }

  /** A turn of the child ended in an error and it still runs (decision #17): its card fails with the error, and a live parent hears it. */
  turnFailed(childId: string, error: string): void {
    const child = this.childOf(childId)
    if (!child) return
    const parent = child.parent_session_id!
    // The full card, as in turnStarted: an evicted one is recreated whole.
    this.deps.subagents.applyBody(parent, { ...this.card(child), phase: "failed", result: error })
    // A message to an archived session restores it (spec §1.3 step 0), so an archived parent hears nothing.
    if (this.deps.isLive(parent)) this.deps.notify(parent, `Your subagent [${child.name}:${child.id}] stopped with an error:\n${error}`)
  }

  /**
   * The child was archived. A child killed by the cascade (its parent being killed) sends no
   * notice and leaves its card alone: the parent's list goes with the parent (spec §4).
   */
  archived(child: Session, how: { cascade?: boolean; endedBy?: "parent" | "client" }): void {
    if (!child.parent_session_id || how.cascade) return
    if (this.deps.isCouncilMember(child.id)) return
    this.deps.subagents.applyBody(child.parent_session_id, {
      ...this.card(child),
      phase: "cancelled",
      ...(how.endedBy ? { endedBy: how.endedBy } : {}),
      canStop: false,
      cannotStopReason: "It was killed",
    })
    // A parent that killed it with kill_session needs no notice: the tool only answers "killed" once
    // the child is stopped and archived (decided 2026-10-29).
    if (how.endedBy !== "parent") this.notice(child, "was killed")
  }

  /**
   * A live child. An archived child's late events are ignored: its card is final ("cancelled" must not
   * turn "failed" with a second notice), and its parent's list may already be cleared.
   */
  private childOf(childId: string): Session | undefined {
    if (this.deps.isCouncilMember(childId)) return undefined
    const child = this.deps.session(childId)
    return child?.parent_session_id && this.deps.isLive(childId) ? child : undefined
  }

  private card(child: Session) {
    return {
      kind: "subagent" as const,
      subagentId: child.id,
      name: child.name,
      description: this.deps.describe(child),
      ...(child.model ? { model: child.model } : {}),
      messaging: "direct" as const,
      canMessage: true,
      canStop: true,
      actionsSource: "derived" as const,
    }
  }

  private fold(childId: string, body: { phase: "completed" | "progress"; result?: string; activity?: string }): void {
    const child = this.childOf(childId)
    if (child) this.deps.subagents.applyBody(child.parent_session_id!, { kind: "subagent", subagentId: child.id, ...body })
  }

  /** Spec §3: the child named [name:id], what happened, and its last message. Only a live parent hears it. */
  private notice(child: Session, what: "crashed" | "was killed"): void {
    const parent = child.parent_session_id
    // A message to an archived session restores it (spec §1.3 step 0), so an archived parent hears nothing.
    if (!parent || !this.deps.isLive(parent)) return
    const last = this.deps.lastReply(child.id)
    this.deps.notify(parent, [`Your subagent [${child.name}:${child.id}] ${what}.`, last ? `Its last message:\n${last}` : "It sent no message."].join("\n"))
  }
}
