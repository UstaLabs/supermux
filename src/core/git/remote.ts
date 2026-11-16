// src/core/git/remote.ts
import { gitSync } from "./exec-sync"
import { branchOfRef } from "./refs"
import { dirtyFiles, mergeInProgress, syncBaseIntoBranch } from "./integrate"

function git(cwd: string, args: string[], timeout = 120_000): { ok: boolean; out: string } {
  return gitSync(cwd, args, timeout)
}

export interface RemoteStatus {
  isRepo: boolean            // workdir is inside a git work tree
  hasRemote: boolean         // the repo has any remote (a fork's PR branch may have no "origin")
  remotes: string[]          // their names: Publish asks which when there are several (VS Code)
  branch: string | null      // current branch, or null when detached
  detachedSha: string | null // short HEAD sha when detached (and isRepo)
  upstream: string | null    // e.g. "origin/mux/s", or null when unpublished
  ahead: number              // commits HEAD has that upstream lacks
  behind: number             // commits upstream has that HEAD lacks
}

/** Read the branch's remote-sync state. Local refs only — no network. */
export function remoteStatus(workdir: string): RemoteStatus {
  const inside = git(workdir, ["rev-parse", "--is-inside-work-tree"])
  const isRepo = inside.ok && inside.out === "true"

  const list = git(workdir, ["remote"])
  const remotes = list.ok ? list.out.split("\n").map((s) => s.trim()).filter(Boolean) : []
  const hasRemote = remotes.length > 0

  const br = git(workdir, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  const branch = br.ok && br.out ? br.out : null
  let detachedSha: string | null = null
  if (isRepo && !branch) {
    const sha = git(workdir, ["rev-parse", "--short", "HEAD"])
    detachedSha = sha.ok && sha.out ? sha.out : null
  }

  if (!hasRemote || !branch) return { isRepo, hasRemote, remotes, branch, detachedSha, upstream: null, ahead: 0, behind: 0 }

  const up = git(workdir, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
  const upstream = up.ok && up.out ? up.out : null
  if (!upstream) return { isRepo, hasRemote, remotes, branch, detachedSha, upstream: null, ahead: 0, behind: 0 }

  const counts = git(workdir, ["rev-list", "--count", "--left-right", "@{upstream}...HEAD"])
  let ahead = 0, behind = 0
  if (counts.ok) {
    const [b, a] = counts.out.split(/\s+/)
    behind = Number(b) || 0
    ahead = Number(a) || 0
  }
  return { isRepo, hasRemote, remotes, branch, detachedSha, upstream, ahead, behind }
}

/** The branch's upstream as its remote and its branch there ("origin" + "main"); null: not published. */
function upstreamOf(workdir: string): { remote: string; name: string } | null {
  const br = git(workdir, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  if (!br.ok || !br.out) return null
  const remote = git(workdir, ["config", `branch.${br.out}.remote`])
  const merge = git(workdir, ["config", `branch.${br.out}.merge`])
  if (!remote.ok || !merge.ok || !remote.out || remote.out === ".") return null
  return { remote: remote.out, name: branchOfRef(merge.out) }
}

/** Refresh remote-tracking refs (best-effort, timeboxed): the branch's own remote, else origin. */
export function fetchRemote(workdir: string, remote?: string): { ok: boolean; error?: string } {
  const r = git(workdir, ["fetch", remote ?? upstreamOf(workdir)?.remote ?? "origin", "--prune"], 30_000)
  return r.ok ? { ok: true } : { ok: false, error: r.out }
}

export type PushResult =
  | { status: "pushed" }
  | { status: "up_to_date" }
  | { status: "rejected_non_ff" }
  | { status: "auth_failed"; message: string }
  | { status: "error"; message: string }

export function isAuthError(lower: string): boolean {
  return lower.includes("authentication failed")
    || lower.includes("could not read username")
    || lower.includes("invalid username or password")
    || lower.includes("permission denied")
    || lower.includes("publickey")
    || lower.includes("terminal prompts disabled")
    || lower.includes("returned error: 403")
}

function doPush(workdir: string, extra: string[]): PushResult {
  const r = git(workdir, ["push", "--porcelain", ...extra])
  if (r.ok) return /\[up to date\]/i.test(r.out) ? { status: "up_to_date" } : { status: "pushed" }
  const lower = r.out.toLowerCase()
  if (lower.includes("non-fast-forward") || lower.includes("fetch first") || lower.includes("[rejected]")) {
    return { status: "rejected_non_ff" }
  }
  if (isAuthError(lower)) return { status: "auth_failed", message: r.out }
  return { status: "error", message: r.out }
}

/** The remote Publish uses unless one is picked: the only one, else origin, else the first. */
export function defaultRemote(workdir: string): string {
  const list = git(workdir, ["remote"])
  const remotes = list.ok ? list.out.split("\n").map((s) => s.trim()).filter(Boolean) : []
  return remotes.length === 1 ? remotes[0]! : remotes.includes("origin") ? "origin" : remotes[0] ?? "origin"
}

/** Publish Branch (VS Code): `push -u <remote> <branch>`, only the current branch; the remote is the one picked. */
export function publishBranch(workdir: string, remote?: string): PushResult {
  const r = remote ?? defaultRemote(workdir)
  if (r.startsWith("-")) return { status: "error", message: `bad remote: ${r}` }
  return doPush(workdir, ["-u", r, "HEAD"])
}

/** Push a published branch to its own upstream, by name (VS Code's `<remote> <local>:<upstream>`): an upstream with
 *  another name is pushed to, not refused by push.default=simple. */
export function pushBranch(workdir: string): PushResult {
  const up = upstreamOf(workdir)
  return up ? doPush(workdir, [up.remote, `HEAD:refs/heads/${up.name}`]) : doPush(workdir, [])
}

export type PullResult =
  | { status: "clean" }
  | { status: "up_to_date" }
  | { status: "conflict"; files: string[] }
  | { status: "dirty"; files: string[] }
  | { status: "auth_failed"; message: string }
  | { status: "error"; message: string }

/** Fetch origin, then merge the branch's upstream INTO it. Reuses the Finish
 *  flow's re-entrant merge semantics (conflict left in the worktree for the
 *  agent to resolve; the next call completes it). */
export function pullBranch(workdir: string): PullResult {
  // A resolved-but-incomplete prior merge must finish even on a "dirty" tree,
  // so only gate dirty + fetch when we're NOT resuming a merge.
  if (!mergeInProgress(workdir)) {
    const dirty = dirtyFiles(workdir)
    if (dirty.length) return { status: "dirty", files: dirty }
    const f = fetchRemote(workdir)
    if (!f.ok) {
      const lower = (f.error ?? "").toLowerCase()
      if (isAuthError(lower)) return { status: "auth_failed", message: f.error ?? "" }
      return { status: "error", message: f.error ?? "fetch failed" }
    }
  }
  const up = git(workdir, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
  if (!up.ok || !up.out) return { status: "error", message: "no upstream — publish the branch first" }

  const r = syncBaseIntoBranch(workdir, "@{upstream}")
  if (r.status === "up_to_date") return { status: "up_to_date" }
  if (r.status === "clean") return { status: "clean" }
  return { status: "conflict", files: r.files }
}
