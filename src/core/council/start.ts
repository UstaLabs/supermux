// A council's start (spec 2026-10-28 §5, changed 2026-10-31): the council is saved and returned at once; its
// members start in the background, each on its own.
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
  /** Council.opened: the saved council on the caller's card. */
  opened(councilId: string): void
  /** Council.memberStarted: the question to a member that started. */
  memberStarted(memberId: string): void
  /** Council.memberStartFailed: the member's status, and the caller is told. */
  memberStartFailed(memberId: string, error: string): void
}

export type CreatedCouncil = {
  started: StartedCouncil
  /** Starts every member, in parallel; never rejects (a member that can't start is reported). */
  run(): Promise<void>
}

export type AddMembersArgs = {
  callerId: string
  council: string
  members: StartCouncilArgs["members"]
  checkout: "shared" | "worktree"
}

/**
 * council (spec §5): members start like delegated children (hidden in the caller's workspace, the
 * caller their parent), each with a fixed letter recorded before it starts. Only what is quick is
 * checked before the council is saved (the caller, the agents installed, the folder a git repository for
 * "worktree"), so the call returns at once (decided 2026-10-31: a slow agent took the tool past its
 * timeout). run() then makes each member's worktree and starts it, all members at the same time; a
 * member that started gets the question, a member that can't start is stopped, its worktree removed,
 * and the caller is told. The others carry on.
 */
export async function createCouncil(deps: StartCouncilDeps, args: StartCouncilArgs): Promise<CreatedCouncil> {
  const { caller, repo } = await check(deps, args.callerId, args.members, args.checkout)
  const council = deps.store.create({ callerSessionId: caller.id, question: args.question, callId: args.callId })
  const created = launch(deps, { councilId: council.id, councilName: council.name, caller, repo, members: args.members, firstIndex: 0 })
  deps.opened(council.id)
  return { started: { council: council.id, name: council.name, members: created.members }, run: created.run }
}

/**
 * council_add (design 2026-10-31 §2): more members join an open council's current round, with the next letters,
 * started the same way. A member added after round 1 gets that round's focus and the reports before it.
 */
export async function addCouncilMembers(deps: StartCouncilDeps, args: AddMembersArgs): Promise<{ added: StartedCouncil["members"]; run(): Promise<void> }> {
  const c = deps.store.get(args.council)
  if (!c) throw new Error(`unknown council: ${args.council}`)
  if (c.callerSessionId !== args.callerId) throw new Error("only the council's caller can add members")
  if (c.status === "ended") throw new Error(`${c.name} has ended`)
  const { caller, repo } = await check(deps, args.callerId, args.members, args.checkout)
  const created = launch(deps, { councilId: c.id, councilName: c.name, caller, repo, members: args.members, firstIndex: deps.store.members(c.id).length })
  deps.opened(c.id)
  return { added: created.members, run: created.run }
}

/** What is quick to check before anything is saved: the caller, the agents, and the folder for "worktree". */
async function check(deps: StartCouncilDeps, callerId: string, members: StartCouncilArgs["members"], checkout: "shared" | "worktree") {
  const caller = deps.isLive(callerId) ? deps.session(callerId) : undefined
  if (!caller) throw new Error("unknown session")
  for (const m of members) deps.assertInstalled(m.agent)
  let repo: { root: string; branch: string } | undefined
  if (checkout === "worktree") {
    const info = await deps.repoInfo(caller.workdir)
    if (!info.eligible || !info.repoRoot) throw new Error(`${caller.workdir} is not a single git repository, so no worktree can be made`)
    repo = { root: info.repoRoot, branch: info.currentBranch || "HEAD" }
  }
  return { caller, repo }
}

/** Records the members (letters from `firstIndex`) and returns how to start them, all at once. */
function launch(deps: StartCouncilDeps, o: {
  councilId: string; councilName: string; caller: Session; repo?: { root: string; branch: string }
  members: StartCouncilArgs["members"]; firstIndex: number
}) {
  const workspaceId = deps.workspaceOf(o.caller.id)
  const members = o.members.map((m, i) => ({ ...m, letter: letterAt(o.firstIndex + i), id: deps.newId() }))
  // Recorded before any start: a member's preamble and its tool list read its membership.
  for (const m of members) deps.store.addMember(o.councilId, m.id, m.letter)

  const startMember = async (m: (typeof members)[number]): Promise<void> => {
    let worktree: WorktreeHandle | undefined
    try {
      if (o.repo) {
        try {
          worktree = await deps.createWorktree({ repoRoot: o.repo.root, baseBranch: o.repo.branch, sessionName: `${o.councilName} ${m.letter}` })
        } catch (err) {
          throw new Error(`could not create a worktree: ${err instanceof Error ? err.message : String(err)}`)
        }
      }
      await deps.spawn({
        id: m.id,
        workdir: o.caller.workdir,
        requestedName: `${o.councilName} ${m.letter}`,
        agent: m.agent,
        model: m.model,
        reasoningLevel: m.effort,
        permissionMode: m.access,
        worktree: false,
        worktreeHandle: worktree,
        parentSessionId: o.caller.id,
        workspaceId,
      })
    } catch (err) {
      // A spawn can throw after its session row was made: that session is stopped too.
      if (deps.isLive(m.id)) await deps.kill(m.id).catch(() => {})
      if (worktree) await deps.removeWorktree(worktree.repoRoot, worktree.worktreeDir, worktree.sessionBranch, { force: true }).catch(() => {})
      deps.memberStartFailed(m.id, err instanceof Error ? err.message : String(err))
      return
    }
    deps.memberStarted(m.id)
  }

  return {
    members: members.map((m) => ({ letter: m.letter, session: m.id, agent: m.agent, model: m.model ?? null })),
    run: async () => { await Promise.all(members.map(startMember)) },
  }
}
