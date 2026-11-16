// #152: the local branch a session on a pull / merge request works on: the PR's OWN head, never a new branch. A
// same-repo PR's head comes from origin; a fork's from its own remote (pr-<owner>), and when its author allows
// maintainer edits, pushes go back to the fork's branch under the PR's name, so they update the PR. A fork that can't
// be fetched is read from origin's own PR ref instead (GitHub refs/pull/N/head, GitLab refs/merge-requests/N/head):
// enough to run and review, with nowhere to push.
import type { OpenIn } from "../git/branches"
import { gitAsync } from "../git/exec"
import type { PrSummary } from "../git/forge"

export type PrBranch =
  | { ok: true; branch: string; pushRemote?: string }
  | { ok: false; code: "held_elsewhere"; branch: string; holder: OpenIn }
  | { ok: false; code: "local_ahead"; branch: string; ahead: number }
  | { ok: false; code: "fetch_failed"; message: string }

const FETCH_MS = 60_000
const quiet = { ...process.env, GIT_TERMINAL_PROMPT: "0" }
const has = (repoRoot: string, ref: string) => gitAsync(repoRoot, ["rev-parse", "--verify", "--quiet", ref]).then(() => true, () => false)

/** A branch name git doesn't have yet: [name], else name-2, name-3… */
async function freeName(repoRoot: string, name: string): Promise<string> {
  for (let i = 1; ; i++) {
    const candidate = i === 1 ? name : `${name}-${i}`
    if (!(await has(repoRoot, `refs/heads/${candidate}`))) return candidate
  }
}

/**
 * Makes the PR's head a local branch that follows it, refusing first when git keeps that branch elsewhere
 * ([holderOf]) or when the local branch has commits the PR doesn't ([policy] absent). [policy] "pr" moves the local
 * branch to `<branch>-backup` first and takes the PR's; "mine" keeps the local branch as it is.
 */
export async function preparePrBranch(
  repoRoot: string,
  pr: PrSummary,
  policy: "pr" | "mine" | undefined,
  holderOf: (branch: string) => OpenIn | null,
): Promise<PrBranch> {
  const local = pr.isFork && pr.headOwner ? `${pr.headOwner}/${pr.head}` : pr.head
  // Where the head comes from: origin, the fork's own remote, or (a fork that can't be fetched) origin's PR ref.
  let remote = "origin"
  let tracking = `refs/remotes/origin/${pr.head}`
  let fetched = false
  if (pr.isFork && pr.headOwner && pr.headUrl) {
    remote = `pr-${pr.headOwner}`
    tracking = `refs/remotes/${remote}/${pr.head}`
    const url = await gitAsync(repoRoot, ["remote", "get-url", remote]).catch(() => "")
    if (!url) await gitAsync(repoRoot, ["remote", "add", remote, pr.headUrl]).catch(() => {})
    fetched = await gitAsync(repoRoot, ["fetch", "--no-tags", remote, `+refs/heads/${pr.head}:${tracking}`], { timeoutMs: FETCH_MS, env: quiet })
      .then(() => true, () => false)
  }
  let followsRemote = fetched || !pr.isFork
  if (pr.isFork && !fetched) {
    remote = ""
    tracking = `refs/remotes/origin/pr/${pr.number}`
    const ref = pr.forge === "gitlab" ? `refs/merge-requests/${pr.number}/head` : `refs/pull/${pr.number}/head`
    try {
      await gitAsync(repoRoot, ["fetch", "--no-tags", "origin", `+${ref}:${tracking}`], { timeoutMs: FETCH_MS, env: quiet })
    } catch (err) {
      return { ok: false, code: "fetch_failed", message: err instanceof Error ? err.message : String(err) }
    }
    followsRemote = false
  } else if (!pr.isFork) {
    try {
      await gitAsync(repoRoot, ["fetch", "--no-tags", "origin", `+refs/heads/${pr.head}:${tracking}`], { timeoutMs: FETCH_MS, env: quiet })
    } catch (err) {
      return { ok: false, code: "fetch_failed", message: err instanceof Error ? err.message : String(err) }
    }
  }

  const holder = holderOf(local)
  if (holder) return { ok: false, code: "held_elsewhere", branch: local, holder }
  const upstream = followsRemote ? `${remote}/${pr.head}` : null
  if (await has(repoRoot, `refs/heads/${local}`)) {
    const ahead = Number(await gitAsync(repoRoot, ["rev-list", "--count", `${tracking}..refs/heads/${local}`]).catch(() => "0")) || 0
    if (ahead > 0 && !policy) return { ok: false, code: "local_ahead", branch: local, ahead }
    if (ahead > 0 && policy === "pr") {
      // Nothing is lost: the local commits keep a branch of their own.
      await gitAsync(repoRoot, ["branch", "-m", local, await freeName(repoRoot, `${local}-backup`)])
      await gitAsync(repoRoot, ["branch", ...(upstream ? ["--track"] : ["--no-track"]), local, upstream ?? tracking])
    } else if (ahead === 0) {
      // At or behind the PR's head: brought up to it.
      await gitAsync(repoRoot, ["branch", "--force", "--no-track", local, tracking])
      if (upstream) await gitAsync(repoRoot, ["branch", `--set-upstream-to=${upstream}`, local])
    }
  } else {
    await gitAsync(repoRoot, ["branch", ...(upstream ? ["--track"] : ["--no-track"]), local, upstream ?? tracking])
  }

  if (!pr.isFork) return { ok: true, branch: local, pushRemote: "origin" }
  if (!fetched || !pr.maintainerCanModify) return { ok: true, branch: local }
  // Pushes go to the fork's branch under the PR's own name (the local one carries the owner's name).
  await gitAsync(repoRoot, ["config", `branch.${local}.pushRemote`, remote])
  const refspec = `refs/heads/${local}:refs/heads/${pr.head}`
  const pushes = await gitAsync(repoRoot, ["config", "--get-all", `remote.${remote}.push`]).catch(() => "")
  if (!pushes.split("\n").includes(refspec)) await gitAsync(repoRoot, ["config", "--add", `remote.${remote}.push`, refspec])
  return { ok: true, branch: local, pushRemote: remote }
}
