// A council's start (spec 2026-10-28 §5): every member started, or none left running and no council.
import type { AgentKind } from "../../shared/agents"
import type { Session } from "../session-manager/types"
import type { RepoInfo } from "../git/repo-info"
import type { WorktreeHandle } from "../worktree/manager"
import type { CouncilStore } from "./store"
import { letterAt } from "./letters"

export type StartCouncilArgs = {
  callerId: string
  question: string
  members: Array<{ agent: AgentKind; model?: string; effort?: string; access: string }>
  checkout: "shared" | "worktree"
  callId?: string
}

export type StartedCouncil = {
  council: string
  name: string
  members: Array<{ letter: string; session: string; agent: AgentKind; model: string | null }>
}

export type StartCouncilDeps = {
  store: CouncilStore
  /** A session row, archived ones included. */
  session(id: string): Session | undefined
  /** True while the session is not archived. */
  isLive(id: string): boolean
  /** Throws when the agent's CLI is not installed. */
  assertInstalled(agent: AgentKind): void
  /** sessions.workspace_id of a session, if it has one. */
  workspaceOf(sessionId: string): string | undefined
  /** getRepoInfo: whether the folder is a single git repository, and its current branch. */
  repoInfo(workdir: string): Promise<RepoInfo>
  /** worktree/manager's createWorktree and removeWorktree. */
  createWorktree(opts: { repoRoot: string; baseBranch: string; sessionName: string }): Promise<WorktreeHandle>
  removeWorktree(repoRoot: string, worktreeDir: string, sessionBranch: string, opts: { force?: boolean }): Promise<void>
  /** A member's session id, minted before it starts. */
  newId(): string
  /** The bare spawn (main.ts spawnSession). */
  spawn(args: {
    id: string
    workdir: string
    requestedName: string
    agent: AgentKind
    model?: string
    reasoningLevel?: string
    permissionMode: string
    worktree: false
    worktreeHandle?: WorktreeHandle
    parentSessionId: string
    workspaceId?: string
  }): Promise<unknown>
  /** Stops and archives a started member (main.ts: killAndArchive, ended by the parent). */
  kill(id: string): Promise<void>
  /** Council.opened: the question to the members. */
  opened(councilId: string): void
}

/**
 * council (spec §5): members start like delegated children (hidden in the caller's workspace, the
 * caller their parent), each with a fixed letter recorded before it starts. With "worktree" every
 * worktree is made first: if one can't be, none starts. When a worktree or a member fails, the call
 * fails with that error and takes the council with it: the members already started are killed and
 * archived (the caller gets no notice, no subagent card and no council_update for them), every
 * worktree is removed, and nothing of the council stays.
 */
export async function startCouncil(deps: StartCouncilDeps, args: StartCouncilArgs): Promise<StartedCouncil> {
  const caller = deps.isLive(args.callerId) ? deps.session(args.callerId) : undefined
  if (!caller) throw new Error("unknown session")
  for (const m of args.members) deps.assertInstalled(m.agent)
  const workspaceId = deps.workspaceOf(caller.id)
  const council = deps.store.create({ callerSessionId: caller.id, question: args.question, callId: args.callId })
  const worktrees: WorktreeHandle[] = []
  try {
    if (args.checkout === "worktree") {
      const info = await deps.repoInfo(caller.workdir)
      if (!info.eligible || !info.repoRoot) throw new Error(`${caller.workdir} is not a single git repository, so no worktree can be made`)
      for (const [i] of args.members.entries()) {
        try {
          worktrees.push(await deps.createWorktree({ repoRoot: info.repoRoot, baseBranch: info.currentBranch || "HEAD", sessionName: `${council.name} ${letterAt(i)}` }))
        } catch (err) {
          throw new Error(`could not create a worktree: ${err instanceof Error ? err.message : String(err)}`)
        }
      }
    }
    for (const [i, m] of args.members.entries()) {
      const letter = letterAt(i)
      const id = deps.newId()
      deps.store.addMember(council.id, id, letter)
      await deps.spawn({
        id,
        workdir: caller.workdir,
        requestedName: `${council.name} ${letter}`,
        agent: m.agent,
        model: m.model,
        reasoningLevel: m.effort,
        permissionMode: m.access,
        worktree: false,
        worktreeHandle: worktrees[i],
        parentSessionId: caller.id,
        workspaceId,
      })
    }
  } catch (err) {
    // Every member id is recorded before its spawn, so this also finds one whose spawn threw after
    // its row was made. The council row goes before the kills and the membership after them: a
    // member's kill then finds no council to update (no council_update frame), and its membership
    // still keeps it off the caller's subagent list (no "cancelled" subagent card).
    const memberIds = deps.store.members(council.id).map((m) => m.sessionId)
    deps.store.removeRecord(council.id)
    for (const id of memberIds) {
      if (deps.isLive(id)) await deps.kill(id).catch(() => {})
    }
    // The council never ran: none of its worktrees stays (its members are stopped by now).
    for (const wt of worktrees) await deps.removeWorktree(wt.repoRoot, wt.worktreeDir, wt.sessionBranch, { force: true }).catch(() => {})
    // Then everything under it (members, round, reports, statuses): nothing of the council stays.
    deps.store.remove(council.id)
    throw err
  }
  deps.opened(council.id)
  return {
    council: council.id,
    name: council.name,
    members: deps.store.members(council.id).map((m) => {
      const s = deps.session(m.sessionId)
      return { letter: m.letter, session: m.sessionId, agent: (s?.agent ?? "claude") as AgentKind, model: s?.model ?? null }
    }),
  }
}
