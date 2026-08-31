// src/core/walkthrough/store.ts
import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"

export type AnchorStatus = "ok" | "not_in_diff" | "outdated"

export interface NewWalkthroughStep {
  title: string
  bodyMd: string
  repo?: string | null
  path?: string | null
  side?: "LEFT" | "RIGHT"
  anchorLine?: number | null
  rangeStart?: number | null
  rangeEnd?: number | null
  anchorContext?: string | null
  anchorStatus?: AnchorStatus
}

export interface WalkthroughStep extends NewWalkthroughStep {
  id: string
  walkthroughId: string
  ord: number
  side: "LEFT" | "RIGHT"
  anchorStatus: AnchorStatus
}

export interface Walkthrough {
  id: string
  sessionId: string
  title: string
  baseSpec: string
  revision: number
  createdAt: string
  isCurrent: boolean
  steps: WalkthroughStep[]
}

interface WtRow {
  id: string; session_id: string; title: string; base_spec: string
  revision: number; created_at: string; is_current: number
}
interface StepRow {
  id: string; walkthrough_id: string; ord: number; title: string; body_md: string
  repo: string | null; path: string | null; side: string
  anchor_line: number | null; range_start: number | null; range_end: number | null
  anchor_context: string | null; anchor_status: string
}

function toStep(r: StepRow): WalkthroughStep {
  return {
    id: r.id,
    walkthroughId: r.walkthrough_id,
    ord: r.ord,
    title: r.title,
    bodyMd: r.body_md,
    repo: r.repo ?? undefined,
    path: r.path ?? undefined,
    side: r.side === "LEFT" ? "LEFT" : "RIGHT",
    anchorLine: r.anchor_line ?? undefined,
    rangeStart: r.range_start ?? undefined,
    rangeEnd: r.range_end ?? undefined,
    anchorContext: r.anchor_context ?? undefined,
    anchorStatus: (r.anchor_status as AnchorStatus) || "ok",
  }
}

function toWalkthrough(r: WtRow, steps: WalkthroughStep[]): Walkthrough {
  return {
    id: r.id,
    sessionId: r.session_id,
    title: r.title,
    baseSpec: r.base_spec,
    revision: r.revision,
    createdAt: r.created_at,
    isCurrent: r.is_current === 1,
    steps,
  }
}

export class WalkthroughStore {
  constructor(private readonly db: Db) {}

  getCurrent(sessionId: string): Walkthrough | undefined {
    const r = this.db.query("SELECT * FROM walkthroughs WHERE session_id = ? AND is_current = 1").get(sessionId) as WtRow | null
    if (!r) return undefined
    return toWalkthrough(r, this.stepsOf(r.id))
  }

  get(id: string): Walkthrough | undefined {
    const r = this.db.query("SELECT * FROM walkthroughs WHERE id = ?").get(id) as WtRow | null
    if (!r) return undefined
    return toWalkthrough(r, this.stepsOf(r.id))
  }

  /** Replace the session's current walkthrough. Marks the previous one not-current and bumps revision. */
  replaceCurrent(sessionId: string, title: string, baseSpec: string, steps: NewWalkthroughStep[]): Walkthrough {
    const prev = this.db.query(
      "SELECT MAX(revision) as rev FROM walkthroughs WHERE session_id = ?",
    ).get(sessionId) as { rev: number | null } | null
    const revision = (prev?.rev ?? 0) + 1
    const id = randomUUID()
    const createdAt = new Date().toISOString()
    const tx = this.db.transaction(() => {
      this.db.run("UPDATE walkthroughs SET is_current = 0 WHERE session_id = ? AND is_current = 1", [sessionId])
      this.db.run(
        `INSERT INTO walkthroughs (id, session_id, title, base_spec, revision, created_at, is_current)
         VALUES (?, ?, ?, ?, ?, ?, 1)`,
        [id, sessionId, title, baseSpec, revision, createdAt],
      )
      for (let i = 0; i < steps.length; i++) {
        const s = steps[i]!
        this.db.run(
          `INSERT INTO walkthrough_steps (id, walkthrough_id, ord, title, body_md, repo, path, side, anchor_line, range_start, range_end, anchor_context, anchor_status)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [
            randomUUID(), id, i, s.title, s.bodyMd,
            s.repo ?? null, s.path ?? null, s.side ?? "RIGHT",
            s.anchorLine ?? null, s.rangeStart ?? null, s.rangeEnd ?? null,
            s.anchorContext ?? null, s.anchorStatus ?? "ok",
          ],
        )
      }
    })
    tx()
    return this.get(id)!
  }

  updateStepAnchors(stepId: string, patch: { anchorLine?: number | null; anchorContext?: string | null; anchorStatus?: AnchorStatus }): void {
    const sets: string[] = [], vals: unknown[] = []
    if (patch.anchorLine !== undefined) { sets.push("anchor_line = ?"); vals.push(patch.anchorLine) }
    if (patch.anchorContext !== undefined) { sets.push("anchor_context = ?"); vals.push(patch.anchorContext) }
    if (patch.anchorStatus !== undefined) { sets.push("anchor_status = ?"); vals.push(patch.anchorStatus) }
    if (!sets.length) return
    vals.push(stepId)
    this.db.run(`UPDATE walkthrough_steps SET ${sets.join(", ")} WHERE id = ?`, vals as never[])
  }

  private stepsOf(walkthroughId: string): WalkthroughStep[] {
    return (this.db.query("SELECT * FROM walkthrough_steps WHERE walkthrough_id = ? ORDER BY ord ASC").all(walkthroughId) as StepRow[]).map(toStep)
  }
}
