// src/core/git/branches.ts
import { realpathSync } from "fs"
import { paths } from "@supermux/platform"
import { mergeInProgress } from "./integrate"
import { gitSync } from "./exec-sync"

function git(cwd: string, args: string[], timeout = 30_000): { ok: boolean; out: string } {
  return gitSync(cwd, args, timeout)
}

export interface LocalBranch { name: string; checkedOutAt: string | null }
export interface BranchList {
  repoRoot: string | null     // toplevel of the enclosing repo, null outside one
  current: string | null      // checked-out branch name; null when detached; non-null but absent from local[] when unborn
  detachedSha: string | null  // short HEAD sha when detached
  local: LocalBranch[]        // checkedOutAt = worktree path when the branch is checked out somewhere
  remote: string[]            // e.g. "origin/main"; origin/HEAD symref filtered out
}

/** Repo toplevel (realpath) for a path, or null when not inside a work tree. */
export function repoToplevel(workdir: string): string | null {
  const r = git(workdir, ["rev-parse", "--show-toplevel"])
  if (!r.ok || !r.out) return null
  try { return realpathSync(r.out) } catch { return r.out }
}

/** The branch a checkout is on now, or null when detached / not a repo (#72: a chat follows its copy's branch). */
export function liveBranch(workdir: string): string | null {
  const r = git(workdir, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  return r.ok && r.out ? r.out : null
}

/** List local + remote branches with worktree-occupancy info. Local refs only — no network. */
export function listBranches(workdir: string): BranchList {
  const repoRoot = repoToplevel(workdir)
  if (!repoRoot) return { repoRoot: null, current: null, detachedSha: null, local: [], remote: [] }

  const br = git(workdir, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  const current = br.ok && br.out ? br.out : null
  let detachedSha: string | null = null
  if (!current) {
    const sha = git(workdir, ["rev-parse", "--short", "HEAD"])
    detachedSha = sha.ok && sha.out ? sha.out : null
  }

  const locals = git(workdir, ["for-each-ref", "--format=%(refname:short)\t%(worktreepath)", "refs/heads"])
  const local: LocalBranch[] = !locals.ok || !locals.out ? [] : locals.out.split("\n").filter(Boolean).map((line) => {
    const [name, wt] = line.split("\t")
    let checkedOutAt: string | null = wt || null
    if (checkedOutAt) { try { checkedOutAt = realpathSync(checkedOutAt) } catch { /* keep raw */ } }
    return { name: name!, checkedOutAt }
  })

  // %(refname:short) renders the origin/HEAD symref as "origin/HEAD" or bare
  // "origin" depending on git version — drop /HEAD suffixes and slash-less names.
  const remotes = git(workdir, ["for-each-ref", "--format=%(refname:short)", "refs/remotes"])
  const remote = !remotes.ok || !remotes.out ? [] : remotes.out.split("\n").map((s) => s.trim())
    .filter((n) => n && n.includes("/") && !n.endsWith("/HEAD")) // platform-ok: a git ref name, /-separated on every OS

  return { repoRoot, current, detachedSha, local, remote }
}

export type SwitchResult =
  | { status: "switched"; branch: string }
  | { status: "dirty"; changed: number }                  // requireClean: the folder has changes (#72 G9)
  | { status: "clobber"; files: string[]; branch: string } // git refused: local changes would be overwritten
  | { status: "checked_out_elsewhere"; path: string }      // branch held by another worktree
  | { status: "merge_in_progress" }
  | { status: "invalid_name"; message: string }
  | { status: "error"; message: string }

/** Parse `git switch` refusal output into a typed result. */
function classifySwitchFailure(out: string, target: string): SwitchResult {
  if (/would be overwritten by checkout/i.test(out)) {
    // Tracked and untracked clobber messages both list the files in an
    // indented block right under a line ending in "overwritten by checkout:".
    const files: string[] = []
    let collecting = false
    for (const line of out.split("\n")) {
      if (/overwritten by checkout:/i.test(line)) { collecting = true; continue }
      if (!collecting) continue
      const m = line.match(/^\s+(\S.*)$/)
      if (m) files.push(m[1]!.trim())
      else if (files.length) collecting = false
    }
    return { status: "clobber", files, branch: target }
  }
  const wt = out.match(/already (?:checked out|used by worktree) at '([^']+)'/i)
  if (wt) return { status: "checked_out_elsewhere", path: wt[1]! }
  return { status: "error", message: out }
}

/** Switch the checkout to a branch. `create` makes a new branch off HEAD; a
 *  name from the remote list (e.g. "origin/foo") checks out a local tracking
 *  branch. Plain `git switch` semantics otherwise: non-conflicting uncommitted
 *  changes carry over, clobbering ones make git refuse (→ `clobber`). */
export function switchBranch(workdir: string, name: string, opts?: { create?: boolean; requireClean?: boolean }): SwitchResult {
  const target = name.trim()
  if (!target) return { status: "invalid_name", message: "branch name required" }
  if (mergeInProgress(workdir)) return { status: "merge_in_progress" }
  // #152's Local mode, VS Code's rule: a folder with changes is not switched at all (git alone carries non-clashing ones).
  if (opts?.requireClean) {
    const st = folderState(workdir)
    if (!st.clean) return { status: "dirty", changed: st.changed }
  }

  if (opts?.create) {
    // Belt-and-suspenders: check-ref-format would also reject these, but the
    // early return keeps a dash-leading name from ever reaching arg parsing.
    if (target.startsWith("-")) return { status: "invalid_name", message: `invalid branch name: ${target}` }
    const v = git(workdir, ["check-ref-format", "--branch", target])
    if (!v.ok) return { status: "invalid_name", message: v.out || `invalid branch name: ${target}` }
    const r = git(workdir, ["switch", "-c", target])
    return r.ok ? { status: "switched", branch: target } : classifySwitchFailure(r.out, target)
  }

  const list = listBranches(workdir)

  if (list.local.some((b) => b.name === target)) {
    const r = git(workdir, ["switch", target])
    return r.ok ? { status: "switched", branch: target } : classifySwitchFailure(r.out, target)
  }
  if (list.remote.includes(target)) {
    const localName = target.slice(target.indexOf("/") + 1) // platform-ok: a git ref name, /-separated on every OS
    const r = list.local.some((b) => b.name === localName)
      ? git(workdir, ["switch", localName])
      : git(workdir, ["switch", "-c", localName, "--track", target])
    return r.ok ? { status: "switched", branch: localName } : classifySwitchFailure(r.out, localName)
  }
  return { status: "error", message: `no such branch: ${target}` }
}

// ---- #72 G9: one git-branch service: a session's copy (G4) and the main folder (#152) call the same code ----

/** A path on the host's rules after the native realpath (Windows: "D:/a" = "D:\\a", 8.3 short names expanded). */
export function realPath(p: string): string {
  try { return realpathSync.native(p) } catch { return p }
}
export function samePath(a: string, b: string): boolean {
  return paths.isInside(a, b) && paths.isInside(b, a)
}

export interface BranchOwner { id: string; name: string; workdir: string }
/** Where a branch is checked out, seen from [cwd]: here, the main folder, a session's copy, a worktree no session owns. */
export type OpenIn =
  | { kind: "this" }
  | { kind: "folder" }
  | { kind: "session"; id: string; name: string }
  | { kind: "external"; path: string }

export interface BranchInfo { name: string; isDefault: boolean; upstream: string | null; ahead: number; behind: number; openIn: OpenIn | null }
export interface BranchListing {
  current: string | null
  detachedSha: string | null
  local: BranchInfo[]
  remote: Array<{ name: string; isDefault: boolean }>
}

/** The main worktree (the folder the repo was cloned into), from `git worktree list --porcelain`. */
function mainWorktree(cwd: string): string | null {
  const r = git(cwd, ["worktree", "list", "--porcelain"])
  const m = r.ok ? r.out.match(/^worktree (.+)$/m) : null
  return m ? realPath(m[1]!) : null
}

/** origin/HEAD's branch, else init.defaultBranch, else main / master, else the main folder's branch (a local-only repo). */
function defaultBranch(cwd: string, locals: string[], mainBranch: string | null): string | null {
  const head = git(cwd, ["symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"])
  if (head.ok && head.out.includes("/")) return head.out.slice(head.out.indexOf("/") + 1) // platform-ok: a git ref name
  const cfg = git(cwd, ["config", "--get", "init.defaultBranch"])
  if (cfg.ok && cfg.out && locals.includes(cfg.out)) return cfg.out
  return ["main", "master"].find((n) => locals.includes(n)) ?? mainBranch
}

/** "ahead 1, behind 2" / "gone" / "" from %(upstream:track,nobracket). */
function track(t: string): { ahead: number; behind: number } {
  return { ahead: Number(t.match(/ahead (\d+)/)?.[1] ?? 0), behind: Number(t.match(/behind (\d+)/)?.[1] ?? 0) }
}

/** Every local and remote branch: the default, its upstream and ↑↓, and where it is open. Local refs only, no network. */
export function branchList(cwd: string, owners: BranchOwner[]): BranchListing {
  const base = listBranches(cwd)
  const fmt = "%(refname:short)%09%(upstream:short)%09%(upstream:track,nobracket)"
  const refs = git(cwd, ["for-each-ref", `--format=${fmt}`, "refs/heads"])
  const up = new Map<string, { upstream: string | null; ahead: number; behind: number }>()
  for (const line of refs.ok ? refs.out.split("\n").filter(Boolean) : []) {
    const [name, upstream, t] = line.split("\t")
    up.set(name!, { upstream: upstream || null, ...track(t ?? "") })
  }
  const here = realPath(base.repoRoot ?? cwd), main = mainWorktree(cwd)
  const ownersAt = owners.map((o) => ({ ...o, at: realPath(o.workdir) }))
  const where = (checkedOutAt: string | null): OpenIn | null => {
    if (!checkedOutAt) return null
    const at = realPath(checkedOutAt)
    if (samePath(at, here)) return { kind: "this" }
    if (main && samePath(at, main)) return { kind: "folder" }
    const o = ownersAt.find((x) => samePath(x.at, at))
    return o ? { kind: "session", id: o.id, name: o.name } : { kind: "external", path: at }
  }
  const mainBranch = main ? base.local.find((b) => b.checkedOutAt && samePath(realPath(b.checkedOutAt), main))?.name ?? null : null
  const def = defaultBranch(cwd, base.local.map((b) => b.name), mainBranch)
  return {
    current: base.current,
    detachedSha: base.detachedSha,
    local: base.local.map((b) => ({ name: b.name, isDefault: b.name === def, ...(up.get(b.name) ?? { upstream: null, ahead: 0, behind: 0 }), openIn: where(b.checkedOutAt) })),
    remote: base.remote.map((name) => ({ name, isDefault: !!def && name === `origin/${def}` })),
  }
}

/** The folder's branch and whether it is clean (changed = files changed or new), from one `git status`. */
export function folderState(cwd: string): { branch: string | null; detachedSha: string | null; clean: boolean; changed: number } {
  const r = git(cwd, ["status", "--porcelain=v2", "--branch", "--untracked-files=normal"])
  const lines = r.ok ? r.out.split("\n").filter(Boolean) : []
  const head = lines.find((l) => l.startsWith("# branch.head "))?.slice(14) ?? null
  const oid = lines.find((l) => l.startsWith("# branch.oid "))?.slice(13) ?? null
  const changed = lines.filter((l) => !l.startsWith("#")).length
  const branch = head && head !== "(detached)" ? head : null
  return { branch, detachedSha: branch ? null : oid && oid !== "(initial)" ? oid.slice(0, 7) : null, clean: changed === 0, changed }
}
