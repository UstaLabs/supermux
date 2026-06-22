import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"
import { type Session, type SessionRecord, type SessionRow, type SessionStatus, type AgentKind, type SessionRole, rowToRecord } from "./types"
import type { FinishJob } from "../worktree/finish-job"

export type RegisterInput = {
  id?: string
  name: string
  agent: AgentKind
  workdir: string
  tmux_target?: string
  tmux_window_id?: string
  pid: number
  model?: string
  reasoningLevel?: string
  can_orchestrate?: boolean
  role?: SessionRole
  is_default?: boolean
  internal?: boolean
  agent_session_id?: string
  agent_home?: string
  base_commit?: string
  base_commits?: Record<string, string>
  repo_root?: string
  base_branch?: string
  session_branch?: string
}

export class SessionStore {
  private cache = new Map<string, Session>()
  constructor(private readonly db: Db) {
    this.loadFromDb()
  }

  private loadFromDb() {
    const rows = this.db.query(
      "SELECT * FROM sessions WHERE status IN ('active', 'suspended')"
    ).all() as SessionRow[]
    for (const row of rows) {
      const rec = rowToRecord(row)
      const session: Session = { ...rec, pid: 0, connected: false }
      this.cache.set(session.id, session)
    }
  }

  register(input: RegisterInput): Session {
    const id = input.id ?? randomUUID()
    const now = new Date().toISOString()
    const role: SessionRole = input.role ?? "worker"
    const is_default: boolean = input.is_default ?? false
    const session: Session = {
      id,
      name: input.name,
      status: "active",
      agent: input.agent,
      workdir: input.workdir,
      model: input.model,
      reasoningLevel: input.reasoningLevel,
      mute: false,
      can_orchestrate: input.can_orchestrate ?? false,
      role,
      is_default,
      internal: input.internal ?? false,
      tmux_target: input.tmux_target ?? "",
      tmux_window_id: input.tmux_window_id,
      agent_session_id: input.agent_session_id,
      agent_home: input.agent_home,
      created_at: now,
      base_commit: input.base_commit,
      base_commits: input.base_commits,
      repo_root: input.repo_root,
      base_branch: input.base_branch,
      session_branch: input.session_branch,
      pid: input.pid,
      connected: false,
    }
    this.db.run(
      `INSERT INTO sessions (id, name, status, agent, workdir, model, reasoning_level, mute, can_orchestrate, role, is_default, internal, tmux_target, tmux_window_id, agent_session_id, agent_home, created_at, base_commit, base_commits, repo_root, base_branch, session_branch)
       VALUES (?, ?, 'active', ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [id, input.name, input.agent, input.workdir, input.model ?? null, input.reasoningLevel ?? null,
       input.can_orchestrate ? 1 : 0, role, is_default ? 1 : 0, input.internal ? 1 : 0, input.tmux_target ?? null,
       input.tmux_window_id ?? null, input.agent_session_id ?? null, input.agent_home ?? null, now,
       input.base_commit ?? null,
       input.base_commits ? JSON.stringify(input.base_commits) : null,
       input.repo_root ?? null, input.base_branch ?? null, input.session_branch ?? null]
    )
    this.cache.set(id, session)
    return session
  }

  getById(id: string): Session | undefined {
    const cached = this.cache.get(id)
    if (cached) return cached
    const row = this.db.query("SELECT * FROM sessions WHERE id = ?").get(id) as SessionRow | null
    if (!row) return undefined
    const rec = rowToRecord(row)
    return { ...rec, pid: 0, connected: false }
  }

  getByName(name: string): Session | undefined {
    const row = this.db.query(
      "SELECT * FROM sessions WHERE name = ? AND status IN ('active', 'suspended')"
    ).get(name) as SessionRow | null
    if (!row) return undefined
    const rec = rowToRecord(row)
    return { ...rec, pid: 0, connected: false }
  }

  archive(id: string): void {
    const session = this.cache.get(id)
    if (!session) return
    const now = new Date().toISOString()
    this.db.run("UPDATE sessions SET status = 'archived', killed_at = ? WHERE id = ?", [now, id])
    session.status = "archived"
    session.killed_at = now
    session.pid = 0
    session.connected = false
    this.cache.delete(id)
  }

  suspend(id: string): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET status = 'suspended' WHERE id = ?", [id])
    session.status = "suspended"
    session.pid = 0
    session.connected = false
  }

  activate(id: string, pid: number): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET status = 'active' WHERE id = ?", [id])
    session.status = "active"
    session.pid = pid
  }

  resume(id: string, name: string, pid: number): void {
    this.db.run(
      "UPDATE sessions SET status = 'active', killed_at = NULL, name = ? WHERE id = ?",
      [name, id]
    )
    const row = this.db.query("SELECT * FROM sessions WHERE id = ?").get(id) as SessionRow | null
    if (!row) return
    const rec = rowToRecord(row)
    const session: Session = { ...rec, pid, connected: false }
    this.cache.set(id, session)
  }

  rename(id: string, newName: string): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET name = ? WHERE id = ?", [newName, id])
    session.name = newName
  }

  setConnectionStatus(id: string, connected: boolean, last_pong_at?: number): void {
    const session = this.cache.get(id)
    if (!session) return
    session.connected = connected
    if (last_pong_at !== undefined) session.last_pong_at = last_pong_at
  }

  setMuted(id: string, muted: boolean): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET mute = ? WHERE id = ?", [muted ? 1 : 0, id])
    session.mute = muted
  }

  setModel(id: string, model: string | undefined): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET model = ? WHERE id = ?", [model ?? null, id])
    session.model = model
  }

  setReasoningLevel(id: string, reasoningLevel: string | undefined): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET reasoning_level = ? WHERE id = ?", [reasoningLevel ?? null, id])
    session.reasoningLevel = reasoningLevel
  }

  setAgentSessionId(id: string, agentSessionId: string): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET agent_session_id = ? WHERE id = ?", [agentSessionId, id])
    session.agent_session_id = agentSessionId
  }

  setTmuxWindowId(id: string, windowId: string | undefined): void {
    this.db.run("UPDATE sessions SET tmux_window_id = ? WHERE id = ?", [windowId ?? null, id])
    const session = this.cache.get(id)
    if (!session) return
    session.tmux_window_id = windowId
  }

  setWorktree(id: string, wt: { repo_root: string; base_branch: string; session_branch: string }): void {
    this.db.run("UPDATE sessions SET repo_root = ?, base_branch = ?, session_branch = ? WHERE id = ?",
      [wt.repo_root, wt.base_branch, wt.session_branch, id])
    const session = this.getById(id)
    if (session) { session.repo_root = wt.repo_root; session.base_branch = wt.base_branch; session.session_branch = wt.session_branch }
  }

  setFinishJob(id: string, job: FinishJob | null): void {
    this.db.run("UPDATE sessions SET finish_job = ? WHERE id = ?", [job ? JSON.stringify(job) : null, id])
    const session = this.cache.get(id)
    if (session) session.finish_job = job ?? undefined
  }

  // --- Read status & drafts (server-side, global per session; migration 017) ---
  // These touch only the new columns and deliberately bypass the in-memory
  // Session cache and the session_state broadcast path: read advances ride the
  // `session_read` frame and draft writes ride `draft_set`/`draft_clear`.

  getLastReadAt(id: string): string | null {
    const row = this.db.query("SELECT last_read_at FROM sessions WHERE id = ?").get(id) as { last_read_at: string | null } | null
    return row?.last_read_at ?? null
  }

  setLastReadAt(id: string, ts: string): void {
    this.db.run("UPDATE sessions SET last_read_at = ? WHERE id = ?", [ts, id])
  }

  getDraft(id: string): string | null {
    const row = this.db.query("SELECT draft FROM sessions WHERE id = ?").get(id) as { draft: string | null } | null
    return row?.draft ?? null
  }

  setDraft(id: string, text: string | null): void {
    this.db.run("UPDATE sessions SET draft = ? WHERE id = ?", [text ?? null, id])
  }

  /** Map of sessionId → last_read_at for all non-archived sessions that have one. */
  allReads(): Record<string, string> {
    const rows = this.db.query(
      "SELECT id, last_read_at FROM sessions WHERE last_read_at IS NOT NULL AND status IN ('active','suspended')"
    ).all() as Array<{ id: string; last_read_at: string }>
    const out: Record<string, string> = {}
    for (const r of rows) out[r.id] = r.last_read_at
    return out
  }

  /** Map of sessionId → draft text for all non-archived sessions that have one. */
  allDrafts(): Record<string, string> {
    const rows = this.db.query(
      "SELECT id, draft FROM sessions WHERE draft IS NOT NULL AND status IN ('active','suspended')"
    ).all() as Array<{ id: string; draft: string }>
    const out: Record<string, string> = {}
    for (const r of rows) out[r.id] = r.draft
    return out
  }

  grantOrchestrate(id: string, value: boolean): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET can_orchestrate = ? WHERE id = ?", [value ? 1 : 0, id])
    session.can_orchestrate = value
  }

  setRole(id: string, role: SessionRole): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET role = ? WHERE id = ?", [role, id])
    session.role = role
  }

  setDefault(id: string, value: boolean): void {
    const session = this.cache.get(id)
    if (!session) return
    this.db.run("UPDATE sessions SET is_default = ? WHERE id = ?", [value ? 1 : 0, id])
    session.is_default = value
  }

  listActive(): Session[] {
    return [...this.cache.values()].filter(s => s.status === "active")
  }

  listSuspended(): Session[] {
    return [...this.cache.values()].filter(s => s.status === "suspended")
  }

  listArchived(): SessionRecord[] {
    const rows = this.db.query(
      "SELECT * FROM sessions WHERE status = 'archived' ORDER BY killed_at DESC"
    ).all() as SessionRow[]
    return rows.map(rowToRecord)
  }

  list(): Session[] {
    return [...this.cache.values()]
  }

  takenNames(): Set<string> {
    return new Set([...this.cache.values()].map(s => s.name))
  }

  /** Fuzzy lookup by display name. Returns undefined if 0 or 2+ matches. */
  fuzzyByName(query: string): Session | undefined {
    const rows = this.db.query(
      "SELECT * FROM sessions WHERE status IN ('active', 'suspended') AND (name = ? OR name LIKE ?) LIMIT 2"
    ).all(query, `%${query}%`) as SessionRow[]
    if (rows.length !== 1) return undefined
    const rec = rowToRecord(rows[0]!)
    return { ...rec, pid: 0, connected: false }
  }

  listArchivedWorktrees(): Array<{ id: string; workdir: string; repo_root: string; base_branch: string; session_branch: string }> {
    const rows = this.db.query(
      "SELECT id, workdir, repo_root, base_branch, session_branch FROM sessions WHERE status = 'archived' AND repo_root IS NOT NULL"
    ).all() as Array<{ id: string; workdir: string; repo_root: string; base_branch: string | null; session_branch: string | null }>
    return rows
      .filter((r) => !!r.session_branch)
      .map((r) => ({ id: r.id, workdir: r.workdir, repo_root: r.repo_root, base_branch: r.base_branch || "HEAD", session_branch: r.session_branch! }))
  }
}
