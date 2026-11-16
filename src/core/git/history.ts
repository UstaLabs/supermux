// src/core/git/history.ts
// The History tab (#72): this chat's commits (what its branch has that the base doesn't) and the base's
// recent ones, newest first; and what can be done to one commit — revert it, branch or tag from it.

import { gitAsync } from "./exec"

export interface LogCommit { sha: string; subject: string; author: string; at: number; pushed?: boolean }
/** incoming (#72): how many commits the base has that this chat doesn't yet (the Graph's "Incoming Changes"). */
export interface History { branch: string | null; base: string | null; chat: LogCommit[]; baseCommits: LogCommit[]; incoming: number }

const SEP = "\x1f", END = "\x1e"
const FORMAT = `--format=%H${SEP}%s${SEP}%an${SEP}%ct${END}`

function parse(out: string): LogCommit[] {
  return out.split(END).map((r) => r.trim()).filter(Boolean).map((r) => {
    const [sha, subject, author, at] = r.split(SEP)
    return { sha: sha!, subject: subject ?? "", author: author ?? "", at: Number(at) * 1000 || 0 }
  })
}

const verify = (dir: string, ref: string) => gitAsync(dir, ["rev-parse", "--verify", "--quiet", `${ref}^{commit}`]).then(() => true, () => false)

/** `base` is the chat's own (null: none known; then the chat's list is just the branch's recent commits). */
export async function history(dir: string, base: string | null, limit = 50): Promise<History> {
  const branch = await gitAsync(dir, ["symbolic-ref", "--quiet", "--short", "HEAD"]).catch(() => null)
  if (!(await verify(dir, "HEAD"))) return { branch, base, chat: [], baseCommits: [], incoming: 0 }
  // A base that is the branch itself (a chat working on the branch it started from) is no base: its list is the
  // branch's own commits, as with none.
  const hasBase = !!base && base !== "HEAD" && base !== branch && (await verify(dir, base))
  // The base as last fetched (origin/main when main has an upstream): where a teammate's pushes show, and what Get
  // latest merges (it fetches it first). The local base only when it has no upstream.
  const baseUp = hasBase ? await gitAsync(dir, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", `${base}@{upstream}`]).catch(() => "") : ""
  const baseRef = baseUp && (await verify(dir, baseUp)) ? baseUp : base
  const chatRange = hasBase ? [`${baseRef}..HEAD`] : ["HEAD"]
  const [chatOut, baseOut, upstream, incomingOut] = await Promise.all([
    gitAsync(dir, ["log", FORMAT, `-n${limit}`, ...chatRange], { trim: false }),
    hasBase ? gitAsync(dir, ["log", FORMAT, `-n${Math.min(limit, 20)}`, baseRef!], { trim: false }) : Promise.resolve(""),
    gitAsync(dir, ["rev-parse", "--verify", "--quiet", "@{upstream}"]).catch(() => ""),
    hasBase ? gitAsync(dir, ["rev-list", "--count", `HEAD..${baseRef}`]).catch(() => "0") : Promise.resolve("0"),
  ])
  let chat = parse(chatOut)
  if (upstream) {
    const pushed = new Set(parse(await gitAsync(dir, ["log", FORMAT, `-n${limit}`, upstream], { trim: false }).catch(() => "")).map((c) => c.sha))
    chat = chat.map((c) => ({ ...c, pushed: pushed.has(c.sha) }))
  }
  return { branch, base: hasBase ? base : null, chat, baseCommits: parse(baseOut), incoming: Number(incomingOut) || 0 }
}

const SHA = /^[0-9a-f]{7,40}$/
/** A branch or tag name git accepts (checked by git itself). */
async function validRef(dir: string, kind: "branch" | "tag", name: string): Promise<boolean> {
  if (!name || name.startsWith("-")) return false
  return gitAsync(dir, ["check-ref-format", kind === "branch" ? "--branch" : "--normalize", kind === "branch" ? name : `refs/tags/${name}`]).then(() => true, () => false)
}

export type RefOpResult = { ok: true; sha?: string } | { ok: false; reason: "bad" | "exists" | "conflict" | "failed"; message?: string }

/** A new commit that undoes `sha` on the current branch. A clash leaves nothing half-done. */
export async function revert(dir: string, sha: string): Promise<RefOpResult> {
  if (!SHA.test(sha)) return { ok: false, reason: "bad" }
  try {
    await gitAsync(dir, ["revert", "--no-edit", sha], { timeoutMs: 120_000, env: { ...process.env, GIT_EDITOR: "true" } })
    return { ok: true, sha: await gitAsync(dir, ["rev-parse", "HEAD"]) }
  } catch (err) {
    const clash = await gitAsync(dir, ["rev-parse", "-q", "--verify", "REVERT_HEAD"]).then(() => true, () => false)
    if (clash) { await gitAsync(dir, ["revert", "--abort"]).catch(() => {}); return { ok: false, reason: "conflict" } }
    return { ok: false, reason: "failed", message: err instanceof Error ? err.message : String(err) }
  }
}

/** A branch or a tag at `sha`; nothing is checked out, nothing moves. */
export async function createRef(dir: string, kind: "branch" | "tag", name: string, sha: string): Promise<RefOpResult> {
  if (!SHA.test(sha) || !(await validRef(dir, kind, name))) return { ok: false, reason: "bad" }
  const exists = await gitAsync(dir, ["rev-parse", "--verify", "--quiet", kind === "branch" ? `refs/heads/${name}` : `refs/tags/${name}`]).then(() => true, () => false)
  if (exists) return { ok: false, reason: "exists" }
  try {
    await gitAsync(dir, kind === "branch" ? ["branch", "--no-track", name, sha] : ["tag", name, sha])
    return { ok: true }
  } catch (err) {
    return { ok: false, reason: "failed", message: err instanceof Error ? err.message : String(err) }
  }
}
