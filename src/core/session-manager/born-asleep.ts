// Card #75: a session exists the moment it is asked for. createAsleep does only the instant part:
// a unique name, the branch (made now, so two quick creates can't pick one name), the folder its
// worktree will be checked out in, its agent's home, and the row, registered and then suspended.
// Nothing starts: the session's first wake (SessionChain) checks the worktree out and starts its
// agent through resume, like any session that dozed off.
import { existsSync } from "fs"
import { join } from "path"
import { AgentKind } from "../../shared/agents"
import { homeDirName } from "../../shared/paths"
import { resolvePermissionMode } from "../agents/permission-modes"
import { branchList, realPath, samePath, switchBranch, type OpenIn } from "../git/branches"
import { readPrByNumber, type PrNumberRead } from "../git/forge"
import { refFormatOk } from "../settings/user-prefs"
import type { RepoInfo } from "../git/repo-info"
import { createTrackingBranch, deriveSessionBranch, repoSlug, type WorktreeHandle } from "../worktree/manager"
import { preparePrBranch } from "../worktree/pr-branch"
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
  /** #152: what the launcher picked; absent = the worktree / baseBranch fields above, as before. */
  source?: SessionSource
  /** #152: who asked for it, as JSON ({"kind":"device","name":…}). */
  createdBy?: string
}

/**
 * #152: where a session's work comes from. new: a worktree on a new branch from `base`, named `name` (refused when
 * it exists; `auto`, made free instead) or as before. existing: a worktree on that branch itself (origin's gets a
 * local branch tracking it). pr: a worktree on the pull / merge request's own head branch, with the PR's target as
 * its base; a local branch with commits the PR lacks is refused unless `divergedPolicy` says whose wins. local: the
 * folder itself, switched to `switchTo` first, only when it is clean. reuse: the copy, branch and diff base of another
 * session.
 */
export type SessionSource =
  | { kind: "new"; base?: string; name?: string; auto?: boolean }
  | { kind: "existing"; branch: string }
  | { kind: "pr"; number: number; divergedPolicy?: "pr" | "mine" }
  | { kind: "local"; switchTo?: string }
  | { kind: "reuse"; sessionId: string }

/** #152: a request body's `source`, or why it isn't one. */
export function parseSessionSource(raw: unknown): { source?: SessionSource } | { error: string } {
  if (raw === undefined || raw === null) return {}
  if (typeof raw !== "object") return { error: "source must be an object" }
  const o = raw as Record<string, unknown>
  const str = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : undefined)
  const base = str(o.base), name = str(o.name), branch = str(o.branch), switchTo = str(o.switchTo), sessionId = str(o.sessionId)
  const number = typeof o.number === "number" && Number.isInteger(o.number) && o.number > 0 ? o.number : undefined
  const divergedPolicy = o.divergedPolicy === "pr" || o.divergedPolicy === "mine" ? o.divergedPolicy : undefined
  switch (o.kind) {
    case "new": return { source: { kind: "new", ...(base ? { base } : {}), ...(name ? { name } : {}), ...(o.auto === true ? { auto: true } : {}) } }
    case "existing": return branch ? { source: { kind: "existing", branch } } : { error: "source.branch required" }
    case "pr": return number ? { source: { kind: "pr", number, ...(divergedPolicy ? { divergedPolicy } : {}) } } : { error: "source.number required" }
    case "local": return { source: { kind: "local", ...(switchTo ? { switchTo } : {}) } }
    case "reuse": return sessionId ? { source: { kind: "reuse", sessionId } } : { error: "source.sessionId required" }
    default: return { error: "unknown source kind" }
  }
}

/** #152: a source that can't be honoured, said before anything is made, with what the launcher needs to offer a way
 *  out: who holds a branch (held_elsewhere), how many files changed (dirty), the taken name (branch_exists)… */
export class SourceRefused extends Error {
  readonly status = 409
  constructor(readonly code: string, message: string, readonly details: Record<string, unknown> = {}) {
    super(message)
  }
}

/** Who has a worktree at [path]: a session (its copy), else a worktree no session owns. */
function holderAt(path: string, sessions: Session[]): OpenIn {
  const at = realPath(path), s = sessions.find((x) => x.workdir && samePath(realPath(x.workdir), at))
  return s ? { kind: "session", id: s.id, name: s.name } : { kind: "external", path: at }
}

/** Where git keeps [branch] already: a session made a moment ago (its copy is checked out at its first wake), else the
 *  folder or a worktree. The folder reads as "folder" here, as the launcher shows it. */
function holderOfBranch(repoRoot: string, branch: string, sessions: Session[], list = branchList(repoRoot, owners(sessions))): OpenIn | null {
  const asleep = sessions.find((s) => s.session_branch === branch && s.repo_root && samePath(realPath(s.repo_root), realPath(repoRoot)) && !existsSync(s.workdir))
  if (asleep) return { kind: "session", id: asleep.id, name: asleep.name }
  const openIn = list.local.find((b) => b.name === branch)?.openIn
  return openIn ? (openIn.kind === "this" ? { kind: "folder" } : openIn) : null
}
const owners = (sessions: Session[]) => sessions.flatMap((s) => (s.workdir ? [{ id: s.id, name: s.name, workdir: s.workdir }] : []))

/** existing: the local branch to check out (origin's gets a tracking one at Send), refused when it is held. */
function existingBranch(repoRoot: string, wanted: string, sessions: Session[]): { branch: string; base: string; track?: string } {
  const list = branchList(repoRoot, owners(sessions))
  let branch = wanted, track: string | undefined
  if (!list.local.some((b) => b.name === wanted)) {
    const remote = list.remote.find((r) => r.name === wanted) ?? list.remote.find((r) => r.name === `origin/${wanted}`)
    if (!remote) throw new SourceRefused("no_branch", `no branch ${wanted}`, { branch: wanted })
    branch = remote.name.slice(remote.name.indexOf("/") + 1) // platform-ok: a git ref name, /-separated on every OS
    if (!list.local.some((b) => b.name === branch)) track = remote.name
  }
  const holder = holderOfBranch(repoRoot, branch, sessions, list)
  if (holder) throw new SourceRefused("held_elsewhere", `${branch} is checked out elsewhere`, { branch, holder })
  const base = list.local.find((b) => b.isDefault)?.name ?? list.current ?? "HEAD"
  return { branch, base, track }
}

/** new: the name asked for, made free when it was automatic; a typed name that exists is refused. */
async function namedBranch(repoRoot: string, name: string, auto: boolean, taken: Set<string>): Promise<string> {
  const wanted = name.trim()
  if (!wanted || wanted.startsWith("-") || !(await refFormatOk(repoRoot, wanted))) {
    throw new SourceRefused("invalid_name", `not a valid branch name: ${wanted}`, { branch: wanted })
  }
  if (!taken.has(wanted)) return wanted
  if (!auto) throw new SourceRefused("branch_exists", `a branch ${wanted} already exists`, { branch: wanted })
  return ensureUnique(wanted, taken)
}

/** local: the folder switched to the picked branch, only when it is clean (VS Code's rule). */
function switchFolder(folder: string, to: string, sessions: Session[]): void {
  const r = switchBranch(folder, to, { requireClean: true })
  if (r.status === "switched") return
  if (r.status === "dirty") throw new SourceRefused("dirty", `the folder has ${r.changed} changed files`, { changed: r.changed })
  if (r.status === "checked_out_elsewhere") throw new SourceRefused("held_elsewhere", `${to} is checked out elsewhere`, { branch: to, holder: holderAt(r.path, sessions) })
  throw new SourceRefused("switch_failed", "message" in r ? r.message : r.status, { status: r.status })
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
  /** #152: a pull / merge request by "#N" (default: gh / glab with the user's own sign-in). */
  readPr?(cwd: string, ref: string): Promise<PrNumberRead>
}

/** Branch names being made right now, per repo: taken before git has them. */
const branchesInFlight = new Map<string, Set<string>>()

export async function createAsleep(deps: CreateAsleepDeps, args: CreateAsleepArgs): Promise<Session> {
  const { registry } = deps
  const source = args.source
  // reuse: another session's copy, branch and diff base; named after it, as a conversation continued in it is.
  const other = source?.kind === "reuse" ? registry.get(source.sessionId) : undefined
  if (source?.kind === "reuse" && !other) throw new SourceRefused("no_session", "that session is gone", { sessionId: source.sessionId })
  const base = args.requestedName?.trim() || other?.name || deriveName(args.workdir)
  const name = ensureUnique(base, registry.takenNames())
  registry.reserveName(name)
  let branchInFlight: { repoRoot: string; branch: string } | undefined
  try {
    let workdir = args.workdir
    let worktree: { repo_root: string; base_branch: string; session_branch: string } | undefined
    let baseCommits: Record<string, string> | undefined
    let prFields: { pr_forge: "github" | "gitlab"; pr_number: number; pr_url: string; push_remote?: string } | undefined
    if (other) {
      workdir = other.workdir
      if (other.repo_root && other.session_branch) worktree = { repo_root: other.repo_root, base_branch: other.base_branch || "HEAD", session_branch: other.session_branch }
      baseCommits = other.base_commits ?? (existsSync(workdir) ? deps.captureBaseCommits(workdir) : undefined)
    } else if (source?.kind === "local") {
      if (source.switchTo) switchFolder(args.workdir, source.switchTo, registry.list())
    } else if (args.worktreeHandle) {
      workdir = args.worktreeHandle.worktreeDir
      worktree = { repo_root: args.worktreeHandle.repoRoot, base_branch: args.worktreeHandle.baseBranch, session_branch: args.worktreeHandle.sessionBranch }
    } else if (source?.kind === "existing") {
      const info = await deps.repoInfo(args.workdir)
      if (!info.eligible || !info.repoRoot) throw new SourceRefused("no_worktree", `${args.workdir} is not a single git repository, so no worktree can be made`)
      const repoRoot = info.repoRoot
      const picked = existingBranch(repoRoot, source.branch, registry.list())
      if (picked.track) await createTrackingBranch(repoRoot, picked.branch, picked.track)
      workdir = join(deps.worktreesRoot(), repoSlug(repoRoot), deps.newId())
      worktree = { repo_root: repoRoot, base_branch: picked.base, session_branch: picked.branch }
    } else if (source?.kind === "pr") {
      const info = await deps.repoInfo(args.workdir)
      if (!info.eligible || !info.repoRoot) throw new SourceRefused("no_worktree", `${args.workdir} is not a single git repository, so no worktree can be made`)
      const repoRoot = info.repoRoot
      const read = await (deps.readPr ?? readPrByNumber)(repoRoot, `#${source.number}`)
      if (read.status !== "found") {
        throw new SourceRefused("pr_unavailable", `#${source.number} can't be worked on (${read.status})`, {
          status: read.status, ...("state" in read ? { state: read.state } : {}), ...("reason" in read ? { reason: read.reason } : {}),
        })
      }
      const pr = read.pr
      const sessions = registry.list()
      const got = await preparePrBranch(repoRoot, pr, source.divergedPolicy, (b) => holderOfBranch(repoRoot, b, sessions))
      if (!got.ok) {
        if (got.code === "held_elsewhere") throw new SourceRefused("held_elsewhere", `${got.branch} is checked out elsewhere`, { branch: got.branch, holder: got.holder })
        if (got.code === "local_ahead") throw new SourceRefused("local_ahead", `your ${got.branch} has ${got.ahead} commits the pull request doesn't`, { branch: got.branch, ahead: got.ahead })
        throw new SourceRefused("fetch_failed", got.message)
      }
      workdir = join(deps.worktreesRoot(), repoSlug(repoRoot), deps.newId())
      worktree = { repo_root: repoRoot, base_branch: pr.base || info.currentBranch || "HEAD", session_branch: got.branch }
      prFields = { pr_forge: pr.forge, pr_number: pr.number, pr_url: pr.url, ...(got.pushRemote ? { push_remote: got.pushRemote } : {}) }
    } else if (source?.kind === "new" || args.worktree !== false) {
      const info = await deps.repoInfo(args.workdir)
      if (info.eligible && info.repoRoot) {
        const repoRoot = info.repoRoot
        const inFlight = branchesInFlight.get(repoRoot) ?? new Set<string>()
        branchesInFlight.set(repoRoot, inFlight)
        const taken = new Set([...(await deps.existingBranches(repoRoot)), ...inFlight])
        for (const s of [...registry.list(), ...registry.listArchived()]) if (s.repo_root === repoRoot && s.session_branch) taken.add(s.session_branch)
        const branch = source?.kind === "new" && source.name?.trim()
          ? await namedBranch(repoRoot, source.name, source.auto === true, taken)
          : deriveSessionBranch(args.requestedName?.trim() || deriveName(args.workdir), taken)
        inFlight.add(branch)
        branchInFlight = { repoRoot, branch }
        const baseBranch = (source?.kind === "new" ? source.base : undefined) || args.baseBranch || info.currentBranch || "HEAD"
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
      } else if (source?.kind === "new") {
        throw new SourceRefused("no_worktree", `${args.workdir} is not a single git repository, so no worktree can be made`)
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
      // A worktree checked out at the first wake gets them then. Another session's copy: that session's.
      ...(other ? (baseCommits ? { base_commits: baseCommits } : {}) : worktree && !args.worktreeHandle ? {} : { base_commits: deps.captureBaseCommits(workdir) }),
      ...(worktree ?? {}),
      ...(source ? { source_kind: source.kind } : {}),
      ...(prFields ?? {}),
      ...(args.createdBy ? { created_by: args.createdBy } : {}),
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
