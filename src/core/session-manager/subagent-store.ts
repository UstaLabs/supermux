// src/core/session-manager/subagent-store.ts
import { EventEmitter } from "events"
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs"
import { dirname } from "node:path"
import type { NormalizedBody } from "../../../packages/supermux-core/src/events/normalized.js"
import type { SubagentSnapshot } from "../../../packages/supermux-core/src/types.js"
import type { ActivityEvent } from "../agents/claude/activity-event"

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
  /** Who ended the run (terminal statuses): itself, its parent model, or the user. */
  endedBy?: "self" | "parent" | "client"
  /** What the user can do now — straight from the agent (see supermux-core API "Truthful subagent actions"). */
  canMessage?: boolean
  canStop?: boolean
  actionsSource?: "native" | "derived"
  cannotMessageReason?: string
  cannotStopReason?: string
  /**
   * How many messages the subagent itself has produced (its "from" rows), monotonic. Unread is
   * the client's business: it remembers the count it last showed and badges the difference.
   */
  replies?: number
}

/** Finished subagents kept per session (running ones are always kept). */
export const FINISHED_KEEP = 20
/** Result / prompt size cap. */
export const RESULT_MAX = 8000

type Entry = { view: Subagent; agentActivity: boolean; agentToolCalls: boolean }

/** A `subagent_message` activity row: one message in a subagent's conversation. */
export type SubagentMessageRow = ActivityEvent & { kind: "subagent_message"; subagentId: string; direction: "to" | "from" }

/** Conversation rows kept per subagent (oldest dropped first). */
export const MESSAGES_KEEP = 60

export type SubagentStoreOptions = {
  /** JSON file the store survives broker restarts in; omitted = in memory only. */
  file?: string
  /** Coalescing delay before a save (default 250 ms). */
  saveDelayMs?: number
}

const TERMINAL: ReadonlySet<string> = new Set(["completed", "failed", "cancelled"])

function clip(text: string): { text: string; clipped: boolean } {
  return text.length > RESULT_MAX ? { text: text.slice(0, RESULT_MAX), clipped: true } : { text, clipped: false }
}

/**
 * Per-session subagent registry. It survives a client reload (the snapshot carries it) and, with
 * a `file`, a broker restart: views and each subagent's conversation are saved (coalesced).
 *
 * Emits `change(sessionId, view)` with the full latest view after every fold, and
 * `clear(sessionId)` when a session's list is dropped.
 */
export class SubagentStore extends EventEmitter {
  private readonly bySession = new Map<string, Map<string, Entry>>()
  private readonly threads = new Map<string, SubagentMessageRow[]>()
  private saveTimer?: ReturnType<typeof setTimeout>
  private saving?: Promise<void>

  constructor(private readonly options: SubagentStoreOptions = {}) {
    super()
  }

  /** Read the saved state (a missing or unreadable file is an empty store). */
  load(): void {
    if (!this.options.file) return
    let data: { sessions?: Record<string, { subagents?: Subagent[]; messages?: SubagentMessageRow[] }> }
    try { data = JSON.parse(readFileSync(this.options.file, "utf8")) } catch { return }
    for (const [sessionId, saved] of Object.entries(data.sessions ?? {})) {
      const list = new Map<string, Entry>()
      for (const view of saved.subagents ?? []) {
        if (!view || typeof view.id !== "string") continue
        list.set(view.id, { view: copy({ ...view, stats: view.stats ?? {} }), agentActivity: !!view.activity, agentToolCalls: typeof view.stats?.toolCalls === "number" })
      }
      if (list.size) this.bySession.set(sessionId, list)
      const messages = (saved.messages ?? []).filter((m) => m && m.kind === "subagent_message" && typeof m.subagentId === "string")
      if (messages.length) this.threads.set(sessionId, messages)
    }
  }

  /** Wait for any pending save (tests, shutdown). */
  async flush(): Promise<void> {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer)
      this.saveTimer = undefined
      this.writeNow()
    }
    await this.saving
  }

  private scheduleSave(): void {
    if (!this.options.file || this.saveTimer) return
    this.saveTimer = setTimeout(() => {
      this.saveTimer = undefined
      this.writeNow()
    }, this.options.saveDelayMs ?? 250)
    this.saveTimer.unref?.()
  }

  private writeNow(): void {
    const file = this.options.file
    if (!file) return
    const sessions: Record<string, { subagents: Subagent[]; messages: SubagentMessageRow[] }> = {}
    for (const sessionId of new Set([...this.bySession.keys(), ...this.threads.keys()])) {
      sessions[sessionId] = { subagents: this.get(sessionId), messages: this.messages(sessionId) }
    }
    try {
      mkdirSync(dirname(file), { recursive: true })
      const temp = `${file}.${process.pid}.tmp`
      writeFileSync(temp, JSON.stringify({ version: 1, sessions }), { mode: 0o600 })
      renameSync(temp, file)
    } catch { /* persistence is best effort: the live store is unaffected */ }
  }

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
      delete view.endedBy
    } else if (TERMINAL.has(body.phase)) {
      view.status = body.phase as SubagentStatus
      view.endedAt = now
      if (body.endedBy) view.endedBy = body.endedBy
      else delete view.endedBy
    }
    applyActions(view, body)
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

  /**
   * The library's registry after a (re)start: what the agent's driver says is true now (a fresh
   * process no longer runs what the old one ran; names and flags carried over).
   */
  applySnapshot(sessionId: string, snap: SubagentSnapshot, now: number = Date.now()): Subagent {
    const entry = this.entry(sessionId, snap.subagentId, now)
    const view = entry.view
    for (const key of ["name", "description", "model", "messaging", "nativeId"] as const) {
      const value = snap[key]
      if (typeof value === "string" && value) (view as unknown as Record<string, unknown>)[key] = value
    }
    if (typeof snap.background === "boolean") view.background = snap.background
    if (snap.spawnCallId && !view.parentCallId) view.parentCallId = snap.spawnCallId
    if (snap.status !== view.status) {
      view.status = snap.status
      if (snap.status === "running") delete view.endedAt
      else view.endedAt = view.endedAt ?? now
    }
    if (snap.status !== "running" && snap.endedBy) view.endedBy = snap.endedBy
    if (snap.status === "running") delete view.endedBy
    applyActions(view, snap)
    return this.commit(sessionId, entry)
  }

  /**
   * One message of a subagent's conversation (its prompt, a reply, a message sent to it). Kept
   * bounded per subagent and persisted; a reply (`from`) bumps the view's `replies`.
   */
  recordMessage(sessionId: string, row: SubagentMessageRow, now: number = Date.now()): SubagentMessageRow {
    const text = clip(row.text ?? "")
    const kept: SubagentMessageRow = { ...row, text: text.text, ...(text.clipped || row.truncated ? { truncated: true } : {}) }
    const list = this.threads.get(sessionId) ?? []
    list.push(kept)
    const mine = list.filter((m) => m.subagentId === row.subagentId)
    if (mine.length > MESSAGES_KEEP) list.splice(list.indexOf(mine[0]!), 1)
    this.threads.set(sessionId, list)
    if (row.direction === "from") {
      const entry = this.entry(sessionId, row.subagentId, now)
      entry.view.replies = (entry.view.replies ?? 0) + 1
      entry.view.lastActivityAt = now
      this.commit(sessionId, entry)
    } else this.scheduleSave()
    return kept
  }

  /** Sessions the store holds anything for. */
  sessionIds(): string[] {
    return [...new Set([...this.bySession.keys(), ...this.threads.keys()])]
  }

  /** Every kept conversation row of a session, in arrival order. */
  messages(sessionId: string): SubagentMessageRow[] {
    return (this.threads.get(sessionId) ?? []).map((m) => ({ ...m }))
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
      // Nothing left to stop; whether it can be messaged again is the agent's call on resume.
      entry.view.canStop = false
      entry.view.cannotStopReason = "Its session ended"
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
    const hadThread = this.threads.delete(sessionId)
    if (!this.bySession.delete(sessionId)) {
      if (hadThread) this.scheduleSave()
      return
    }
    this.scheduleSave()
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
    this.scheduleSave()
    this.emit("change", sessionId, view)
    return view
  }

  private evict(sessionId: string): void {
    const list = this.bySession.get(sessionId)
    if (!list) return
    const finished = [...list.values()].filter((e) => e.view.status !== "running")
    if (finished.length <= FINISHED_KEEP) return
    finished.sort((a, b) => (a.view.endedAt ?? 0) - (b.view.endedAt ?? 0))
    for (const e of finished.slice(0, finished.length - FINISHED_KEEP)) {
      list.delete(e.view.id)
      const thread = this.threads.get(sessionId)
      if (thread) this.threads.set(sessionId, thread.filter((m) => m.subagentId !== e.view.id))
    }
  }
}

/** Fold the action flags a body or registry entry carries (absent = unchanged). */
function applyActions(view: Subagent, from: {
  canMessage?: boolean; canStop?: boolean; actionsSource?: "native" | "derived"; cannotMessageReason?: string; cannotStopReason?: string
}): void {
  if (typeof from.canMessage === "boolean") {
    view.canMessage = from.canMessage
    if (from.canMessage) delete view.cannotMessageReason
    else if (from.cannotMessageReason) view.cannotMessageReason = from.cannotMessageReason
  }
  if (typeof from.canStop === "boolean") {
    view.canStop = from.canStop
    if (from.canStop) delete view.cannotStopReason
    else if (from.cannotStopReason) view.cannotStopReason = from.cannotStopReason
  }
  if (from.actionsSource) view.actionsSource = from.actionsSource
}

function copy(view: Subagent): Subagent {
  return { ...view, stats: { ...view.stats } }
}
