import type { LayoutNode } from "./layout-tree"

export type WorkspaceStatus = "active" | "archived"

export type ViewKind = "chat" | "terminal" | "editor" | "display"

/** A chat view names exactly one agent session. */
export type ChatViewState = { sessionId: string }

/**
 * A terminal view is either workspace-scoped (a plain shell in the workspace
 * work directory, no agent needed) or session-scoped (the agent's own pane,
 * terminalId always "agent").
 */
export type TerminalViewState =
  | { scope: "workspace"; terminalId: string }
  | { scope: "session"; sessionId: string; terminalId: "agent" }

/** Paths are relative to the workspace work directory. */
export type EditorViewState = {
  path?: string
  mode: "tree" | "file" | "diff"
  line?: number
  diffBase?: string
}

/** The display stream stays host-global; the view only points at it. */
export type DisplayViewState = { displayId: string }

export type ViewState = ChatViewState | TerminalViewState | EditorViewState | DisplayViewState

export type WorkspaceRecord = {
  id: string
  name: string
  status: WorkspaceStatus
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  layout: LayoutNode
  active_view_id?: string
  /** The session whose name drives this workspace's name (spec §9.5). */
  primary_session_id?: string
  /** True once the user renames the workspace by hand; stops name propagation. */
  name_locked: boolean
  sort_order: number
  created_at: string
  archived_at?: string
}

export type WorkspaceRow = {
  id: string
  name: string
  status: string
  workdir: string
  repo_root: string | null
  base_branch: string | null
  branch: string | null
  layout: string
  active_view_id: string | null
  primary_session_id: string | null
  name_locked: number
  sort_order: number
  created_at: string
  archived_at: string | null
}

export type ViewRecord = {
  id: string
  workspace_id: string
  kind: ViewKind
  title?: string
  state: ViewState
  created_at: string
}

export type ViewRow = {
  id: string
  workspace_id: string
  kind: string
  title: string | null
  state: string
  created_at: string
}

export function rowToWorkspace(row: WorkspaceRow): WorkspaceRecord {
  return {
    id: row.id,
    name: row.name,
    status: row.status as WorkspaceStatus,
    workdir: row.workdir,
    repo_root: row.repo_root ?? undefined,
    base_branch: row.base_branch ?? undefined,
    branch: row.branch ?? undefined,
    layout: JSON.parse(row.layout) as LayoutNode,
    active_view_id: row.active_view_id ?? undefined,
    primary_session_id: row.primary_session_id ?? undefined,
    name_locked: row.name_locked === 1,
    sort_order: row.sort_order,
    created_at: row.created_at,
    archived_at: row.archived_at ?? undefined,
  }
}

export function rowToView(row: ViewRow): ViewRecord {
  return {
    id: row.id,
    workspace_id: row.workspace_id,
    kind: row.kind as ViewKind,
    title: row.title ?? undefined,
    state: JSON.parse(row.state) as ViewState,
    created_at: row.created_at,
  }
}
