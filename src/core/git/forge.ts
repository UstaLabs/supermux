// src/core/git/forge.ts
// A branch's pull request (GitHub, via `gh`) or merge request (GitLab, via `glab`), read into one shape for the
// Source Control pane (#72): state, draft, target, review/approvals, checks and comments. The CLIs hold the
// user's own sign-in; nothing here stores a token. Parsing is pure so it is tested without either CLI.

import { execFile } from "child_process"
import { proc } from "@supermux/platform"

export type Forge = "github" | "gitlab"
export type Exec = (cmd: string, args: string[], cwd: string) => Promise<{ ok: boolean; out: string }>

/** Runs a CLI with stdout kept apart from stderr (JSON must not be mixed with warnings). */
export const execCli: Exec = (cmd, args, cwd) => new Promise((resolve) => {
  execFile(cmd, args, { ...proc.spawnDefaults(), cwd, maxBuffer: 16 * 1024 * 1024, timeout: 30_000 }, (err, stdout, stderr) => {
    resolve(err ? { ok: false, out: String(stderr || err.message).trim() } : { ok: true, out: String(stdout).trim() })
  })
})

/** GitLab when origin's host says so (gitlab.com, gitlab.acme.io); any other host is taken as GitHub. */
export function forgeOf(originUrl: string): Forge | null {
  const m = originUrl.match(/^[^@]+@([^:]+):/) ?? originUrl.match(/^[a-z+]+:\/\/(?:[^@/]+@)?([^/:]+)/i)
  if (!m) return null
  return /(^|\.)gitlab\./i.test(m[1]!) || /^gitlab/i.test(m[1]!) ? "gitlab" : "github"
}

export interface PrCheck { name: string; state: "ok" | "bad" | "run" | "skip"; url?: string }
export interface PrComment { who: string; body: string; file?: string; line?: number; url?: string }
export interface PrReview { state: "approved" | "changes" | "required" | "none"; by: string[]; got?: number; need?: number }
export interface PrInfo {
  forge: Forge; number: number; url: string; title: string; state: "open" | "merged" | "closed"; draft: boolean
  target: string; head: string; review: PrReview; checks: PrCheck[]; comments: PrComment[]
}
export type PrRead =
  | { status: "found"; pr: PrInfo }
  | { status: "none"; forge: Forge }
  | { status: "unavailable"; forge?: Forge; reason: "no_remote" | "no_branch" | "no_cli" | "not_authed" | "error"; message?: string }

const withUrl = <T extends object>(o: T, url: unknown): T & { url?: string } => (typeof url === "string" && url ? { ...o, url } : o)

function ghCheck(c: any): PrCheck {
  if (c?.__typename === "StatusContext") {
    const s = String(c.state ?? "").toUpperCase()
    return withUrl({ name: String(c.context ?? ""), state: s === "SUCCESS" ? "ok" : s === "FAILURE" || s === "ERROR" ? "bad" : "run" }, c.targetUrl)
  }
  const status = String(c?.status ?? "").toUpperCase(), con = String(c?.conclusion ?? "").toUpperCase()
  const state: PrCheck["state"] = status !== "COMPLETED" ? "run"
    : con === "SUCCESS" ? "ok" : con === "SKIPPED" || con === "NEUTRAL" ? "skip" : "bad"
  // Two workflows can run a job of the same name (on push and on pull_request): GitHub shows "Workflow / job".
  const name = c?.workflowName ? `${c.workflowName} / ${c?.name ?? ""}` : String(c?.name ?? "")
  return withUrl({ name, state }, c?.detailsUrl)
}

/** One check per name: a commit pushed and opened as a PR runs a workflow twice; the newest run is the one shown. */
function newestRuns(rollup: any[]): any[] {
  const seen = new Map<string, any>()
  for (const c of rollup) {
    const key = `${c?.workflowName ?? ""}\0${c?.name ?? c?.context ?? ""}`
    const prev = seen.get(key)
    if (!prev || String(c?.startedAt ?? "") > String(prev?.startedAt ?? "")) seen.set(key, c)
  }
  return [...seen.values()]
}

/** `gh pr view --json …` plus `gh api …/pulls/N/comments` (inline review comments). */
export function parseGhPr(v: any, inline: any[]): PrInfo {
  const decision = String(v.reviewDecision ?? "").toUpperCase()
  const review: PrReview = {
    state: decision === "APPROVED" ? "approved" : decision === "CHANGES_REQUESTED" ? "changes" : decision === "REVIEW_REQUIRED" ? "required" : "none",
    by: (v.latestReviews ?? []).filter((r: any) => ["APPROVED", "CHANGES_REQUESTED"].includes(String(r?.state).toUpperCase()))
      .map((r: any) => String(r?.author?.login ?? "")).filter(Boolean),
  }
  const comments: PrComment[] = [
    ...(inline ?? []).filter((c: any) => !c?.in_reply_to_id).map((c: any) =>
      withUrl({ who: String(c?.user?.login ?? ""), body: String(c?.body ?? ""), file: String(c?.path ?? ""), line: Number(c?.line ?? c?.original_line) }, c?.html_url)),
    ...(v.comments ?? []).map((c: any) => withUrl({ who: String(c?.author?.login ?? ""), body: String(c?.body ?? "") }, c?.url)),
  ]
  const state = String(v.state ?? "").toUpperCase()
  return {
    forge: "github", number: Number(v.number), url: String(v.url ?? ""), title: String(v.title ?? ""),
    state: state === "MERGED" ? "merged" : state === "CLOSED" ? "closed" : "open", draft: v.isDraft === true,
    target: String(v.baseRefName ?? ""), head: String(v.headRefName ?? ""), review, checks: newestRuns(v.statusCheckRollup ?? []).map(ghCheck), comments,
  }
}

function glCheck(j: any): PrCheck {
  const s = String(j?.status ?? "")
  const state: PrCheck["state"] = s === "success" ? "ok" : s === "failed" || s === "canceled" ? "bad" : s === "skipped" || s === "manual" ? "skip" : "run"
  return withUrl({ name: String(j?.name ?? ""), state }, j?.web_url)
}

/** `glab mr view -F json`, its approvals, its head pipeline's jobs and its discussions. */
export function parseGlabMr(mr: any, approvals: any, jobs: any[], discussions: any[]): PrInfo {
  const by = (approvals?.approved_by ?? []).map((a: any) => String(a?.user?.username ?? "")).filter(Boolean)
  const need = Number(approvals?.approvals_required ?? 0)
  const review: PrReview = approvals?.approved === true && Number(approvals?.approvals_left ?? 0) === 0 && (need > 0 || by.length)
    ? { state: "approved", by }
    : need > 0 ? { state: "required", by, got: by.length, need } : { state: "none", by }
  const comments: PrComment[] = []
  for (const d of discussions ?? []) {
    const n = (d?.notes ?? [])[0]
    if (!n || n.system) continue
    const c: PrComment = { who: String(n.author?.username ?? ""), body: String(n.body ?? "") }
    if (n.position?.new_path) { c.file = String(n.position.new_path); c.line = Number(n.position.new_line) }
    comments.push(c)
  }
  const state = String(mr.state ?? "")
  return {
    forge: "gitlab", number: Number(mr.iid), url: String(mr.web_url ?? ""), title: String(mr.title ?? ""),
    state: state === "merged" ? "merged" : state === "closed" ? "closed" : "open", draft: mr.draft === true || mr.work_in_progress === true,
    target: String(mr.target_branch ?? ""), head: String(mr.source_branch ?? ""), review, checks: (jobs ?? []).map(glCheck), comments,
  }
}

const GH_FIELDS = "number,url,title,state,isDraft,baseRefName,headRefName,reviewDecision,latestReviews,statusCheckRollup,comments"

function failure(forge: Forge, out: string): PrRead {
  const o = out.toLowerCase()
  if (/no pull requests? found|no open merge request|no merge requests? (found|available)/.test(o)) return { status: "none", forge }
  if (/enoent|not found: (gh|glab)|command not found|is not recognized/.test(o)) return { status: "unavailable", forge, reason: "no_cli" }
  if (/auth login|not logged in|authenticat|401/.test(o)) return { status: "unavailable", forge, reason: "not_authed" }
  return { status: "unavailable", forge, reason: "error", message: out.slice(0, 500) }
}

const json = (s: string): any => { try { return JSON.parse(s) } catch { return null } }

/** The PR/MR whose head is `branch` on origin, read with the user's own gh / glab sign-in. */
export async function readPr(cwd: string, branch: string, exec: Exec = execCli): Promise<PrRead> {
  const origin = await exec("git", ["-C", cwd, "remote", "get-url", "origin"], cwd)
  const forge = origin.ok ? forgeOf(origin.out) : null
  if (!forge) return { status: "unavailable", reason: "no_remote" }
  if (forge === "github") {
    const v = await exec("gh", ["pr", "view", branch, "--json", GH_FIELDS], cwd)
    if (!v.ok) return failure(forge, v.out)
    const view = json(v.out)
    if (!view) return { status: "unavailable", forge, reason: "error", message: "unreadable answer from gh" }
    const inline = await exec("gh", ["api", `repos/{owner}/{repo}/pulls/${Number(view.number)}/comments`, "--paginate"], cwd)
    return { status: "found", pr: parseGhPr(view, inline.ok ? json(inline.out) ?? [] : []) }
  }
  const v = await exec("glab", ["mr", "view", branch, "-F", "json"], cwd)
  if (!v.ok) return failure(forge, v.out)
  const mr = json(v.out)
  if (!mr) return { status: "unavailable", forge, reason: "error", message: "unreadable answer from glab" }
  const iid = Number(mr.iid), pipeline = Number(mr.head_pipeline?.id ?? 0)
  const [ap, jobs, disc] = await Promise.all([
    exec("glab", ["api", `projects/:id/merge_requests/${iid}/approvals`], cwd),
    pipeline ? exec("glab", ["api", `projects/:id/pipelines/${pipeline}/jobs`], cwd) : Promise.resolve({ ok: true, out: "[]" }),
    exec("glab", ["api", `projects/:id/merge_requests/${iid}/discussions`], cwd),
  ])
  return { status: "found", pr: parseGlabMr(mr, ap.ok ? json(ap.out) : null, jobs.ok ? json(jobs.out) ?? [] : [], disc.ok ? json(disc.out) ?? [] : []) }
}
