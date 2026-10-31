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
  /** Account id; absent (or "auto") = Auto, the account with the most room left (decision #18). */
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

/**
 * The announce half of the create path: a just-created session joins a workspace (the one named, or
 * the inherited session's) or gets its own, and every client hears about it, the workspace frame
 * first, then the full session_added. Also used on its own for a session created elsewhere (an
 * Agent made by create_agent).
 */
export function announceCreated(
  deps: Omit<CreateSessionDeps, "spawn" | "getSession" | "refreshTelegramMenu" | "sessionEffort">,
  entry: Session,
  opts: { workspaceId?: string; viewId?: string; inheritFrom?: string } = {},
): void {
  // Continue-in-new-conversation sends inheritFrom but no workspaceId; it joins the source
  // session's workspace (same checkout, same workspace) instead of minting a second one.
  let joinId = opts.workspaceId?.trim() || (opts.inheritFrom ? deps.workspaceOf(opts.inheritFrom) : undefined)
  if (joinId && !deps.workspaceExists(joinId)) joinId = undefined
  if (joinId) {
    deps.joinWorkspace(joinId, entry.id, opts.viewId?.trim() || undefined)
    const dto = deps.workspaceDto(joinId)
    if (dto) deps.broadcast({ type: "workspace_changed", workspace: dto })
  } else {
    const ws = deps.createWorkspace(entry)
    // Catalog first, so the client knows the project the workspace points at.
    if (ws.projectsChanged) deps.broadcastProjects()
    const dto = deps.workspaceDto(ws.id)
    if (dto) deps.broadcast({ type: "workspace_added", workspace: dto })
  }
  // The spawn's own session_added is a thin one; clients upsert by id, so this full one replaces it.
  deps.broadcast({ type: "session_added", session: deps.sessionFrame(entry) })
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
  if (entry) announceCreated(deps, entry, { workspaceId: args.workspaceId, viewId: args.viewId, inheritFrom: args.inheritFrom })
  await deps.refreshTelegramMenu()
  return {
    id: entry?.id ?? r.session_id,
    name: entry?.name ?? r.name,
    workdir: entry?.workdir ?? args.workdir,
    agent: entry?.agent ?? args.agent ?? AgentKind.Claude,
    model: entry?.model,
    reasoningLevel: entry ? deps.sessionEffort(entry) : undefined,
    permissionMode: entry?.permissionMode ?? undefined,
    repo_root: entry?.repo_root || undefined,
    session_branch: entry?.session_branch || undefined,
  }
}
