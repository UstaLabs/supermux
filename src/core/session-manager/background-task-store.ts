// src/core/session-manager/background-task-store.ts
import { EventEmitter } from "events"

export type BgTaskKind = "shell" | "agent" | "workflow" | "task"
export type BgTaskStatus = "running" | "completed" | "failed"

export interface BackgroundTask {
  id: string
  kind: BgTaskKind
  label: string
  startedAt: number      // epoch ms (transcript timestamp)
  status: BgTaskStatus
  endedAt?: number
  summary?: string
  callId?: string        // launching tool_use id
}

export interface BgTaskOpen { id: string; kind: BgTaskKind; label: string; ts: number; callId?: string }
export interface BgTaskClose { id: string; status: "completed" | "failed"; summary?: string; ts: number; callId?: string }

export function kindFromId(id: string): BgTaskKind {
  if (id.startsWith("wf_")) return "workflow"
  if (id.startsWith("a")) return "agent"
  if (id.startsWith("b")) return "shell"
  return "task"
}

const CLOSED_KEEP = 20

// Per-session background-task registry. In-memory and ephemeral by design —
// same lifecycle as ActivityStore (dropped on broker restart / session exit).
export class BackgroundTaskStore extends EventEmitter {
  private readonly bySession = new Map<string, BackgroundTask[]>()

  upsertOpen(sessionId: string, t: BgTaskOpen): void {
    const list = this.bySession.get(sessionId) ?? []
    if (list.some((x) => x.id === t.id)) return   // replayed start marker
    list.push({ id: t.id, kind: t.kind, label: t.label, startedAt: t.ts, status: "running", ...(t.callId ? { callId: t.callId } : {}) })
    this.bySession.set(sessionId, this.evict(list))
    this.emit("change", sessionId)
  }

  close(sessionId: string, c: BgTaskClose): void {
    const list = this.bySession.get(sessionId) ?? []
    // Match by task-id first (Bash/Agent), then by the launching tool_use_id (Monitor,
    // whose stored id IS its callId because its start result carries no task-id).
    const existing = list.find((x) => x.id === c.id) ?? (c.callId ? list.find((x) => x.callId === c.callId) : undefined)
    if (existing) {
      if (existing.status !== "running") return   // replayed notification
      existing.status = c.status
      existing.endedAt = c.ts
      if (c.summary) existing.summary = c.summary
    } else {
      // Notification for a task we never saw start (missed tail) — still show the ✓/✕ moment.
      list.push({ id: c.id, kind: kindFromId(c.id), label: c.id, startedAt: c.ts, status: c.status, endedAt: c.ts, ...(c.summary ? { summary: c.summary } : {}) })
    }
    this.bySession.set(sessionId, this.evict(list))
    this.emit("change", sessionId)
  }

  get(sessionId: string): BackgroundTask[] {
    return this.bySession.get(sessionId)?.slice() ?? []
  }

  openCount(sessionId: string): number {
    return this.bySession.get(sessionId)?.filter((t) => t.status === "running").length ?? 0
  }

  clear(sessionId: string): void {
    if (!this.bySession.delete(sessionId)) return
    this.emit("change", sessionId)
  }

  private evict(list: BackgroundTask[]): BackgroundTask[] {
    const closed = list.filter((t) => t.status !== "running")
    if (closed.length <= CLOSED_KEEP) return list
    const drop = new Set(closed.slice(0, closed.length - CLOSED_KEEP).map((t) => t.id))
    return list.filter((t) => !drop.has(t.id))
  }
}
