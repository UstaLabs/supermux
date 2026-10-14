import { readdirSync, readFileSync, existsSync } from "fs"
import { join } from "path"
import type { Db } from "../storage/db"
import { splitSections } from "./sections"
import { homedir } from "os"

function encodeProjectDir(cwd: string): string {
  return cwd.replace(/\/$/, "").replace(/[/.]/g, "-")
}

function claudeTranscriptPath(cwd: string, claudeSessionId: string): string {
  return join(homedir(), ".claude", "projects", encodeProjectDir(cwd), `${claudeSessionId}.jsonl`)
}

export interface KnowledgeHit {
  scope: string
  name: string
  heading: string
  path: string
  snippet: string
}

export interface SessionHit {
  id: string
  name: string
  workdir: string
  agent: string
  status: string
  created_at: string
  snippet: string
  transcript_path: string | null
}

/** One message matching a client's chat search (`GET /search/messages`). */
export interface MessageHit {
  session_id: string
  session_name: string
  workdir: string
  agent: string
  message_id: string
  ts: string
  direction: string
  /** The match with some context either side; `…` marks a cut. */
  snippet: string
}

export interface SessionFilter {
  project?: string
  since?: string
  agent?: string
  limit?: number
}

export class SearchStore {
  constructor(
    private readonly db: Db,
    private readonly home: string,
  ) {}

  /** Wipe + repopulate the knowledge index from domains/, personal/, conventions.md. */
  rebuildKnowledge(): void {
    const ins = this.db.prepare(
      "INSERT INTO memory_fts (scope, name, heading, body, path, is_personal) VALUES (?, ?, ?, ?, ?, ?)",
    )
    const tx = this.db.transaction(() => {
      // DELETE + re-inserts as one atomic swap: a throw mid-rebuild won't leave the index empty.
      this.db.exec("DELETE FROM memory_fts")
      this.indexDir(ins, join(this.home, "domains"), (f) => (f.endsWith(".digest.md") ? "digest" : "domain"), 0, (f) => f !== "_inbox.md" && f.endsWith(".md"))
      this.indexDir(ins, join(this.home, "personal"), () => "personal", 1, (f) => f.endsWith(".md"))
      const conv = join(this.home, "conventions.md")
      if (existsSync(conv)) this.indexFile(ins, conv, "conventions", 0)
      const soul = join(this.home, "soul.md")
      if (existsSync(soul)) this.indexFile(ins, soul, "personal", 1)
    })
    tx()
  }

  private indexDir(ins: any, dir: string, scopeOf: (f: string) => string, isPersonal: number, accept: (f: string) => boolean): void {
    if (!existsSync(dir)) return
    for (const file of readdirSync(dir).filter(accept).sort()) {
      this.indexFile(ins, join(dir, file), scopeOf(file), isPersonal)
    }
  }

  private indexFile(ins: any, path: string, scope: string, isPersonal: number): void {
    const name = path.split("/").pop()!.replace(/\.digest\.md$/, "").replace(/\.md$/, "")
    for (const sec of splitSections(readFileSync(path, "utf8"))) {
      if (!sec.body && !sec.heading) continue
      ins.run(scope, name, sec.heading, sec.body, path, isPersonal)
    }
  }

  searchKnowledge(query: string, opts: { includePersonal: boolean; limit?: number }): KnowledgeHit[] {
    const limit = opts.limit ?? 10
    const personalClause = opts.includePersonal ? "" : "AND is_personal = 0"
    const rows = this.db
      .prepare(
        `SELECT scope, name, heading, path, snippet(memory_fts, 3, '[', ']', '…', 16) AS snippet
           FROM memory_fts
          WHERE memory_fts MATCH ? ${personalClause}
          ORDER BY (scope = 'digest') DESC, rank
          LIMIT ?`,
      )
      .all(toMatch(query), limit) as any[]
    return rows.map((r) => ({ scope: r.scope, name: r.name, heading: r.heading, path: r.path, snippet: r.snippet }))
  }

  /** Wipe + repopulate the session index from the messages table. */
  rebuildSessions(): void {
    const ins = this.db.prepare("INSERT INTO session_fts (session_id, ts, text) VALUES (?, ?, ?)")
    const rows = this.db.query("SELECT session_id, ts, text FROM messages WHERE session_id IS NOT NULL AND text IS NOT NULL AND text != ''").all() as any[]
    const tx = this.db.transaction(() => {
      // DELETE + re-inserts as one atomic swap: a throw mid-rebuild won't leave the index empty.
      this.db.exec("DELETE FROM session_fts")
      for (const r of rows) ins.run(r.session_id, r.ts, r.text)
    })
    tx()
  }

  /** Incrementally index one message (called on MessageStore 'append'). */
  indexMessage(sessionId: string, ts: string, text: string): void {
    if (!text) return
    this.db.run("INSERT INTO session_fts (session_id, ts, text) VALUES (?, ?, ?)", [sessionId, ts, text])
  }

  searchSessions(query: string, filter: SessionFilter = {}): SessionHit[] {
    const limit = filter.limit ?? 10
    const rows = this.db
      .prepare(
        `SELECT s.id, s.name, s.workdir, s.agent, s.status, s.created_at, s.agent_session_id,
                snippet(session_fts, 2, '[', ']', '…', 12) AS snippet
           FROM session_fts
           JOIN sessions s ON s.id = session_fts.session_id
          WHERE session_fts MATCH ?
            AND s.internal = 0
            AND (? IS NULL OR s.workdir = ?)
            AND (? IS NULL OR s.agent = ?)
            AND (? IS NULL OR session_fts.ts >= ?)
          ORDER BY rank
          LIMIT ?`,
      )
      .all(
        toMatch(query),
        filter.project ?? null, filter.project ?? null,
        filter.agent ?? null, filter.agent ?? null,
        filter.since ?? null, filter.since ?? null,
        Math.max(limit * 8, 50),
      ) as any[]
    // We over-fetch candidate messages and dedupe per session in JS (keeping the best-ranked
    // snippet), so a session that dominates the candidate window could still cap recall —
    // acceptable for v1.
    const seen = new Set<string>()
    const out: SessionHit[] = []
    for (const r of rows) {
      if (seen.has(r.id)) continue
      seen.add(r.id)
      out.push({
        id: r.id, name: r.name, workdir: r.workdir, agent: r.agent, status: r.status, created_at: r.created_at,
        snippet: r.snippet,
        transcript_path: r.agent === "claude" && r.agent_session_id ? claudeTranscriptPath(r.workdir, r.agent_session_id) : null,
      })
      if (out.length >= limit) break
    }
    return out
  }

  /**
   * The chat find bar's "All chats": every live, non-internal session's messages containing
   * [query] as a substring, newest first. Find-bar semantics, not the agents' keyword bag: the
   * text the user typed, in that order. A plain scan of `messages` (~35k rows scan in ~50 ms),
   * which also gives each hit its message id to jump to — `session_fts` has none.
   * LIKE folds ASCII case only; the snippet locates the match case-insensitively in JS.
   */
  searchMessages(query: string, opts: { limit?: number; sessionId?: string } = {}): MessageHit[] {
    const q = query.trim()
    if (!q) return []
    const limit = Math.min(Math.max(opts.limit ?? 50, 1), 200)
    const pattern = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`
    const rows = this.db
      .prepare(
        `SELECT m.id, m.session_id, m.ts, m.direction, m.text, s.name, s.workdir, s.agent
           FROM messages m
           JOIN sessions s ON s.id = m.session_id
          WHERE m.text LIKE ? ESCAPE '\\'
            AND (m.op IS NULL OR m.op = 'reply')
            AND s.status != 'archived'
            AND s.internal = 0
            AND (? IS NULL OR m.session_id = ?)
          ORDER BY m.ts DESC
          LIMIT ?`,
      )
      .all(pattern, opts.sessionId ?? null, opts.sessionId ?? null, limit) as any[]
    return rows.map((r) => ({
      session_id: r.session_id,
      session_name: r.name,
      workdir: r.workdir,
      agent: r.agent,
      message_id: r.id,
      ts: r.ts,
      direction: r.direction,
      snippet: messageSnippet(r.text, q),
    }))
  }
}

/** ~[radius] characters either side of the first case-insensitive [query] match, on one line. */
export function messageSnippet(text: string, query: string, radius = 60): string {
  const flat = text.replace(/\s+/g, " ").trim()
  const at = flat.toLowerCase().indexOf(query.replace(/\s+/g, " ").toLowerCase())
  if (at < 0) return flat.length > radius * 2 ? `${flat.slice(0, radius * 2)}…` : flat
  const start = Math.max(0, at - radius)
  const end = Math.min(flat.length, at + query.length + radius)
  return `${start > 0 ? "…" : ""}${flat.slice(start, end)}${end < flat.length ? "…" : ""}`
}

/** Build an FTS5 match expression that neutralizes operators and uses prefix
 * queries so partial stems (e.g. "deploy" → "Deploys") are found. Each word
 * becomes a quoted prefix term `"word"*`; multi-word input is OR-ed so a natural
 * keyword-bag matches any section/message containing ANY term, and the BM25 `rank`
 * already in the ORDER BY floats the best-overlap row to the top.
 *
 * NB: the old " " join meant implicit-AND — every word had to co-occur in one
 * section — so real 5+ word queries almost always returned `[]` (transcript audit
 * 2026-07-01 found ~87% of memory_search calls came back empty for this reason). */
function toMatch(query: string): string {
  const cleaned = query.replace(/["*+\-()^~]/g, " ").trim()
  if (!cleaned) return '""'
  return cleaned
    .split(/\s+/)
    .map((w) => `"${w}"*`)
    .join(" OR ")
}
