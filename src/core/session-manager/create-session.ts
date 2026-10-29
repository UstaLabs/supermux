// The one create path (spec 2026-10-28 §4). The launcher (POST /sessions) and spawn_session start a
// session through it, and so will delegate and council. It spawns the agent, puts the session in a
// workspace (its own, or the one it joins) and tells the clients: the workspace frame first, then
// the full session_added.
import { AgentKind } from "../../shared/agents"
import type { Session } from "./types"
import type { WorkspaceDto } from "../workspace/dto"

export type CreateSessionArgs = {
  workdir: string
  name?: string
  agent?: AgentKind
  model?: string
  reasoningLevel?: string
  permissionMode?: string
  /** Account id; absent = the agent's system account. */
  account?: string
  /** false = run in `workdir` itself; otherwise a worktree when it is a single git repo. */
  worktree?: boolean
  baseBranch?: string
  /** Continue-in-new-conversation: the source session. Its name and worktree are reused, and the new chat joins its workspace. */
  inheritFrom?: string
  /** Join this workspace as another chat. Without it (and without inheritFrom) the session gets a workspace of its own. */
  workspaceId?: string
  /** The pending "+ → Chat" tab of that workspace to bind, instead of adding a tab. */
  viewId?: string
}

export type CreatedSession = {
  id: string
  name: string
  workdir: string
  agent: AgentKind
  model?: string
  reasoningLevel?: string
  permissionMode?: string
  repo_root?: string
  session_branch?: string
}

export type CreateSessionDeps = {
  /** The bare spawn (main.ts spawnSession): agent process, worktree, session row. */
  spawn(args: {
    workdir: string
    requestedName?: string
    agent?: AgentKind
    model?: string
    reasoningLevel?: string
    permissionMode?: string
    account?: string
    worktree?: boolean
    baseBranch?: string
    inheritFromSessionId?: string
  }): Promise<{ name: string; session_id: string }>
  /** A live (not archived) session. */
  getSession(id: string): Session | undefined
  /** sessions.workspace_id of a session, if it has one. */
  workspaceOf(sessionId: string): string | undefined
  workspaceExists(workspaceId: string): boolean
  /** WorkspaceService.addChatSession. */
  joinWorkspace(workspaceId: string, sessionId: string, viewId?: string): void
  /** WorkspaceService.createForSession; projectsChanged when it registered a new project. */
  createWorkspace(session: Session): { id: string; projectsChanged: boolean }
  workspaceDto(workspaceId: string): WorkspaceDto | undefined
  broadcast(frame: object): void
  broadcastProjects(): void
  refreshTelegramMenu(): Promise<void>
  /** The session object of a session_added frame. */
  sessionFrame(session: Session): object
  /** The session's resolved reasoning level. */
  sessionEffort(session: Session): string | undefined
}

export async function createSession(deps: CreateSessionDeps, args: CreateSessionArgs): Promise<CreatedSession> {
  const r = await deps.spawn({
    workdir: args.workdir,
    requestedName: args.name,
    agent: args.agent ?? AgentKind.Claude,
    model: args.model,
    reasoningLevel: args.reasoningLevel,
    permissionMode: args.permissionMode,
    account: args.account,
    worktree: args.worktree,
    baseBranch: args.baseBranch,
    inheritFromSessionId: args.inheritFrom,
  })
  const entry = deps.getSession(r.session_id)
  if (entry) {
    // Continue-in-new-conversation sends inheritFrom but no workspaceId; it joins the source
    // session's workspace (same checkout, same workspace) instead of minting a second one.
    let joinId = args.workspaceId?.trim() || (args.inheritFrom ? deps.workspaceOf(args.inheritFrom) : undefined)
    if (joinId && !deps.workspaceExists(joinId)) joinId = undefined
    if (joinId) {
      deps.joinWorkspace(joinId, entry.id, args.viewId?.trim() || undefined)
      const dto = deps.workspaceDto(joinId)
      if (dto) deps.broadcast({ type: "workspace_changed", workspace: dto })
    } else {
      const created = deps.createWorkspace(entry)
      // Catalog first, so the client knows the project the workspace points at.
      if (created.projectsChanged) deps.broadcastProjects()
      const dto = deps.workspaceDto(created.id)
      if (dto) deps.broadcast({ type: "workspace_added", workspace: dto })
    }
  }
  await deps.refreshTelegramMenu()
  // The spawn's own session_added is a thin one; clients upsert by id, so this full one replaces it.
  if (entry) deps.broadcast({ type: "session_added", session: deps.sessionFrame(entry) })
  return {
    id: entry?.id ?? r.session_id,
    name: entry?.name ?? r.name,
    workdir: entry?.workdir ?? args.workdir,
    agent: entry?.agent ?? AgentKind.Claude,
    model: entry?.model,
    reasoningLevel: entry ? deps.sessionEffort(entry) : undefined,
    permissionMode: entry?.permissionMode ?? undefined,
    repo_root: entry?.repo_root || undefined,
    session_branch: entry?.session_branch || undefined,
  }
}
