import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"
import {
  type WorkspaceRecord, type WorkspaceRow, type ViewRecord, type ViewRow,
  type ViewKind, type ViewState, type ChatViewState,
  rowToWorkspace, rowToView,
} from "./types"
import {
  type LayoutNode,
  validateLayout, normalizeLayout, addViewToGroup, removeViewFromLayout, collectViewIds,
} from "./layout-tree"

export type CreateWorkspaceInput = {
  id?: string
  name: string
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  primary_session_id?: string
  sort_order?: number
}

export type AddViewInput = {
  id?: string
  kind: ViewKind
  state: ViewState
  title?: string
  /** Which group to append to. Defaults to the first group in the layout. */
  groupId?: string
}

/**
 * All SQL for the workspaces and views tables.
 *
 * There is no in-memory cache here, unlike SessionStore. A workspace is read on
 * a route call, not on every agent frame, so the cache would buy nothing and
 * would be one more thing to keep coherent.
 */
export class WorkspaceStore {
  constructor(private readonly db: Db) {}

  create(input: CreateWorkspaceInput): WorkspaceRecord {
    const id = input.id ?? randomUUID()
    const created_at = new Date().toISOString()
    // An empty group, not an empty object: a workspace with no views yet is
    // still a valid tree shape, and addView can append straight into it.
    const layout: LayoutNode = { type: "group", id: randomUUID(), viewIds: [] }
    this.db.run(
      `INSERT INTO workspaces (id, name, status, workdir, repo_root, base_branch, branch,
                               layout, active_view_id, primary_session_id, name_locked, sort_order, created_at)
       VALUES (?, ?, 'active', ?, ?, ?, ?, ?, NULL, ?, 0, ?, ?)`,
      [id, input.name, input.workdir, input.repo_root ?? null, input.base_branch ?? null,
       input.branch ?? null, JSON.stringify(layout), input.primary_session_id ?? null,
       input.sort_order ?? 0, created_at],
    )
    return this.getById(id)!
  }

  getById(id: string): WorkspaceRecord | undefined {
    const row = this.db.query("SELECT * FROM workspaces WHERE id = ?").get(id) as WorkspaceRow | null
    return row ? rowToWorkspace(row) : undefined
  }

  findByPrimarySession(sessionId: string): WorkspaceRecord | undefined {
    const row = this.db
      .query("SELECT * FROM workspaces WHERE primary_session_id = ?")
      .get(sessionId) as WorkspaceRow | null
    return row ? rowToWorkspace(row) : undefined
  }

  list(opts?: { includeArchived?: boolean }): WorkspaceRecord[] {
    const sql = opts?.includeArchived
      ? "SELECT * FROM workspaces ORDER BY sort_order ASC, id ASC"
      : "SELECT * FROM workspaces WHERE status = 'active' ORDER BY sort_order ASC, id ASC"
    return (this.db.query(sql).all() as WorkspaceRow[]).map(rowToWorkspace)
  }

  rename(id: string, name: string, opts?: { byUser?: boolean }): void {
    if (opts?.byUser) {
      this.db.run("UPDATE workspaces SET name = ?, name_locked = 1 WHERE id = ?", [name, id])
    } else {
      this.db.run("UPDATE workspaces SET name = ? WHERE id = ?", [name, id])
    }
  }

  setPrimarySession(id: string, sessionId: string | null): void {
    this.db.run("UPDATE workspaces SET primary_session_id = ? WHERE id = ?", [sessionId, id])
  }

  archive(id: string): void {
    this.db.run(
      "UPDATE workspaces SET status = 'archived', archived_at = ? WHERE id = ?",
      [new Date().toISOString(), id],
    )
  }

  reorder(orderedIds: string[]): void {
    const tx = this.db.transaction((ids: string[]) => {
      ids.forEach((id, i) => this.db.run("UPDATE workspaces SET sort_order = ? WHERE id = ?", [i, id]))
    })
    tx(orderedIds)
  }

  /**
   * Replace the layout tree. Throws when the tree is invalid or names a view
   * that does not belong to this workspace — the HTTP layer turns that into a 400.
   * The stored layout is never written from an unvalidated input.
   */
  setLayout(id: string, layout: LayoutNode): void {
    const err = validateLayout(layout)
    if (err) throw new Error(err)
    const owned = new Set(this.listViews(id).map((v) => v.id))
    for (const viewId of collectViewIds(layout)) {
      if (!owned.has(viewId)) {
        throw new Error(`layout names a view that is not in this workspace: ${viewId}`)
      }
    }
    this.db.run("UPDATE workspaces SET layout = ? WHERE id = ?", [JSON.stringify(layout), id])
  }

  setActiveView(id: string, viewId: string | null): void {
    this.db.run("UPDATE workspaces SET active_view_id = ? WHERE id = ?", [viewId, id])
  }

  // ── views ────────────────────────────────────────────────────────────────

  listViews(workspaceId: string): ViewRecord[] {
    return (this.db
      .query("SELECT * FROM views WHERE workspace_id = ? ORDER BY created_at ASC, id ASC")
      .all(workspaceId) as ViewRow[]).map(rowToView)
  }

  getView(viewId: string): ViewRecord | undefined {
    const row = this.db.query("SELECT * FROM views WHERE id = ?").get(viewId) as ViewRow | null
    return row ? rowToView(row) : undefined
  }

  /** The session id of every chat view, in view order. Used by archive and by the sidebar. */
  chatSessionIds(workspaceId: string): string[] {
    return this.listViews(workspaceId)
      .filter((v) => v.kind === "chat")
      .map((v) => (v.state as ChatViewState).sessionId)
  }

  addView(workspaceId: string, input: AddViewInput): ViewRecord {
    const id = input.id ?? randomUUID()
    const created_at = new Date().toISOString()
    this.db.run(
      "INSERT INTO views (id, workspace_id, kind, title, state, created_at) VALUES (?, ?, ?, ?, ?, ?)",
      [id, workspaceId, input.kind, input.title ?? null, JSON.stringify(input.state), created_at],
    )

    const ws = this.getById(workspaceId)
    if (ws) {
      const groupId = input.groupId ?? firstGroupId(ws.layout)
      const next = groupId ? addViewToGroup(ws.layout, groupId, id) : ws.layout
      this.db.run("UPDATE workspaces SET layout = ?, active_view_id = ? WHERE id = ?",
        [JSON.stringify(next), id, workspaceId])
    }
    return this.getView(id)!
  }

  setViewState(viewId: string, state: ViewState): void {
    this.db.run("UPDATE views SET state = ? WHERE id = ?", [JSON.stringify(state), viewId])
  }

  setViewTitle(viewId: string, title: string | null): void {
    this.db.run("UPDATE views SET title = ? WHERE id = ?", [title, viewId])
  }

  /**
   * Delete the view row and take it out of its workspace's layout.
   *
   * This is storage only. It does NOT archive a session, kill a terminal, or
   * stop a display — spec §9.3 puts those side effects in the route layer,
   * where the session/terminal/display managers live.
   */
  removeView(viewId: string): void {
    const view = this.getView(viewId)
    if (!view) return
    this.db.run("DELETE FROM views WHERE id = ?", [viewId])
    this.pruneLayout(view.workspace_id, viewId)
  }

  moveView(viewId: string, toWorkspaceId: string, toGroupId?: string): void {
    const view = this.getView(viewId)
    if (!view) return
    const from = view.workspace_id
    if (from === toWorkspaceId) return

    this.db.run("UPDATE views SET workspace_id = ? WHERE id = ?", [toWorkspaceId, viewId])
    this.pruneLayout(from, viewId)

    const target = this.getById(toWorkspaceId)
    if (target) {
      const groupId = toGroupId ?? firstGroupId(target.layout)
      const next = groupId ? addViewToGroup(target.layout, groupId, viewId) : target.layout
      this.db.run("UPDATE workspaces SET layout = ?, active_view_id = ? WHERE id = ?",
        [JSON.stringify(next), viewId, toWorkspaceId])
    }
  }

  /**
   * Take one view id out of a workspace's layout and repair the tree.
   * An emptied tree becomes a single empty group rather than null — a workspace
   * always has a layout column, and the next addView needs a group to target.
   */
  private pruneLayout(workspaceId: string, viewId: string): void {
    const ws = this.getById(workspaceId)
    if (!ws) return
    const pruned = removeViewFromLayout(ws.layout, viewId)
    const next: LayoutNode = pruned ?? { type: "group", id: randomUUID(), viewIds: [] }
    const remaining = collectViewIds(next)
    const active = ws.active_view_id && remaining.includes(ws.active_view_id)
      ? ws.active_view_id
      : (remaining[0] ?? null)
    this.db.run("UPDATE workspaces SET layout = ?, active_view_id = ? WHERE id = ?",
      [JSON.stringify(next), active, workspaceId])
  }
}

/** The id of the first group found in document order, or undefined for an empty tree. */
function firstGroupId(node: LayoutNode): string | undefined {
  if (node.type === "group") return node.id
  for (const child of node.children) {
    const id = firstGroupId(child)
    if (id) return id
  }
  return undefined
}

// Re-exported so callers do not have to import from two modules to repair a tree.
export { normalizeLayout, validateLayout }
