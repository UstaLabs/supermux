// Card #75: a session exists the moment it is asked for. createAsleep does only the instant part:
// a unique name, the branch (made now, so two quick creates can't pick one name), the folder its
// worktree will be checked out in, its agent's home, and the row, registered and then suspended.
// Nothing starts: the session's first wake (SessionChain) checks the worktree out and starts its
// agent through resume, like any session that dozed off.
import { join } from "path"
import { AgentKind } from "../../shared/agents"
import { homeDirName } from "../../shared/paths"
import { resolvePermissionMode } from "../agents/permission-modes"
import type { RepoInfo } from "../git/repo-info"
import { deriveSessionBranch, repoSlug, type WorktreeHandle } from "../worktree/manager"
import { deriveName, ensureUnique } from "./naming"
import type { Registry } from "./registry"
import type { Session } from "./types"
import { makeLogger } from "../../shared/log"

const log = makeLogger("born-asleep")

export type CreateAsleepArgs = {
  workdir: string
  requestedName?: string
  agent: AgentKind
  model?: string
  reasoningLevel?: string
  permissionMode?: string
  /** Validated account id; undefined = the system login. */
  account?: string
  /** false = run in `workdir` itself; otherwise a worktree when it is a single git repo. */
  worktree?: boolean
  baseBranch?: string
  /** delegate: fail instead of running in the folder when no worktree can be made. */
  requireWorktree?: boolean
  internal?: boolean
  parentSessionId?: string
  workspaceId?: string
  /** council: the member's id, minted first. */
  id?: string
  /** council: a worktree made beforehand; used as it is. */
  worktreeHandle?: WorktreeHandle
}

export type CreateAsleepDeps = {
  registry: Registry
  repoInfo(workdir: string): Promise<RepoInfo>
  existingBranches(repoRoot: string): Promise<Set<string>>
  createBranch(repoRoot: string, branch: string, base: string): Promise<void>
  worktreesRoot(): string
  stateDir: string
  captureBaseCommits(workdir: string): Record<string, string>
  newId(): string
}

/** Branch names being made right now, per repo: taken before git has them. */
const branchesInFlight = new Map<string, Set<string>>()

export async function createAsleep(deps: CreateAsleepDeps, args: CreateAsleepArgs): Promise<Session> {
  const { registry } = deps
  const base = args.requestedName?.trim() || deriveName(args.workdir)
  const name = ensureUnique(base, registry.takenNames())
  registry.reserveName(name)
  let branchInFlight: { repoRoot: string; branch: string } | undefined
  try {
    let workdir = args.workdir
    let worktree: { repo_root: string; base_branch: string; session_branch: string } | undefined
    if (args.worktreeHandle) {
      workdir = args.worktreeHandle.worktreeDir
      worktree = { repo_root: args.worktreeHandle.repoRoot, base_branch: args.worktreeHandle.baseBranch, session_branch: args.worktreeHandle.sessionBranch }
    } else if (args.worktree !== false) {
      const info = await deps.repoInfo(args.workdir)
      if (info.eligible && info.repoRoot) {
        const repoRoot = info.repoRoot
        const inFlight = branchesInFlight.get(repoRoot) ?? new Set<string>()
        branchesInFlight.set(repoRoot, inFlight)
        const taken = new Set([...(await deps.existingBranches(repoRoot)), ...inFlight])
        for (const s of [...registry.list(), ...registry.listArchived()]) if (s.repo_root === repoRoot && s.session_branch) taken.add(s.session_branch)
        const branch = deriveSessionBranch(args.requestedName?.trim() || deriveName(args.workdir), taken)
        inFlight.add(branch)
        branchInFlight = { repoRoot, branch }
        const baseBranch = args.baseBranch || info.currentBranch || "HEAD"
        let made = true
        try {
          await deps.createBranch(repoRoot, branch, baseBranch)
        } catch (err) {
          // No branch can be made (a repo with no commits yet, a base that doesn't resolve): the
          // session runs in the folder itself, as a start that couldn't make its worktree always did.
          // A worktree that must be made (delegate) fails instead.
          if (args.requireWorktree) throw new Error(`could not create a worktree: ${err instanceof Error ? err.message : String(err)}`)
          log.warn("session_branch_failed_running_in_folder", { workdir: args.workdir, branch, err: err instanceof Error ? err.message : String(err) })
          made = false
        }
        if (made) {
          workdir = join(deps.worktreesRoot(), repoSlug(repoRoot), deps.newId())
          worktree = { repo_root: repoRoot, base_branch: baseBranch, session_branch: branch }
        }
      } else if (args.requireWorktree) {
        throw new Error(`${args.workdir} is not a single git repository, so no worktree can be made`)
      }
    }
    const id = args.id ?? deps.newId()
    const row = registry.register({
      id,
      name,
      workdir,
      pid: 0,
      agent: args.agent,
      model: args.model,
      reasoningLevel: args.reasoningLevel,
      permissionMode: resolvePermissionMode(args.agent, args.permissionMode),
      ...(args.account ? { account: args.account } : {}),
      agent_home: join(deps.stateDir, "agents", args.agent, homeDirName(name)),
      core: true,
      internal: args.internal,
      parent_session_id: args.parentSessionId,
      workspace_id: args.workspaceId,
      connected: false,
      // A folder that exists now (no worktree to check out, or one made beforehand): its base commits now.
      // A worktree checked out at the first wake gets them then.
      ...(worktree && !args.worktreeHandle ? {} : { base_commits: deps.captureBaseCommits(workdir) }),
      ...(worktree ?? {}),
    })
    registry.sessions.suspend(row.id)
    return registry.sessions.getById(row.id) ?? row
  } catch (err) {
    registry.releaseName(name)
    throw err
  } finally {
    if (branchInFlight) branchesInFlight.get(branchInFlight.repoRoot)?.delete(branchInFlight.branch)
  }
}
