// src/core/review/store.ts
import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"

export interface NewComment {
  sessionId: string; repo: string; path: string; side: "LEFT" | "RIGHT"
  baseSha?: string; headBlobSha?: string
  anchorLine: number; rangeStart?: number; rangeEnd?: number
  anchorContext: string; diffHunkHeader?: string; parentId?: string
  body: string; author: "user" | "agent"; createdAt: string
}

export interface Comment extends NewComment {
  id: string; status: "open" | "submitted" | "resolved"; resolvedBy?: string; resolvedSha?: string
}

interface Row {
  id: string; session_id: string; repo: string; path: string; side: string
  base_sha: string | null; head_blob_sha: string | null
  anchor_line: number; range_start: number | null; range_end: number | null
  anchor_context: string; diff_hunk_header: string | null; parent_id: string | null
  body: string; author: string; created_at: string; status: string
  resolved_by: string | null; resolved_sha: string | null
}

function toComment(r: Row): Comment {
  return {
    id: r.id, sessionId: r.session_id, repo: r.repo, path: r.path, side: r.side as "LEFT" | "RIGHT",
    baseSha: r.base_sha ?? undefined, headBlobSha: r.head_blob_sha ?? undefined,
    anchorLine: r.anchor_line, rangeStart: r.range_start ?? undefined, rangeEnd: r.range_end ?? undefined,
    anchorContext: r.anchor_context, diffHunkHeader: r.diff_hunk_header ?? undefined, parentId: r.parent_id ?? undefined,
    body: r.body, author: r.author as "user" | "agent", createdAt: r.created_at,
    status: r.status as Comment["status"], resolvedBy: r.resolved_by ?? undefined, resolvedSha: r.resolved_sha ?? undefined,
  }
}

export class ReviewStore {
  constructor(private readonly db: Db) {}

  add(c: NewComment): Comment {
    const id = randomUUID()
    this.db.run(
      `INSERT INTO review_comments (id, session_id, repo, path, side, base_sha, head_blob_sha, anchor_line, range_start, range_end, anchor_context, diff_hunk_header, parent_id, body, author, created_at, status)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open')`,
      [id, c.sessionId, c.repo, c.path, c.side, c.baseSha ?? null, c.headBlobSha ?? null, c.anchorLine,
       c.rangeStart ?? null, c.rangeEnd ?? null, c.anchorContext, c.diffHunkHeader ?? null, c.parentId ?? null,
       c.body, c.author, c.createdAt],
    )
    return this.get(id)!
  }
  list(sessionId: string): Comment[] {
    return (this.db.query("SELECT * FROM review_comments WHERE session_id = ? ORDER BY created_at ASC").all(sessionId) as Row[]).map(toComment)
  }
  listOpen(sessionId: string): Comment[] {
    return (this.db.query("SELECT * FROM review_comments WHERE session_id = ? AND status = 'open' ORDER BY created_at ASC").all(sessionId) as Row[]).map(toComment)
  }
  get(id: string): Comment | undefined {
    const r = this.db.query("SELECT * FROM review_comments WHERE id = ?").get(id) as Row | null
    return r ? toComment(r) : undefined
  }
  update(id: string, patch: { status?: Comment["status"]; body?: string; resolvedBy?: string; resolvedSha?: string }): void {
    const sets: string[] = [], vals: unknown[] = []
    if (patch.status !== undefined) { sets.push("status = ?"); vals.push(patch.status) }
    if (patch.body !== undefined) { sets.push("body = ?"); vals.push(patch.body) }
    if (patch.resolvedBy !== undefined) { sets.push("resolved_by = ?"); vals.push(patch.resolvedBy) }
    if (patch.resolvedSha !== undefined) { sets.push("resolved_sha = ?"); vals.push(patch.resolvedSha) }
    if (!sets.length) return
    vals.push(id)
    this.db.run(`UPDATE review_comments SET ${sets.join(", ")} WHERE id = ?`, vals as never[])
  }
  delete(id: string): void { this.db.run("DELETE FROM review_comments WHERE id = ?", [id]) }
}
