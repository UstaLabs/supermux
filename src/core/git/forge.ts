// src/core/git/forge.ts
// A branch's pull request (GitHub, via `gh`) or merge request (GitLab, via `glab`), read into one shape for the
// Source Control pane (#72): state, draft, target, review/approvals, checks and comments. The CLIs hold the
// user's own sign-in; nothing here stores a token. Parsing is pure so it is tested without either CLI.

import { spawnCommand } from "../process/launcher"

export type Forge = "github" | "gitlab"
export type Exec = (cmd: string, args: string[], cwd: string) => Promise<{ ok: boolean; out: string }>

/** The most a CLI answer is read: a PR's JSON with its comments, or a job log's tail. */
const MAX_OUT = 16 * 1024 * 1024

/** Runs a CLI (Windows .cmd shims included) with stdout kept apart from stderr: JSON must not be mixed with warnings. */
export const execCli: Exec = (cmd, args, cwd) => new Promise((resolve) => {
  let out = "", err = "", done = false
  const finish = (r: { ok: boolean; out: string }) => { if (!done) { done = true; clearTimeout(timer); resolve(r) } }
  const child = spawnCommand(cmd, args, { cwd, stdio: ["ignore", "pipe", "pipe"] })
  const timer = setTimeout(() => { child.kill(); finish({ ok: false, out: `${cmd} timed out` }) }, 30_000)
  child.stdout?.on("data", (b: Buffer) => { if (out.length < MAX_OUT) out += b.toString("utf-8") })
  child.stderr?.on("data", (b: Buffer) => { if (err.length < 64 * 1024) err += b.toString("utf-8") })
  child.on("error", (e) => finish({ ok: false, out: e.message }))
  child.on("close", (code) => finish(code === 0 ? { ok: true, out: out.trim() } : { ok: false, out: (err || out).trim() }))
})

/** The host of a remote's URL (scp-like `git@host:a/b`, ssh:// or https://), null for a local path. */
export function hostOf(originUrl: string): string | null {
  const m = originUrl.match(/^[^@/]+@([^:/]+):/) ?? originUrl.match(/^[a-z+]+:\/\/(?:[^@/]+@)?([^/:]+)/i)
  return m ? m[1]! : null
}

/** GitLab when origin's host says so (gitlab.com, gitlab.acme.io); any other host is taken as GitHub. */
export function forgeOf(originUrl: string): Forge | null {
  const host = hostOf(originUrl)
  if (!host) return null
  return /(^|\.)gitlab\./i.test(host) || /^gitlab/i.test(host) ? "gitlab" : "github"
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

// ---- writes ----

async function forgeAt(cwd: string, exec: Exec): Promise<Forge | null> {
  const origin = await exec("git", ["-C", cwd, "remote", "get-url", "origin"], cwd)
  return origin.ok ? forgeOf(origin.out) : null
}
const firstUrl = (s: string) => (s.match(/https?:\/\/\S+/) ?? [])[0] ?? ""
const numberIn = (url: string) => Number((url.match(/\/(?:pull|merge_requests)\/(\d+)/) ?? [])[1] ?? 0)

export type CreatePrResult =
  | { status: "opened"; url: string; number: number }
  | { status: "exists"; url: string }
  | { status: "unavailable"; forge?: Forge; reason: "no_remote" | "no_branch" | "no_cli" | "not_authed" | "no_commits" | "base_missing" | "error"; message?: string }

/** gh's / glab's refusals said in plain words by the app: nothing to propose, or a target the forge doesn't have. */
function createFailure(forge: Forge, out: string): CreatePrResult {
  // A missing target first: GitHub then also says "no commits between", which is not the cause.
  if (/base ref must be a branch|base sha can't be blank|target branch .*(does not exist|not found)|couldn't find remote ref|invalid target branch/i.test(out))
    return { status: "unavailable", forge, reason: "base_missing", message: out.slice(0, 500) }
  if (/no commits between|has no new commits|no changes between/i.test(out)) return { status: "unavailable", forge, reason: "no_commits", message: out.slice(0, 500) }
  const f = failure(forge, out)
  return f.status === "unavailable" ? f : { status: "unavailable", forge, reason: "error", message: out.slice(0, 500) }
}

/** Opens a PR (GitHub) / MR (GitLab) from `branch` into `target`. The branch must already be on origin. */
export async function createPr(cwd: string, branch: string, o: { title: string; body: string; target: string; draft: boolean }, exec: Exec = execCli): Promise<CreatePrResult> {
  const forge = await forgeAt(cwd, exec)
  if (!forge) return { status: "unavailable", reason: "no_remote" }
  const r = forge === "github"
    ? await exec("gh", ["pr", "create", "--head", branch, "--base", o.target, "--title", o.title, "--body", o.body, ...(o.draft ? ["--draft"] : [])], cwd)
    : await exec("glab", ["mr", "create", "--source-branch", branch, "--target-branch", o.target, "--title", o.title, "--description", o.body, ...(o.draft ? ["--draft"] : []), "--yes"], cwd)
  if (r.ok) { const url = firstUrl(r.out); return { status: "opened", url, number: numberIn(url) } }
  if (/already exists/i.test(r.out)) return { status: "exists", url: firstUrl(r.out) }
  return createFailure(forge, r.out)
}

const validBranch = async (cwd: string, name: string, exec: Exec) =>
  !!name && !name.startsWith("-") && (await exec("git", ["check-ref-format", "--branch", name], cwd)).ok

/**
 * Can a PR/MR from HEAD into `target` be opened? The target must be a branch on origin, and HEAD must have commits it
 * doesn't. `local`: the target exists here, so "Publish <target>" can put it on origin.
 */
export type PrBase = { target: string; onRemote: boolean; local: boolean; ahead: number | null }
export async function prBase(cwd: string, target: string, exec: Exec = execCli): Promise<PrBase | null> {
  // No target (a chat in a folder has no base): origin's own default branch, asked from origin, never a guessed main.
  if (!target) {
    const head = await exec("git", ["-C", cwd, "ls-remote", "--symref", "origin", "HEAD"], cwd)
    target = (head.ok ? head.out.match(/^ref: refs\/heads\/(\S+)\s+HEAD/m)?.[1] : undefined) ?? ""
    if (!target) return null
  }
  if (!(await validBranch(cwd, target, exec))) return null
  const [ls, here] = await Promise.all([
    exec("git", ["-C", cwd, "ls-remote", "--heads", "origin", `refs/heads/${target}`], cwd),
    exec("git", ["-C", cwd, "rev-parse", "--verify", "--quiet", `refs/heads/${target}`], cwd),
  ])
  const remoteSha = ls.ok ? ls.out.split(/\s+/)[0] ?? "" : ""
  const onRemote = /^[0-9a-f]{40}$/.test(remoteSha)
  const local = here.ok && !!here.out.trim()
  const tip = onRemote ? remoteSha : local ? here.out.trim() : ""
  if (!tip) return { target, onRemote, local, ahead: null }
  // The remote tip may not be here yet: fetch it, then count.
  if (onRemote && !(await exec("git", ["-C", cwd, "cat-file", "-e", `${tip}^{commit}`], cwd)).ok) await exec("git", ["-C", cwd, "fetch", "-q", "origin", target], cwd)
  const n = await exec("git", ["-C", cwd, "rev-list", "--count", `${tip}..HEAD`], cwd)
  return { target, onRemote, local, ahead: n.ok ? Number(n.out) || 0 : null }
}

/** "Publish <target>": a local branch (the chat's base) put on origin, tracking it, so a PR/MR can target it. */
export async function publishBase(cwd: string, target: string, exec: Exec = execCli): Promise<{ ok: true } | { ok: false; reason: "bad" | "failed"; message?: string }> {
  if (!(await validBranch(cwd, target, exec))) return { ok: false, reason: "bad" }
  const r = await exec("git", ["-C", cwd, "push", "-u", "origin", `refs/heads/${target}:refs/heads/${target}`], cwd)
  return r.ok ? { ok: true } : { ok: false, reason: "failed", message: r.out.slice(0, 500) }
}

/** Draft → ready for review. */
export async function markReady(cwd: string, number: number, exec: Exec = execCli): Promise<{ ok: true } | { ok: false; message: string }> {
  const forge = await forgeAt(cwd, exec)
  if (!forge) return { ok: false, message: "no origin" }
  const r = forge === "github" ? await exec("gh", ["pr", "ready", String(number)], cwd) : await exec("glab", ["mr", "update", String(number), "--ready"], cwd)
  return r.ok ? { ok: true } : { ok: false, message: r.out.slice(0, 500) }
}

/** The most a failing log gives the agent: its tail, where the error is. */
const LOG_TAIL = 20_000

/** A failing check's log, from the check's own URL (GET …/git/pr gives it): GitHub Actions job or GitLab CI job. */
export async function checkLog(cwd: string, checkUrl: string, exec: Exec = execCli): Promise<{ ok: true; log: string } | { ok: false; reason: "unknown_check" | "failed"; message?: string }> {
  const gh = checkUrl.match(/\/actions\/runs\/\d+\/job\/(\d+)/), gl = checkUrl.match(/\/-\/jobs\/(\d+)/)
  if (!gh && !gl) return { ok: false, reason: "unknown_check" }
  const r = gh ? await exec("gh", ["run", "view", "--job", gh[1]!, "--log-failed"], cwd) : await exec("glab", ["api", `projects/:id/jobs/${gl![1]}/trace`], cwd)
  return r.ok ? { ok: true, log: r.out.length > LOG_TAIL ? r.out.slice(-LOG_TAIL) : r.out } : { ok: false, reason: "failed", message: r.out.slice(0, 500) }
}

// ---- #72 G10: a project with no remote yet ----

/** `git remote add <name> <url>`: a name git accepts (no spaces), a URL; a name already taken is "exists". */
export async function addRemote(cwd: string, name: string, url: string, exec: Exec = execCli): Promise<{ ok: true } | { ok: false; reason: "bad" | "exists" | "failed"; message?: string }> {
  if (!/^[A-Za-z0-9._-]+$/.test(name) || !url.trim() || url.trim().startsWith("-")) return { ok: false, reason: "bad" }
  const r = await exec("git", ["-C", cwd, "remote", "add", name, url.trim()], cwd)
  if (r.ok) return { ok: true }
  return /already exists/i.test(r.out) ? { ok: false, reason: "exists" } : { ok: false, reason: "failed", message: r.out.slice(0, 500) }
}

export type PublishNewResult = { ok: true; url: string; base?: string } | { ok: false; reason: "bad" | "no_cli" | "not_authed" | "name_taken" | "error"; url?: string; message?: string }

/** VS Code's "Publish to GitHub": create the repo on the forge from this folder, add it as origin and push the branch. */
export async function publishNew(
  cwd: string,
  o: { forge: Forge; name: string; visibility: "private" | "public"; branch?: string; base?: string },
  exec: Exec = execCli,
): Promise<PublishNewResult> {
  if (!/^[A-Za-z0-9._-]+$/.test(o.name)) return { ok: false, reason: "bad" }
  // A chat's branch publishes its base first (when it has one here): the new repo's default branch is the base, so a
  // pull request into it can be opened straight away, as a developer expects.
  const base = o.base && o.base !== o.branch && (await validBranch(cwd, o.base, exec))
    && (await exec("git", ["-C", cwd, "rev-parse", "--verify", "--quiet", `refs/heads/${o.base}`], cwd)).ok ? o.base : undefined
  const fail = (out: string): PublishNewResult => {
    const f = failure(o.forge, out)
    return f.status === "unavailable" && (f.reason === "no_cli" || f.reason === "not_authed") ? { ok: false, reason: f.reason } : { ok: false, reason: "error", message: out.slice(0, 500) }
  }
  // The repo is made empty, then origin is added and the branch pushed from here. Never gh's `--source`: it refuses a
  // linked worktree (a chat's own copy, whose .git is a file) as "not a git repository". origin lands in the project's
  // shared config, so every copy of the project sees it.
  const created = o.forge === "github"
    ? await exec("gh", ["repo", "create", o.name, `--${o.visibility}`], cwd)
    : await exec("glab", ["repo", "create", o.name, `--${o.visibility}`, "--defaultBranch", base ?? o.branch ?? "main"], cwd)
  if (!created.ok) return /name already exists|has already been taken|already exists/i.test(created.out) ? { ok: false, reason: "name_taken", message: created.out.slice(0, 500) } : fail(created.out)
  const url = firstUrl(created.out)
  if (!url) return { ok: false, reason: "error", message: created.out.slice(0, 500) }
  // gh's own choice of protocol (ssh or https), so the push uses the credentials gh set up.
  const proto = o.forge === "github" ? await exec("gh", ["config", "get", "git_protocol", "-h", new URL(url).host], cwd) : { ok: false, out: "" }
  const remote = proto.ok && proto.out.trim() === "ssh" ? `git@${new URL(url).host}:${new URL(url).pathname.slice(1)}.git` : `${url}.git`
  const add = await exec("git", ["-C", cwd, "remote", "add", "origin", remote], cwd)
  if (!add.ok) return { ok: false, reason: "error", url, message: `Created ${url}, but adding it as origin failed: ${add.out.slice(0, 400)}` }
  // The base first: the first branch on an empty repo becomes its default.
  if (base) {
    const b = await exec("git", ["-C", cwd, "push", "-u", "origin", `refs/heads/${base}:refs/heads/${base}`], cwd)
    if (!b.ok) return { ok: false, reason: "error", url, message: `Created ${url} and added it as origin, but pushing ${base} failed: ${b.out.slice(0, 400)}` }
  }
  const push = await exec("git", ["-C", cwd, "push", "-u", "origin", "HEAD"], cwd)
  return push.ok ? { ok: true, url, ...(base ? { base } : {}) } : { ok: false, reason: "error", url, message: `Created ${url} and added it as origin, but the push failed: ${push.out.slice(0, 400)}` }
}

// ---- #152: the launcher's Pull request tab ----

/** An open PR (GitHub) / MR (GitLab) as the launcher lists it: enough to say where a session on it works and pushes. */
export interface PrSummary {
  forge: Forge
  number: number
  title: string
  url: string
  author: string
  /** The PR's target: a session on it has it as its base. */
  base: string
  /** The PR's own branch, as the repo it lives in names it. */
  head: string
  /** "owner/repo" the head lives in: origin's own, or a fork's. */
  headRepo: string
  isFork: boolean
  /** A fork's owner and clone URL (in origin's scheme): the remote a session adds so its pushes update the PR. */
  headOwner?: string
  headUrl?: string
  /** The author lets maintainers push to the fork's branch (GitHub maintainerCanModify, GitLab allow_collaboration). */
  maintainerCanModify: boolean
  draft: boolean
  checks: "ok" | "bad" | "run" | "none"
  /** Commits the local branch of the same name has that the PR's head doesn't (local refs, no fetch). */
  localAhead?: number
}
type Unavailable = Extract<PrRead, { status: "unavailable" }>
export type PrList = { status: "ok"; forge: Forge; repo: string; prs: PrSummary[] } | Unavailable
export type PrNumberRead =
  | { status: "found"; pr: PrSummary }
  | { status: "none"; forge: Forge }
  | { status: "not_open"; forge: Forge; number: number; state: "merged" | "closed" }
  | { status: "other_repo"; forge: Forge; repo: string }
  | { status: "bad_ref" }
  | Unavailable

/** "#123", "!7", "42", or a PR / MR URL (GitHub /pull/N, GitLab /-/merge_requests/N). */
export function parsePrRef(ref: string): { number: number; repo?: string } | null {
  const s = ref.trim()
  const n = s.match(/^[#!]?(\d+)$/)
  if (n) return Number(n[1]) > 0 ? { number: Number(n[1]) } : null
  const u = s.match(/^https?:\/\/[^/]+\/(.+?)\/(?:pull|-\/merge_requests|merge_requests)\/(\d+)(?:[/?#].*)?$/i)
  return u && Number(u[2]) > 0 ? { number: Number(u[2]), repo: u[1]! } : null
}

/** "owner/repo" (GitLab: "group/sub/repo") from a remote's URL. */
function repoPathOf(originUrl: string): string | null {
  const m = originUrl.match(/^[^@/]+@[^:/]+:(.+?)(?:\.git)?\/?$/) ?? originUrl.match(/^[a-z+]+:\/\/(?:[^@/]+@)?[^/]+\/(.+?)(?:\.git)?\/?$/i)
  return m ? m[1]! : null
}
const sshRemote = (originUrl: string) => /^[^@/]+@[^:/]+:/.test(originUrl) || /^ssh:\/\//i.test(originUrl)

function rollupChecks(states: PrCheck["state"][]): PrSummary["checks"] {
  if (states.includes("bad")) return "bad"
  if (states.includes("run")) return "run"
  return states.includes("ok") ? "ok" : "none"
}

const GH_LIST_FIELDS = "number,title,url,state,headRefName,headRepository,headRepositoryOwner,isCrossRepository,maintainerCanModify,baseRefName,isDraft,author,statusCheckRollup"

function ghSummary(v: any, origin: string, host: string): PrSummary {
  const owner = String(v.headRepositoryOwner?.login ?? ""), name = String(v.headRepository?.name ?? "")
  const isFork = v.isCrossRepository === true
  return {
    forge: "github", number: Number(v.number), title: String(v.title ?? ""), url: String(v.url ?? ""), author: String(v.author?.login ?? ""),
    base: String(v.baseRefName ?? ""), head: String(v.headRefName ?? ""), headRepo: isFork ? `${owner}/${name}` : repoPathOf(origin) ?? `${owner}/${name}`, isFork,
    ...(isFork ? { headOwner: owner, headUrl: sshRemote(origin) ? `git@${host}:${owner}/${name}.git` : `https://${host}/${owner}/${name}.git` } : {}),
    maintainerCanModify: v.maintainerCanModify === true, draft: v.isDraft === true,
    checks: rollupChecks(newestRuns(v.statusCheckRollup ?? []).map(ghCheck).map((c) => c.state)),
  }
}

function glChecks(status: unknown): PrSummary["checks"] {
  const s = String(status ?? "")
  if (!s) return "none"
  return s === "success" ? "ok" : s === "failed" || s === "canceled" ? "bad" : s === "skipped" || s === "manual" ? "none" : "run"
}

async function glSummary(mr: any, origin: string, cwd: string, exec: Exec): Promise<PrSummary> {
  const isFork = mr.source_project_id != null && mr.target_project_id != null && Number(mr.source_project_id) !== Number(mr.target_project_id)
  let fork: { headRepo: string; headOwner?: string; headUrl?: string } = { headRepo: repoPathOf(origin) ?? "" }
  if (isFork) {
    const p = await exec("glab", ["api", `projects/${Number(mr.source_project_id)}`], cwd)
    const proj = p.ok ? json(p.out) : null
    fork = {
      headRepo: String(proj?.path_with_namespace ?? ""),
      ...(proj?.namespace?.path ? { headOwner: String(proj.namespace.path) } : {}),
      ...(proj ? { headUrl: String(sshRemote(origin) ? proj.ssh_url_to_repo ?? "" : proj.http_url_to_repo ?? "") } : {}),
    }
  }
  return {
    forge: "gitlab", number: Number(mr.iid), title: String(mr.title ?? ""), url: String(mr.web_url ?? ""), author: String(mr.author?.username ?? ""),
    base: String(mr.target_branch ?? ""), head: String(mr.source_branch ?? ""), ...fork, isFork,
    maintainerCanModify: mr.allow_collaboration === true || mr.allow_maintainer_to_push === true,
    draft: mr.draft === true || mr.work_in_progress === true, checks: glChecks(mr.head_pipeline?.status ?? mr.pipeline?.status),
  }
}

/** A same-repo PR whose branch is also here: how many local commits its head doesn't have (a checkout would replace them). */
async function withLocalAhead(pr: PrSummary, cwd: string, exec: Exec): Promise<PrSummary> {
  if (pr.isFork || !pr.head) return pr
  const has = await exec("git", ["-C", cwd, "rev-parse", "--verify", "--quiet", `refs/heads/${pr.head}`], cwd)
  if (!has.ok) return pr
  const n = await exec("git", ["-C", cwd, "rev-list", "--count", `refs/remotes/origin/${pr.head}..refs/heads/${pr.head}`], cwd)
  return n.ok ? { ...pr, localAhead: Number(n.out) || 0 } : pr
}

async function originOf(cwd: string, exec: Exec): Promise<{ url: string; forge: Forge; host: string } | null> {
  const o = await exec("git", ["-C", cwd, "remote", "get-url", "origin"], cwd)
  const forge = o.ok ? forgeOf(o.out) : null
  return o.ok && forge ? { url: o.out, forge, host: hostOf(o.out) ?? "" } : null
}

/** The open PRs/MRs of origin's repo, newest first (the forge's order), optionally searched, with the user's own gh / glab sign-in. */
export async function listOpenPrs(cwd: string, q: string, exec: Exec = execCli): Promise<PrList> {
  const origin = await originOf(cwd, exec)
  if (!origin) return { status: "unavailable", reason: "no_remote" }
  const search = q.trim()
  const repo = `${origin.host}/${repoPathOf(origin.url) ?? ""}`
  if (origin.forge === "github") {
    const r = await exec("gh", ["pr", "list", "--state", "open", "--limit", "50", "--json", GH_LIST_FIELDS, ...(search ? ["--search", search] : [])], cwd)
    if (!r.ok) return failure("github", r.out) as Unavailable
    const list = json(r.out)
    if (!Array.isArray(list)) return { status: "unavailable", forge: "github", reason: "error", message: "unreadable answer from gh" }
    const prs = await Promise.all(list.map((v) => withLocalAhead(ghSummary(v, origin.url, origin.host), cwd, exec)))
    return { status: "ok", forge: "github", repo, prs }
  }
  const r = await exec("glab", ["mr", "list", "-F", "json", "--per-page", "50", ...(search ? ["--search", search] : [])], cwd)
  if (!r.ok) return failure("gitlab", r.out) as Unavailable
  const list = json(r.out)
  if (!Array.isArray(list)) return { status: "unavailable", forge: "gitlab", reason: "error", message: "unreadable answer from glab" }
  const prs = await Promise.all(list.map(async (mr) => withLocalAhead(await glSummary(mr, origin.url, cwd, exec), cwd, exec)))
  return { status: "ok", forge: "gitlab", repo, prs }
}

/** One PR/MR by "#N", "!N", "N" or its URL: only origin's own repo, and only an open one (a session works on it). */
export async function readPrByNumber(cwd: string, ref: string, exec: Exec = execCli): Promise<PrNumberRead> {
  const parsed = parsePrRef(ref)
  if (!parsed) return { status: "bad_ref" }
  const origin = await originOf(cwd, exec)
  if (!origin) return { status: "unavailable", reason: "no_remote" }
  const own = repoPathOf(origin.url)
  if (parsed.repo && own && parsed.repo.toLowerCase() !== own.toLowerCase()) return { status: "other_repo", forge: origin.forge, repo: parsed.repo }
  const missing = (out: string) => /could not resolve to a pull ?request|merge request not found|404 not found/i.test(out)
  if (origin.forge === "github") {
    const r = await exec("gh", ["pr", "view", String(parsed.number), "--json", GH_LIST_FIELDS], cwd)
    if (!r.ok) return missing(r.out) ? { status: "none", forge: "github" } : failure("github", r.out) as Unavailable | { status: "none"; forge: Forge }
    const v = json(r.out)
    if (!v) return { status: "unavailable", forge: "github", reason: "error", message: "unreadable answer from gh" }
    const state = String(v.state ?? "").toUpperCase()
    if (state !== "OPEN") return { status: "not_open", forge: "github", number: parsed.number, state: state === "MERGED" ? "merged" : "closed" }
    return { status: "found", pr: await withLocalAhead(ghSummary(v, origin.url, origin.host), cwd, exec) }
  }
  const r = await exec("glab", ["mr", "view", String(parsed.number), "-F", "json"], cwd)
  if (!r.ok) return missing(r.out) ? { status: "none", forge: "gitlab" } : failure("gitlab", r.out) as Unavailable | { status: "none"; forge: Forge }
  const mr = json(r.out)
  if (!mr) return { status: "unavailable", forge: "gitlab", reason: "error", message: "unreadable answer from glab" }
  const state = String(mr.state ?? "")
  if (state !== "opened") return { status: "not_open", forge: "gitlab", number: parsed.number, state: state === "merged" ? "merged" : "closed" }
  return { status: "found", pr: await withLocalAhead(await glSummary(mr, origin.url, cwd, exec), cwd, exec) }
}
