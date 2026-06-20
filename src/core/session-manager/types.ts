import type { SessionRole } from "./policy"
import { AgentKind } from "../../shared/agents"
import type { FinishJob } from "../worktree/finish-job"

export type SessionStatus = "active" | "suspended" | "archived"
export type { AgentKind }
export type { SessionRole }

export type SessionRecord = {
  id: string
  name: string
  status: SessionStatus
  agent: AgentKind
  workdir: string
  model?: string
  reasoningLevel?: string
  mute: boolean
  can_orchestrate: boolean
  role: SessionRole
  is_default: boolean
  internal: boolean
  tmux_target: string
  tmux_window_id?: string
  agent_session_id?: string
  agent_home?: string
  created_at: string
  killed_at?: string
  base_commit?: string
  base_commits?: Record<string, string>
  repo_root?: string
  base_branch?: string
  session_branch?: string
  finish_job?: FinishJob
}

export type TmuxRef = {
  tmux_target?: string
  tmux_window_id?: string
}

export type SessionRow = {
  id: string
  name: string
  status: string
  agent: string
  workdir: string
  model: string | null
  reasoning_level: string | null
  mute: number
  can_orchestrate: number
  role: string
  is_default: number
  internal: number
  tmux_target: string | null
  tmux_window_id: string | null
  agent_session_id: string | null
  agent_home: string | null
  created_at: string
  killed_at: string | null
  base_commit: string | null
  base_commits: string | null
  repo_root: string | null
  base_branch: string | null
  session_branch: string | null
  finish_job: string | null
}

export type Session = SessionRecord & {
  pid: number
  connected: boolean
  last_pong_at?: number
}

export type ChatState = {
  active_session_id?: string
  history: string[]
}

export function rowToRecord(row: SessionRow): SessionRecord {
  return {
    id: row.id,
    name: row.name,
    status: row.status as SessionStatus,
    agent: row.agent as AgentKind,
    workdir: row.workdir,
    model: row.model ?? undefined,
    reasoningLevel: row.reasoning_level ?? undefined,
    mute: row.mute === 1,
    can_orchestrate: row.can_orchestrate === 1,
    role: (row.role as SessionRole) ?? "worker",
    is_default: row.is_default === 1,
    internal: row.internal === 1,
    tmux_target: row.tmux_target ?? "",
    tmux_window_id: row.tmux_window_id ?? undefined,
    agent_session_id: row.agent_session_id ?? undefined,
    agent_home: row.agent_home ?? undefined,
    created_at: row.created_at,
    killed_at: row.killed_at ?? undefined,
    base_commit: row.base_commit ?? undefined,
    base_commits: row.base_commits ? JSON.parse(row.base_commits) : undefined,
    repo_root: row.repo_root ?? undefined,
    base_branch: row.base_branch ?? undefined,
    session_branch: row.session_branch ?? undefined,
    finish_job: row.finish_job ? JSON.parse(row.finish_job) : undefined,
  }
}

export function isTmuxBackedSession(session: Pick<SessionRecord, "agent">): boolean {
  return session.agent === AgentKind.Claude
}
