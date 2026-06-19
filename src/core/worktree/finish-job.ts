// src/core/worktree/finish-job.ts
import { finishWorktree, openPrForSession, discardSession, type FinishResult, type FinishSession } from "./finish"

export type FinishAction = "merge" | "pr" | "keep" | "discard"

export interface FinishJob {
  sessionId: string
  action: FinishAction
  status: "running" | "done" | "failed"
  stage?: string
  outcome?: FinishResult
  startedAt: number
  endedAt?: number
}

export interface FinishJobHooks {
  onUpdate: (job: FinishJob) => void   // broadcast over WS
  persist: (job: FinishJob) => void    // write onto the session record
  notify: (job: FinishJob) => void     // push notification on terminal
  now?: () => number                   // injectable clock (tests)
}

export interface FinishJobOpts {
  action: FinishAction
  skipVerify?: boolean
  commitFirst?: boolean
  commitMessage?: string
  cleanup?: boolean
  deleteBranch?: boolean
  draft?: boolean
  prRequiresGreen?: boolean
  prTitle?: string
  prBody?: string
}

const FAILURE_STATUSES = new Set<FinishResult["status"]>([
  "tests_failed", "sync_conflict", "dirty_overlap", "uncommitted",
  "no_verify", "push_auth_failed", "push_rejected", "non_ff", "error",
])
function isFailure(o: FinishResult): boolean { return FAILURE_STATUSES.has(o.status) }

const jobs = new Map<string, FinishJob>()
export function getFinishJob(sessionId: string): FinishJob | undefined { return jobs.get(sessionId) }
export function clearFinishJob(sessionId: string): void { jobs.delete(sessionId) }

export function startFinishJob(s: FinishSession & { id: string }, opts: FinishJobOpts, hooks: FinishJobHooks): FinishJob {
  const existing = jobs.get(s.id)
  if (existing && existing.status === "running") return existing
  const now = hooks.now ?? Date.now
  const job: FinishJob = { sessionId: s.id, action: opts.action, status: "running", startedAt: now() }
  jobs.set(s.id, job)
  hooks.onUpdate(job); hooks.persist(job)
  void run(s, opts, hooks, job)
  return job
}

async function run(s: FinishSession & { id: string }, opts: FinishJobOpts, hooks: FinishJobHooks, job: FinishJob): Promise<void> {
  const progress = (stage: string) => { job.stage = stage; hooks.onUpdate(job); hooks.persist(job) }
  let outcome: FinishResult
  try {
    if (opts.action === "keep") outcome = { status: "kept", branch: s.sessionBranch }
    else if (opts.action === "discard") outcome = await discardSession(s)
    else if (opts.action === "pr") outcome = await openPrForSession(s, opts, progress)
    else outcome = await finishWorktree(s, { skipVerify: opts.skipVerify, commitFirst: opts.commitFirst, commitMessage: opts.commitMessage, cleanup: opts.cleanup, deleteBranch: opts.deleteBranch }, progress)
  } catch (e: any) {
    outcome = { status: "error", message: String(e?.message ?? e) }
  }
  job.outcome = outcome
  job.stage = undefined
  job.endedAt = (hooks.now ?? Date.now)()
  job.status = isFailure(outcome) ? "failed" : "done"
  hooks.onUpdate(job); hooks.persist(job); hooks.notify(job)
}
