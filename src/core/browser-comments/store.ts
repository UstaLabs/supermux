// src/core/browser-comments/store.ts
// #122 phase 4: a Browser tab's comments (pick and comment), stored one per row in the session they were sent to.
// An agent answers each through its mux tools (browser_comments, resolve/reply/dismiss_browser_comment); the app's pins
// follow the state over the `browser_comment` frame.
import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"

/** sent → seen (the agent's turn began after it) → done / answered / dismissed, each with a one-line note. */
export type BrowserCommentStatus = "sent" | "seen" | "done" | "answered" | "dismissed"

export interface NewBrowserComment {
  number: number
  comment: string
  selector: string
  text?: string
  picture?: string | null
}

export interface BrowserCommentBatch {
  url?: string | null
  viewedAs?: string | null
  comments: NewBrowserComment[]
}

export interface BrowserComment {
  id: string
  sessionId: string
  batchId: string
  number: number
  url: string | null
  viewedAs: string | null
  selector: string
  text: string
  comment: string
  picture: string | null
  status: BrowserCommentStatus
  note: string | null
  createdAt: string
  updatedAt: string
}

interface Row {
  id: string; session_id: string; batch_id: string; number: number; url: string | null; viewed_as: string | null
  selector: string; text: string; comment: string; picture: string | null; status: string; note: string | null
  created_at: string; updated_at: string
}

const OPEN: BrowserCommentStatus[] = ["sent", "seen"]

function toComment(r: Row): BrowserComment {
  return {
    id: r.id, sessionId: r.session_id, batchId: r.batch_id, number: r.number, url: r.url, viewedAs: r.viewed_as,
    selector: r.selector, text: r.text, comment: r.comment, picture: r.picture,
    status: r.status as BrowserCommentStatus, note: r.note, createdAt: r.created_at, updatedAt: r.updated_at,
  }
}

/** ①…⑳, then (21), as the message writes a pin. */
function mark(n: number): string {
  return n >= 1 && n <= 20 ? String.fromCodePoint(0x2460 + n - 1) : `(${n})`
}

export class BrowserCommentStore {
  constructor(private readonly db: Db) {}

  addBatch(sessionId: string, batch: BrowserCommentBatch, now: string): BrowserComment[] {
    const batchId = randomUUID()
    const ids: string[] = []
    for (const c of batch.comments) {
      const id = randomUUID()
      ids.push(id)
      this.db.run(
        `INSERT INTO browser_comments (id, session_id, batch_id, number, url, viewed_as, selector, text, comment, picture, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'sent', ?, ?)`,
        [id, sessionId, batchId, c.number, batch.url ?? null, batch.viewedAs ?? null, c.selector, c.text ?? "", c.comment,
         c.picture ?? null, now, now],
      )
    }
    return ids.map((id) => this.get(id)!)
  }

  get(id: string): BrowserComment | undefined {
    const r = this.db.query("SELECT * FROM browser_comments WHERE id = ?").get(id) as Row | null
    return r ? toComment(r) : undefined
  }

  list(sessionId: string): BrowserComment[] {
    return (this.db.query("SELECT * FROM browser_comments WHERE session_id = ? ORDER BY created_at ASC, number ASC").all(sessionId) as Row[]).map(toComment)
  }

  listOpen(sessionId: string): BrowserComment[] {
    return this.list(sessionId).filter((c) => OPEN.includes(c.status))
  }

  setStatus(id: string, status: BrowserCommentStatus, note: string | null, now: string): BrowserComment | undefined {
    this.db.run("UPDATE browser_comments SET status = ?, note = ?, updated_at = ? WHERE id = ?", [status, note, now, id])
    return this.get(id)
  }

  /**
   * The session's agent began a turn at [turnStartedAt] (epoch ms): the comments sent before it are seen now (it has
   * them in its context, in the chat's message). Those it changed, for the clients.
   */
  markSeen(sessionId: string, turnStartedAt: number, now: string): BrowserComment[] {
    const sent = this.list(sessionId).filter((c) => c.status === "sent" && Date.parse(c.createdAt) <= turnStartedAt)
    return sent.map((c) => this.setStatus(c.id, "seen", null, now)!).filter(Boolean)
  }

  /** What an agent reads: each comment with its id (to answer it), the element and the page it was on. */
  markdown(comments: BrowserComment[]): string {
    if (!comments.length) return "No open browser comments."
    const lines: string[] = []
    let page: string | null | undefined
    for (const c of comments) {
      if (c.url !== page) {
        page = c.url
        lines.push(`**Browser comments** · ${c.url ?? "(no page)"}${c.viewedAs ? ` · viewed as ${c.viewedAs}` : ""}`)
      }
      lines.push("", `### ${mark(c.number)} "${c.comment.trim().replace(/\n/g, " ")}" · id ${c.id}`)
      lines.push(`- element: \`${c.selector}\`` + (c.text && !c.selector.toLowerCase().includes(c.text.toLowerCase()) ? ` · text "${c.text}"` : ""))
      if (c.picture) lines.push(`- picture: ${c.picture}`)
      if (c.status !== "sent") lines.push(`- status: ${c.status}${c.note ? ` · ${c.note}` : ""}`)
    }
    return lines.join("\n")
  }
}
