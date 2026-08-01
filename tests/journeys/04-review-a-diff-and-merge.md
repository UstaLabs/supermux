# 04 — Review a diff and merge

**Gate:** merge · **Implemented:** not yet

"Review before anything lands" is the safety promise: every worker gets an
isolated git worktree and nothing reaches your branch without you. This journey
is the one whose silent failure is genuinely dangerous — a merge that reports
success while the base branch is untouched, or one that lands work you rejected.

## Preconditions
- A session on a real git repository with a clean working tree and a known HEAD.
- The agent has made a committed change on the session's own branch.

## Steps
1. Open the session's diff review.
2. Read the changed file and its hunks.
3. Choose merge.

## Outcomes
- The diff shows the file the agent actually changed, with the actual content.
- After merging, **git itself agrees**: the base branch contains the commit, and
  the worktree is gone or clean.
- The session reflects the merged state.
- The reverse case holds too: discarding leaves the base branch at its original
  HEAD, with no stray commits and no orphaned worktree.

## Known traps
- Assert against **git**, not the UI's success message. The UI reporting success
  is exactly what a broken merge also does.
- Cover a conflicting merge separately: it must surface the conflict rather than
  silently taking one side.
