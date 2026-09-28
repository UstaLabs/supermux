// src/core/session-manager/subagent-store.ts
import { EventEmitter } from "events"
import type { NormalizedBody } from "../../../packages/supermux-core/src/events/normalized.js"

export type SubagentBody = Extract<NormalizedBody, { kind: "subagent" }>
export type SubagentStatus = "running" | "completed" | "failed" | "cancelled"

/**
 * The latest view of one subagent, as clients see it (snapshot `subagents` and the
 * `subagent_update` frame). Times are epoch ms. `parentCallId` is the parent tool call that
 * spawned it: the activity row with that `callId` is the subagent's card.
 */
export interface Subagent {
  id: string
  name?: string
  description?: string
  prompt?: string
  background?: boolean
  status: SubagentStatus
  activity?: string
  stats: { toolCalls?: number; tokens?: number; durationMs?: number; turns?: number }
  result?: string
  /** True when [result] (or [prompt]) was cut to [RESULT_MAX] characters. */
  resultClipped?: boolean
  model?: string
  messaging?: "direct" | "relay" | "none"
  parentCallId?: string
  nativeId?: string
  startedAt: number
  endedAt?: number
  lastActivityAt: number
}

/** Finished subagents kept per session (running ones are always kept). */
export const FINISHED_KEEP = 20
/** Result / prompt size cap. */
export const RESULT_MAX = 8000

type Entry = { view: Subagent; agentActivity: boolean; agentToolCalls: boolean }

const TERMINAL: ReadonlySet<string> = new Set(["completed", "failed", "cancelled"])

function clip(text: string): { text: string; clipped: boolean } {
  return text.length > RESULT_MAX ? { text: text.slice(0, RESULT_MAX), clipped: true } : { text, clipped: false }
}

/**
 * Per-session subagent registry. In-memory like BackgroundTaskStore: it survives a client
 * reload (the snapshot carries it) but not a broker restart.
 *
 * Emits `change(sessionId, view)` with the full latest view after every fold, and
 * `clear(sessionId)` when a session's list is dropped.
 */
export class SubagentStore extends EventEmitter {
  private readonly bySession = new Map<string, Map<string, Entry>>()

  /** Fold one normalized `subagent` body. */
  applyBody(sessionId: string, body: SubagentBody, now: number = Date.now()): Subagent {
    const entry = this.entry(sessionId, body.subagentId, now)
    const view = entry.view
    if (body.name) view.name = body.name
    if (body.description) view.description = body.description
    if (body.prompt) {
      const p = clip(body.prompt)
      view.prompt = p.text
      if (p.clipped) view.resultClipped = true
    }
    if (typeof body.background === "boolean") view.background = body.background
    if (body.model) view.model = body.model
    if (body.messaging) view.messaging = body.messaging
    if (body.nativeId) view.nativeId = body.nativeId
    // The spawning call is the card anchor; a later resume (SendMessage, Task resume) must not move it.
    if (body.parentCallId && !view.parentCallId) view.parentCallId = body.parentCallId
    if (body.activity) {
      view.activity = body.activity
      entry.agentActivity = true
    }
    if (body.stats) {
      const stats = { ...view.stats }
      for (const key of ["toolCalls", "tokens", "durationMs", "turns"] as const) {
        const value = body.stats[key]
        if (typeof value === "number") stats[key] = value
      }
      if (typeof body.stats.toolCalls === "number") entry.agentToolCalls = true
      view.stats = stats
    }
    if (body.phase === "started" || body.phase === "resumed") {
      view.status = "running"
      delete view.endedAt
    } else if (TERMINAL.has(body.phase)) {
      view.status = body.phase as SubagentStatus
      view.endedAt = now
    }
    if (typeof body.result === "string" && body.result) {
      const r = clip(body.result)
      view.result = r.text
      if (r.clipped) view.resultClipped = true
    }
    view.lastActivityAt = now
    return this.commit(sessionId, entry)
  }

  /**
   * A child tool call started. Stands in for the agent's own activity line / tool count when
   * it sends none (Codex has no stats, OpenCode/Cursor no progress at all).
   */
  applyChildActivity(sessionId: string, subagentId: string, activity: string, now: number = Date.now()): Subagent {
    const entry = this.entry(sessionId, subagentId, now)
    if (!entry.agentActivity && activity) entry.view.activity = activity
    if (!entry.agentToolCalls) entry.view.stats = { ...entry.view.stats, toolCalls: (entry.view.stats.toolCalls ?? 0) + 1 }
    entry.view.lastActivityAt = now
    return this.commit(sessionId, entry)
  }

  /** The agent process died: nothing it was running can still finish. */
  abandonRunning(sessionId: string, now: number = Date.now()): void {
    const list = this.bySession.get(sessionId)
    if (!list) return
    for (const entry of list.values()) {
      if (entry.view.status !== "running") continue
      entry.view.status = "cancelled"
      entry.view.endedAt = now
      entry.view.lastActivityAt = now
      this.commit(sessionId, entry)
    }
  }

  get(sessionId: string): Subagent[] {
    const list = this.bySession.get(sessionId)
    if (!list) return []
    return [...list.values()].map((e) => copy(e.view)).sort((a, b) => a.startedAt - b.startedAt)
  }

  find(sessionId: string, subagentId: string): Subagent | undefined {
    const entry = this.bySession.get(sessionId)?.get(subagentId)
    return entry ? copy(entry.view) : undefined
  }

  /** Running subagents that outlive their parent's turn (the parent resumes when they finish). */
  backgroundRunning(sessionId: string): number {
    const list = this.bySession.get(sessionId)
    if (!list) return 0
    let n = 0
    for (const e of list.values()) if (e.view.status === "running" && e.view.background === true) n++
    return n
  }

  clear(sessionId: string): void {
    if (!this.bySession.delete(sessionId)) return
    this.emit("clear", sessionId)
  }

  private entry(sessionId: string, subagentId: string, now: number): Entry {
    let list = this.bySession.get(sessionId)
    if (!list) {
      list = new Map()
      this.bySession.set(sessionId, list)
    }
    let entry = list.get(subagentId)
    if (!entry) {
      entry = { view: { id: subagentId, status: "running", stats: {}, startedAt: now, lastActivityAt: now }, agentActivity: false, agentToolCalls: false }
      list.set(subagentId, entry)
    }
    return entry
  }

  private commit(sessionId: string, entry: Entry): Subagent {
    this.evict(sessionId)
    const view = copy(entry.view)
    this.emit("change", sessionId, view)
    return view
  }

  private evict(sessionId: string): void {
    const list = this.bySession.get(sessionId)
    if (!list) return
    const finished = [...list.values()].filter((e) => e.view.status !== "running")
    if (finished.length <= FINISHED_KEEP) return
    finished.sort((a, b) => (a.view.endedAt ?? 0) - (b.view.endedAt ?? 0))
    for (const e of finished.slice(0, finished.length - FINISHED_KEEP)) list.delete(e.view.id)
  }
}

function copy(view: Subagent): Subagent {
  return { ...view, stats: { ...view.stats } }
}
