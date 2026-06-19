// src/core/worktree/readiness.ts
import { aheadBehind, diffStats, dirtyFiles, mergeTreePreflight, gitOk } from "../git/integrate"
import { remoteStatus } from "../git/remote"
import { ghAvailable } from "../git/pr"

export interface FinishReadiness {
  base: string; branch: string
  ahead: number; behind: number
  dirtyFiles: string[]
  filesChanged: number; insertions: number; deletions: number
  hasRemote: boolean; baseHasUpstream: boolean; ghAvailable: boolean
  conflictPreflight: "clean" | "will_conflict" | "unknown"
  recommended: "merge" | "pr"
  nothingToLand: boolean
}

export interface ReadinessInput {
  repoRoot: string; worktreeDir: string; sessionBranch: string; baseBranch: string
  defaultAction?: "auto" | "merge" | "pr"
}

export function computeReadiness(s: ReadinessInput): FinishReadiness {
  const ab = aheadBehind(s.worktreeDir, s.baseBranch)
  const nothingToLand = ab.ahead === 0
  const stats = diffStats(s.repoRoot, s.baseBranch, s.sessionBranch)
  const rs = remoteStatus(s.worktreeDir)
  const baseHasUpstream = rs.hasRemote && gitOk(s.repoRoot, ["rev-parse", "--verify", `refs/remotes/origin/${s.baseBranch}`])
  const gh = rs.hasRemote ? ghAvailable(s.worktreeDir) : false
  const conflictPreflight = nothingToLand ? "clean" : mergeTreePreflight(s.repoRoot, s.baseBranch, s.sessionBranch)
  let recommended: "merge" | "pr"
  if (s.defaultAction === "merge" || s.defaultAction === "pr") recommended = s.defaultAction
  else recommended = rs.hasRemote && baseHasUpstream ? "pr" : "merge"
  return {
    base: s.baseBranch, branch: s.sessionBranch, ahead: ab.ahead, behind: ab.behind,
    dirtyFiles: dirtyFiles(s.worktreeDir), filesChanged: stats.filesChanged,
    insertions: stats.insertions, deletions: stats.deletions,
    hasRemote: rs.hasRemote, baseHasUpstream, ghAvailable: gh,
    conflictPreflight, recommended, nothingToLand,
  }
}
