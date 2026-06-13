// src/core/git/remote.ts
import { execFileSync } from "child_process"
import { dirtyFiles, mergeInProgress, syncBaseIntoBranch } from "./integrate"

function git(cwd: string, args: string[], timeout = 120_000): { ok: boolean; out: string } {
  try {
    return { ok: true, out: execFileSync("git", args, { cwd, encoding: "utf-8", timeout, stdio: ["pipe", "pipe", "pipe"] }).trim() }
  } catch (e: any) {
    return { ok: false, out: `${e?.stdout?.toString?.() ?? ""}${e?.stderr?.toString?.() ?? ""}`.trim() }
  }
}

export interface RemoteStatus {
  isRepo: boolean            // workdir is inside a git work tree
  hasRemote: boolean         // an "origin" remote exists
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

  const remotes = git(workdir, ["remote"])
  const hasRemote = remotes.ok && remotes.out.split("\n").map((s) => s.trim()).includes("origin")

  const br = git(workdir, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  const branch = br.ok && br.out ? br.out : null
  let detachedSha: string | null = null
  if (isRepo && !branch) {
    const sha = git(workdir, ["rev-parse", "--short", "HEAD"])
    detachedSha = sha.ok && sha.out ? sha.out : null
  }

  if (!hasRemote || !branch) return { isRepo, hasRemote, branch, detachedSha, upstream: null, ahead: 0, behind: 0 }

  const up = git(workdir, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
  const upstream = up.ok && up.out ? up.out : null
  if (!upstream) return { isRepo, hasRemote, branch, detachedSha, upstream: null, ahead: 0, behind: 0 }

  const counts = git(workdir, ["rev-list", "--count", "--left-right", "@{upstream}...HEAD"])
  let ahead = 0, behind = 0
  if (counts.ok) {
    const [b, a] = counts.out.split(/\s+/)
    behind = Number(b) || 0
    ahead = Number(a) || 0
  }
  return { isRepo, hasRemote, branch, detachedSha, upstream, ahead, behind }
}

/** Refresh remote-tracking refs from origin (best-effort, timeboxed). */
export function fetchRemote(workdir: string): { ok: boolean; error?: string } {
  const r = git(workdir, ["fetch", "origin", "--prune"], 30_000)
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

/** First push of an unpublished branch: pushes HEAD to origin and sets upstream. */
export function publishBranch(workdir: string): PushResult {
  return doPush(workdir, ["-u", "origin", "HEAD"])
}

/** Push an already-published branch to its upstream. */
export function pushBranch(workdir: string): PushResult {
  return doPush(workdir, [])
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
