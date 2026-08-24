import type { WorkspaceStore } from "./store"
import type { WorkspaceRecord, ViewRecord, ChatViewState, TerminalViewState, DisplayViewState } from "./types"
import { repointPrimarySession } from "./name"
import { workspaceScope } from "./scope"
import type { Database as Db } from "bun:sqlite"

/**
 * The three side effects a view close can have. Injected rather than imported so
 * the service is testable without a tmux server, a display provider, or a live
 * session supervisor.
 *
 * There is deliberately NO finish dependency. Spec §9.3: a close never opens
 * the Finish flow.
 */
export type WorkspaceDeps = {
  archiveSession: (sessionId: string) => Promise<void>
  /** Inverse of archiveSession: archived → live. */
  resumeSession: (sessionId: string) => Promise<void>
  /** scope is "w:<workspaceId>" for a workspace terminal, or the session name/id for an agent one. */
  closeTerminal: (scope: string, terminalId: string) => Promise<void>
  stopDisplay: (displayId: string) => Promise<void>
}

export type CreateForSessionInput = {
  sessionId: string
  name: string
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  sort_order?: number
}

export class WorkspaceService {
  constructor(
    private readonly store: WorkspaceStore,
    private readonly deps: WorkspaceDeps,
    private readonly db?: Db,
  ) {}

  /** Spec §9.1 steps 3–5. Called from the session spawn path. */
  createForSession(input: CreateForSessionInput): WorkspaceRecord {
    const ws = this.store.create({
      name: input.name,
      workdir: input.workdir,
      repo_root: input.repo_root,
      base_branch: input.base_branch,
      branch: input.branch,
      primary_session_id: input.sessionId,
      sort_order: input.sort_order,
    })
    this.store.addView(ws.id, { kind: "chat", state: { sessionId: input.sessionId } })
    this.linkSession(input.sessionId, ws.id)
    return this.store.getById(ws.id)!
  }

  /**
   * Spec §9.2 "Chat". A second agent joins an existing workspace. The primary
   * session pointer does NOT move — the workspace keeps the name it already has.
   */
  addChatSession(workspaceId: string, sessionId: string): ViewRecord {
    const view = this.store.addView(workspaceId, { kind: "chat", state: { sessionId } })
    this.linkSession(sessionId, workspaceId)
    return view
  }

  /**
   * Spec §9.3. Remove the view AND end the work behind it.
   *
   * Order matters: read the view first (removeView deletes the row), then run
   * the side effect, then remove. A side effect that throws leaves the view in
   * place, so the client can retry rather than losing the only handle to a
   * running thing.
   */
  async closeView(viewId: string): Promise<void> {
    const view = this.store.getView(viewId)
    if (!view) return
    const workspaceId = view.workspace_id

    switch (view.kind) {
      case "chat": {
        await this.deps.archiveSession((view.state as ChatViewState).sessionId)
        break
      }
      case "terminal": {
        const st = view.state as TerminalViewState
        const scope = st.scope === "workspace" ? workspaceScope(workspaceId) : st.sessionId
        await this.deps.closeTerminal(scope, st.terminalId)
        break
      }
      case "display": {
        await this.deps.stopDisplay((view.state as DisplayViewState).displayId)
        break
      }
      case "editor":
        break
    }

    this.store.removeView(viewId)

    // Spec §9.5 rule 6: the pointer follows, the name does not.
    const ws = this.store.getById(workspaceId)
    if (view.kind === "chat" && ws?.primary_session_id === (view.state as ChatViewState).sessionId) {
      repointPrimarySession(this.store, workspaceId)
    }
  }

  /** Spec §9.6. Archive the workspace and every session it chats with. */
  async archiveWorkspace(workspaceId: string): Promise<void> {
    for (const sessionId of this.store.chatSessionIds(workspaceId)) {
      await this.deps.archiveSession(sessionId)
    }
    this.store.archive(workspaceId)
  }

  /**
   * Inverse of archiveWorkspace: unarchive the row and resume every archived
   * chat. A resume failure on one session does not roll back the others or
   * the workspace row.
   */
  async restoreWorkspace(workspaceId: string): Promise<void> {
    const ws = this.store.getById(workspaceId)
    if (!ws) throw new Error("workspace not found")
    if (ws.status === "archived") this.store.unarchive(workspaceId)

    for (const sessionId of this.store.chatSessionIds(workspaceId)) {
      const row = this.db
        ?.query("SELECT status FROM sessions WHERE id = ?")
        .get(sessionId) as { status?: string } | null
      if (row?.status !== "archived") continue
      try {
        await this.deps.resumeSession(sessionId)
      } catch {
        // Keep restoring the rest; the workspace is already live.
      }
    }
  }

  private linkSession(sessionId: string, workspaceId: string): void {
    this.db?.run("UPDATE sessions SET workspace_id = ? WHERE id = ?", [workspaceId, sessionId])
  }
}
