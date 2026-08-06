import type { WorkspaceRecord, ViewRecord } from "./types"

/**
 * The wire shape of a view. Deliberately NOT the record: created_at is server
 * bookkeeping that no client renders, and an explicit `title: null` would force
 * every client to treat null and absent the same way.
 */
export type ViewDto = {
  id: string
  workspace_id: string
  kind: string
  title?: string
  state: unknown
}

export type WorkspaceDto = {
  id: string
  name: string
  status: string
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  layout: unknown
  active_view_id?: string
  primary_session_id?: string
  name_locked: boolean
  sort_order: number
  created_at: string
  archived_at?: string
  /** Inlined so a client never has to make a second call to render a workspace. */
  views: ViewDto[]
}

export function viewDto(v: ViewRecord): ViewDto {
  const dto: ViewDto = { id: v.id, workspace_id: v.workspace_id, kind: v.kind, state: v.state }
  if (v.title !== undefined) dto.title = v.title
  return dto
}

export function workspaceDto(w: WorkspaceRecord, views: ViewRecord[]): WorkspaceDto {
  const dto: WorkspaceDto = {
    id: w.id,
    name: w.name,
    status: w.status,
    workdir: w.workdir,
    layout: w.layout,
    name_locked: w.name_locked,
    sort_order: w.sort_order,
    created_at: w.created_at,
    views: views.map(viewDto),
  }
  if (w.repo_root !== undefined) dto.repo_root = w.repo_root
  if (w.base_branch !== undefined) dto.base_branch = w.base_branch
  if (w.branch !== undefined) dto.branch = w.branch
  if (w.active_view_id !== undefined) dto.active_view_id = w.active_view_id
  if (w.primary_session_id !== undefined) dto.primary_session_id = w.primary_session_id
  if (w.archived_at !== undefined) dto.archived_at = w.archived_at
  return dto
}
