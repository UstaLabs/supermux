# Diff Base Selector — Design

**Date:** 2026-07-09
**Status:** Approved (brainstorming)
**Area:** Web editor — `DiffView` / `/fs/diff` / `computeWorkdirDiff`

## Problem

The editor's diff view always compares one fixed pair:

- **Left / base** = the commit that was HEAD when the session started (a SHA
  captured at spawn, falling back through session-creation timestamp to the
  empty tree — see `resolveBase` in `src/core/editor/workdir-diff.ts`).
- **Right / target** = the current working tree (tracked changes + untracked
  files).

This "session-start → now" view is the right default, but it is the *only*
view. Users want to adjust what the base is compared against without losing
that default.

## Scope

- **In scope:** make the **base (left side) adjustable** via a selector.
- **Out of scope:** the target (right side) stays the **working tree** in every
  case. No arbitrary commit-to-commit or staged/unstaged selection.

## Reference: how established tools do it

- **GitHub PR "Files changed"** — base is a small dropdown of *meaningful*
  choices (commits with subjects, "everything in this PR"), never a raw SHA
  box. Uses **merge-base semantics** for branch comparison so the mainline's
  own commits don't appear as phantom deletions.
- **GitLab MR** — a "Compare [version] with [version]" pair over meaningful
  pushed revisions.
- **VS Code (Git Tree Compare)** — exposes a **"merge base" vs "direct"** toggle,
  confirming merge-base handling is the subtle correctness detail.

Takeaways adopted: a **labeled preset dropdown** (not a SHA field), and
**merge-base semantics** for the branch option.

## The four base options

1. **Session start** — default; current behavior (`resolveBase`).
2. **Uncommitted (HEAD)** — diff against HEAD; shows only uncommitted changes,
   hiding commits the agent already made this session.
3. **Previous commit…** — pick from a short recent-commit list (subject +
   short SHA); diff directly against that commit.
4. **Another branch…** — pick a branch; compared with **merge-base semantics**
   (`git merge-base <branch> HEAD` → working tree), so the branch's own commits
   don't render as deletions.

## UI

A small dropdown in the `DiffView` header, left of the "Wrap" button:

```
[ Base: Session start ▾ ]   Wrap   ✕
   ├ Session start
   ├ Uncommitted (HEAD)
   ├ Previous commit…   → submenu: recent log (subject · short SHA)
   └ Another branch…    → submenu: branch list
```

- Selecting a base re-fetches the diff (`loadDiff` with the chosen base).
- The choice is **remembered for the life of the session** (reopening the diff
  keeps it) via the `useEditor` composable state — **no disk persistence**. A
  fresh session starts at "Session start".
- The header label reflects the active base (e.g. `Base: main`, `Base: a1b2c3d`).

## Backend

### `GET /sessions/:id/fs/diff?base=<spec>`

`base` spec (default `session-start`):

| spec | resolves to |
|------|-------------|
| `session-start` | today's `resolveBase` logic |
| `head` | `HEAD` |
| `commit:<sha>` | that commit, direct diff |
| `branch:<name>` | `git merge-base <name> HEAD` |

`computeWorkdirDiff(workdir, baseCommits, createdAt, baseSpec?)` gains the
`baseSpec` argument. Per repo, it resolves `baseSpec` into an effective base
commit, then runs the existing `trackedDiff` + `untrackedDiff` against it.
Untracked-file handling is unchanged. Invalid/unresolvable specs fall back to
`session-start` for that repo.

### `GET /sessions/:id/fs/refs`

New lightweight endpoint returning, per repo:

```json
{ "repos": [ { "repo": "", "branches": ["main","dev"],
              "commits": [ { "sha": "a1b2c3d", "subject": "…" } ] } ] }
```

- Branches: `git branch --format=%(refname:short)`.
- Commits: `git log -30 --format=%h%x00%s` (last ~30).

Populates the "Previous commit…" and "Another branch…" submenus.

## Multi-repo handling

A workdir can hold several repos. Resolution rules:

- **Session start** and **Uncommitted (HEAD)** apply cleanly to every repo
  (each resolves its own).
- The selector is **global** (one choice for the whole diff view).
- The **Previous commit…** and **Another branch…** pickers list refs from the
  **primary repo** (first in `computeWorkdirDiff`'s repo order).
- A chosen **branch** is applied to any other repo that also has a branch of
  that name; repos lacking it fall back to **session-start** for that repo.
- A chosen **commit SHA** applies only to the repo it exists in; other repos
  fall back to session-start.

Single-repo sessions — the common case — are unaffected. Per-repo pickers are
explicitly deferred (YAGNI for now).

## Data flow

1. User opens diff → `loadDiff()` calls `/fs/diff` (default base) and, in
   parallel, `/fs/refs` to populate submenus.
2. User picks a base → composable stores `diffBase`, re-calls `/fs/diff?base=…`.
3. Server `computeWorkdirDiff` resolves the spec per repo and returns
   `{ repos, comments }` in the existing shape.
4. `DiffView` renders unchanged; only the header label and the added dropdown
   are new.

## Error handling

- Unknown/invalid `base` spec, or a ref that fails to resolve in a repo →
  that repo silently falls back to `session-start` (never a hard error; the
  diff still renders).
- `/fs/refs` failure → submenus show only the two static options (Session
  start, Uncommitted); the picker items degrade gracefully.
- Existing review-comment re-anchoring is unaffected — comments still anchor to
  working-tree lines regardless of the chosen base.

## Testing

- **Unit (`workdir-diff`):** `baseSpec` resolution per spec type; merge-base
  for `branch:`; invalid-spec fallback; multi-repo branch-name matching and
  commit-SHA isolation.
- **Endpoint:** `/fs/diff?base=` returns the expected file set for each spec;
  `/fs/refs` returns branches + commits for single and multi repo.
- **Component (`DiffView`):** dropdown renders the four options, submenus
  populate from refs, selecting an item re-fetches with the right `base`,
  header label reflects the active base.

## Files touched

- `src/core/editor/workdir-diff.ts` — `baseSpec` param + per-repo resolution.
- `src/channels/web/index.ts` — `?base=` on `/fs/diff`; new `/fs/refs` route.
- `src/web-app/src/api/client.ts` — `fsDiff(id, base?)`, new `fsRefs(id)`.
- `src/web-app/src/composables/useEditor.ts` — `diffBase` state, refs load,
  base-aware `loadDiff`/`reloadDiff`.
- `src/web-app/src/components/editor/DiffView.vue` — base dropdown + submenus +
  header label.
```
