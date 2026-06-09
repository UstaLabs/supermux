import type { Database } from "bun:sqlite"
import { SessionStore } from "./session-store"
import { ChatStore } from "./chat-store"
import { ProxyStore, type StoredProxy } from "./proxy-store"
import type { Session, SessionRole, AgentKind } from "./types"
import { canOrchestrate, isFallbackEligible } from "./policy"
import { openDb, runMigrations } from "../storage/db"
import { join, dirname } from "path"
import { fileURLToPath } from "url"

const _dirname = dirname(fileURLToPath(import.meta.url))

function createTestDb(): Database {
  const db = openDb(":memory:")
  runMigrations(db, join(_dirname, "../storage/migrations"))
  return db
}

// Re-export Session for callers that import it from registry
export type { Session } from "./types"

export type ProxyEntry = {
  domain: string
  /** Owning session UUID — the stable key. */
  sessionId: string
  /** Derived from sessionId at read time (kept for display + the WS frame). */
  sessionName: string
  port: number
  createdAt: string
  isPublic: boolean
}

const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/
const MAX_PROXIES_PER_SESSION = 5

export class Registry {
  readonly sessions: SessionStore
  readonly chats: ChatStore
  private reservations = new Set<string>()
  private proxies: ProxyStore

  constructor(db?: Database) {
    const resolvedDb = db ?? createTestDb()
    this.sessions = new SessionStore(resolvedDb)
    this.chats = new ChatStore(resolvedDb)
    this.proxies = new ProxyStore(resolvedDb)
    // Runs after SessionStore has loaded active+suspended sessions, so orphan
    // detection sees the real set. Prunes proxies whose session is gone.
    this.reloadProxies()
  }

  register(input: { id?: string; name: string; workdir: string; tmux_target?: string; tmux_window_id?: string; pid: number; base_commit?: string; base_commits?: Record<string, string>; role?: SessionRole; is_default?: boolean } & Partial<Pick<Session, "mute" | "can_orchestrate" | "agent" | "agent_session_id" | "agent_home" | "model" | "reasoningLevel" | "repo_root" | "base_branch" | "session_branch">>): Session {
    if (this.sessions.takenNames().has(input.name)) {
      throw new Error(`session name already in use: ${input.name}`)
    }
    const session = this.sessions.register({
      id: input.id,
      name: input.name,
      agent: input.agent ?? "claude",
      workdir: input.workdir,
      tmux_target: input.tmux_target,
      tmux_window_id: input.tmux_window_id,
      pid: input.pid,
      model: input.model,
      reasoningLevel: input.reasoningLevel,
      can_orchestrate: input.can_orchestrate ?? canOrchestrate(input.role ?? "worker"),
      role: input.role,
      is_default: input.is_default,
      agent_session_id: input.agent_session_id,
      agent_home: input.agent_home,
      base_commit: input.base_commit,
      base_commits: input.base_commits,
      repo_root: input.repo_root,
      base_branch: input.base_branch,
      session_branch: input.session_branch,
    })
    // Mark connected immediately on register (shim has just joined)
    this.sessions.setConnectionStatus(session.id, true)
    this.reservations.delete(input.name)
    return session
  }

  registerPA(input: {
    id?: string
    name: string
    agent: AgentKind
    workdir: string
    model?: string
    reasoningLevel?: string
    pid: number
    is_default?: boolean
    tmux_target?: string
    agent_home?: string
    base_commits?: Record<string, string>
  }): Session {
    return this.register({
      id: input.id,
      name: input.name,
      agent: input.agent,
      workdir: input.workdir,
      model: input.model,
      reasoningLevel: input.reasoningLevel,
      pid: input.pid,
      role: "personal_assistant",
      is_default: input.is_default ?? false,
      tmux_target: input.tmux_target,
      agent_home: input.agent_home,
      base_commits: input.base_commits,
    })
  }

  get(id: string): Session | undefined {
    const s = this.sessions.getById(id)
    if (!s || s.status === "archived") return undefined
    return s
  }
  resolveName(name: string): Session | undefined { return this.sessions.getByName(name) }
  fuzzyResolve(query: string): Session | undefined { return this.sessions.fuzzyByName(query) }
  list(): Session[] { return this.sessions.list() }
  // Archived sessions are NOT in the in-memory cache that list() returns; they
  // live only in the DB and are resumable via resumeFromArchive. Callers that
  // reason about ALL known sessions (e.g. deciding which agent homes are
  // orphaned) MUST union list() with this — otherwise archived homes look
  // orphaned and get wrongly reclaimed.
  listArchived(): Session[] { return this.sessions.listArchived() as unknown as Session[] }

  takenNames(): Set<string> {
    const names = this.sessions.takenNames()
    for (const r of this.reservations) names.add(r)
    return names
  }

  reserveName(name: string): void {
    this.reservations.add(name)
  }

  unregister(id: string): void {
    const s = this.sessions.getById(id)
    if (!s) return
    this.sessions.archive(id)
    this.chats.removeSessionFromChats(id)
  }

  rename(id: string, newName: string): void {
    const s = this.sessions.getById(id)
    if (!s) throw new Error(`no such session: ${id}`)
    if (this.sessions.getByName(newName)) throw new Error(`session name already in use: ${newName}`)
    this.sessions.rename(id, newName)
  }

  setConnectionStatus(id: string, connected: boolean, last_pong_at?: number): void {
    const s = this.sessions.getById(id)
    if (!s) return
    this.sessions.setConnectionStatus(id, connected, last_pong_at)
  }

  setMuted(id: string, muted: boolean): void {
    const s = this.sessions.getById(id)
    if (!s) throw new Error(`no such session: ${id}`)
    this.sessions.setMuted(id, muted)
  }

  setModel(id: string, model: string | undefined): void {
    const s = this.sessions.getById(id)
    if (!s) throw new Error(`no such session: ${id}`)
    this.sessions.setModel(id, model)
  }

  setReasoningLevel(id: string, reasoningLevel: string | undefined): void {
    const s = this.sessions.getById(id)
    if (!s) throw new Error(`no such session: ${id}`)
    this.sessions.setReasoningLevel(id, reasoningLevel)
  }

  listPAs(): Session[] {
    return this.sessions.list().filter(s => s.role === "personal_assistant" && s.status !== "archived")
  }

  defaultPA(): Session | undefined {
    const pas = this.listPAs()
    return pas.find(s => s.is_default) ?? pas.slice().sort((a, b) => a.created_at.localeCompare(b.created_at))[0]
  }

  reassignDefault(excludeId?: string): void {
    const candidates = this.listPAs().filter(s => s.id !== excludeId).sort((a, b) => a.created_at.localeCompare(b.created_at))
    const next = candidates[0]
    if (!next) return
    for (const s of this.listPAs()) this.sessions.setDefault(s.id, s.id === next.id)
  }

  grantOrchestrate(id: string, value: boolean): void {
    const s = this.sessions.getById(id)
    if (!s) throw new Error(`no such session: ${id}`)
    if (s.role === "personal_assistant" && !value) throw new Error("cannot revoke orchestrate from a personal assistant")
    this.sessions.grantOrchestrate(id, value)
  }

  setActive(chat_id: string, sessionId: string): void {
    const s = this.sessions.getById(sessionId)
    if (!s) throw new Error(`no such session: ${sessionId}`)
    this.chats.setActive(chat_id, sessionId)
  }

  getActive(chat_id: string): string | undefined {
    const id = this.chats.getActive(chat_id)
    if (id) {
      const s = this.sessions.getById(id)
      if (s) return id
    }
    const pa = this.defaultPA()
    return pa?.id
  }

  activeFallback(chat_id: string): string | undefined {
    const id = this.chats.activeFallback(chat_id, this.sessions)
    if (id) {
      const s = this.sessions.getById(id)
      if (s) return id
    }
    const pa = this.defaultPA()
    return pa?.id
  }

  // Proxy methods — write-through to SQLite via ProxyStore; owned by session UUID.
  private toProxyEntry(p: StoredProxy): ProxyEntry {
    return {
      domain: p.domain,
      sessionId: p.sessionId,
      sessionName: this.sessions.getById(p.sessionId)?.name ?? p.sessionId,
      port: p.port,
      createdAt: p.createdAt,
      isPublic: p.isPublic,
    }
  }

  addProxy(input: { domain: string; sessionId: string; port: number; isPublic?: boolean }): ProxyEntry {
    const { domain, sessionId, port } = input
    if (!domain || domain.length > 63 || !DOMAIN_RE.test(domain)) {
      throw new Error(`invalid domain: ${domain}`)
    }
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      throw new Error(`invalid port: ${port}`)
    }
    const session = this.sessions.getById(sessionId)
    if (!session) throw new Error(`no such session: ${sessionId}`)
    const existing = this.proxies.get(domain)
    if (existing && existing.sessionId !== session.id) {
      const owner = this.sessions.getById(existing.sessionId)?.name ?? existing.sessionId
      throw new Error(`domain already registered by session: ${owner}`)
    }
    if (!existing && this.proxies.countForSession(session.id) >= MAX_PROXIES_PER_SESSION) {
      throw new Error(`session ${session.name} has reached the proxy limit of ${MAX_PROXIES_PER_SESSION}`)
    }
    const stored: StoredProxy = {
      domain,
      sessionId: session.id,
      port,
      createdAt: existing?.createdAt ?? new Date().toISOString(),
      isPublic: input.isPublic !== undefined ? input.isPublic : (existing?.isPublic ?? false),
    }
    this.proxies.put(stored)
    return this.toProxyEntry(stored)
  }

  setProxyPublic(domain: string, isPublic: boolean): ProxyEntry {
    const existing = this.proxies.get(domain)
    if (!existing) throw new Error(`no proxy registered for "${domain}"`)
    const stored: StoredProxy = { ...existing, isPublic }
    this.proxies.put(stored)
    return this.toProxyEntry(stored)
  }

  removeProxy(domain: string): void { this.proxies.remove(domain) }

  removeProxiesForSession(sessionId: string): string[] {
    return this.proxies.removeForSession(sessionId)
  }

  getProxy(domain: string): ProxyEntry | undefined {
    const p = this.proxies.get(domain)
    return p ? this.toProxyEntry(p) : undefined
  }

  listProxies(): ProxyEntry[] { return this.proxies.list().map(p => this.toProxyEntry(p)) }

  /** Drop persisted proxies whose owning session is gone (archived/deleted). */
  private reloadProxies(): void {
    for (const p of this.proxies.list()) {
      // getById falls back to a DB read that still returns archived rows, so an
      // existence check isn't enough — the session must be active or suspended.
      const s = this.sessions.getById(p.sessionId)
      if (!s || s.status === "archived") this.proxies.remove(p.domain)
    }
  }
}
