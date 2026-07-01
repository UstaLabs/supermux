# Archived List — Filter & Show Projects

**Status:** proposed
**Author:** supermux-4 (with Ahmet)
**Date:** 2026-06-27
**Related:** [2026-05-30 archived sessions page design](./2026-05-30-archived-sessions-page-design.md)

## Summary

Add a per-project lens to the archived sessions list (`ArchivedListView.vue`):
show each archived session's **project** as a clearer label on its row, and add
a compact, searchable **"All projects ▾"** dropdown that filters the list to a
single project. "Project" matches the active session list's notion —
`repo_root ?? workdir` — so worktree-backed sessions resolve to their real repo.
Filtering is client-side; the archived list is small.

## Goals

1. Each archived row shows a recognizable project label (parent folder + name),
   not just the raw workdir path.
2. A dropdown at the top of the list filters archived sessions to one chosen
   project, with an "All projects" default.
3. The dropdown lists only projects that actually have archived sessions, each
   with a count, and is searchable.
4. Identical behavior in both the desktop sidebar (compact) and mobile
   full-page variants.
5. Project derivation matches the active list (`repo_root ?? workdir`).

## Non-goals

- Grouping archived rows under collapsible project headers — we deliberately
  chose a flat list + filter (the active list does the grouping).
- Any change to the active session list.
- Multi-select filtering, or filtering by agent/date.
- Server-side filtering or pagination.
- Persisting the selected filter across reloads — it resets to "All" each visit.

## Data model

### Backend — expose `repo_root` for archived sessions

`repo_root` is already persisted in the `sessions` table and surfaced for active
sessions, but the archived snapshot omits it. Add it (no DB migration, no new
endpoint):

- `src/channels/web/index.ts` — add `repo_root?: string` to
  `ArchivedSessionSnapshot`.
- `src/main.ts` — in the `listArchivedSessions` map, add `repo_root: s.repo_root`.

### Frontend type

- `src/web-app/src/stores/sessions.ts` — add `repo_root?: string` to
  `ArchivedSession`.

## Project derivation & label

A project's **key** (identity/dedupe) and base label come from the existing
`workdirDisplay(repo_root ?? workdir, homeDir)`. Using `repo_root ?? workdir`
means a worktree-backed archived session resolves to its real repo, exactly as
the active list does.

### Row label — "shortened path with parent folder"

New pure helper `projectLabel(workdir, homeDir)` returns the **last two path
segments** (parent + leaf):

| Input (home = `/home/ahmet`) | Label |
|------|------|
| `/home/ahmet/projects/kurbanhane` | `…/projects/kurbanhane` |
| `/home/ahmet/foo` | `~/foo` |
| `/srv/www/acme` | `…/www/acme` |
| `/acme` | `/acme` |

Rules:

- Operate on the normalized key's path segments.
- If ≥2 segments and the parent is **not** the home dir → `parent/leaf`,
  prefixed with `…/` when any ancestor exists above the parent.
- If the parent **is** the home dir → `~/leaf` (avoids showing the home
  username as the "parent").
- ≤1 segment → return the path unchanged.

The truncation glyph (`…/`) is cosmetic; behavior is pinned by unit tests so it
is trivial to adjust.

Rendered as a subtle line/pill with a folder icon on each row, replacing today's
raw `formatWorkdir(s.workdir)` line.

## Filter logic (isolated + unit-tested)

New module `src/web-app/src/lib/archived-projects.ts` — pure functions, tested
with `bun:test` (mirrors `recent-projects.ts` / `project-options.ts`):

```ts
import type { ArchivedSession } from "@/stores/sessions"

export interface ArchivedProject {
  key: string      // workdirDisplay key — dedupe + filter identity
  label: string    // projectLabel — display
  count: number    // archived sessions in this project
}

// Distinct projects across archived sessions, most-recently-archived first.
export function archivedProjects(
  sessions: ArchivedSession[], homeDir?: string | null,
): ArchivedProject[]

// Sessions whose project key === selected key. null/"" key → all sessions.
export function filterByProject(
  sessions: ArchivedSession[], key: string | null, homeDir?: string | null,
): ArchivedSession[]

export function projectLabel(workdir: string, homeDir?: string | null): string
```

- `archivedProjects`: map each session to
  `workdirDisplay(repo_root ?? workdir, homeDir).key`, dedupe, count, and order
  by each project's newest `killed_at`. Each entry carries its `projectLabel`.
- `filterByProject`: predicate on the same key; null/`""` → passthrough.

**Dropdown order:** most-recently-archived project first (matches the recency
feel of the launcher and active list) — decided. Switchable to alphabetical if
preferred at review.

## UI — `ArchivedListView.vue`

State: `const selectedProjectKey = ref<string | null>(null)` (null = All).

Derived:

- `projects = computed(() => archivedProjects(sessions.archivedSessions, sessions.homeDir))`
- `visible  = computed(() => filterByProject(sessions.archivedSessions, selectedProjectKey.value, sessions.homeDir))`

Auto-reset: `watch(projects, …)` — if `selectedProjectKey` is set but no longer
present (e.g. the last session in that project was resumed), reset it to `null`.

### Filter control

A compact dropdown under the header in **both** variants, built from the
existing `DropdownMenu` + `DropdownMenuRadioGroup` / `DropdownMenuRadioItem`
primitives, with a search input at the top (reusing the input + client-side
filter pattern from `ProjectPathPicker.vue`):

- Trigger: `All projects ▾` when null; otherwise the selected project's label.
- Content: a search input that filters the list as you type, then a radio list —
  "All projects" followed by one item per `projects` entry showing its `label`
  and `count`.
- Rendered only when there is ≥1 archived session (otherwise the existing empty
  state shows). When exactly one project exists the dropdown may be hidden (the
  filter would be a no-op) — minor, decide during implementation.

### Rows

Each visible row keeps its name, agent (+ model), and archived date; the workdir
line is replaced by the project-label pill (folder icon + `projectLabel(...)`).
Applied to both the compact and mobile row templates. Tap still opens
`/s/:id` (unchanged).

### Empty states

- 0 archived sessions → existing "No archived sessions." (no dropdown).
- Filter selected, 0 matches → cannot normally occur (only projects that have
  sessions are listed); the auto-reset above covers the resume edge case.

## Testing

`archived-projects.test.ts` (bun:test):

- `projectLabel`: home-nested, direct-in-home, non-home absolute,
  single-segment, and `repo_root`-over-`workdir` precedence.
- `archivedProjects`: dedupe by project key, correct counts, recency order, and
  worktree grouping (worktree `workdir` + `repo_root` collapse to one project);
  empty input → `[]`.
- `filterByProject`: matches by key; `null`/`""` passthrough; unknown key → `[]`.

Manual: dropdown lists projects with counts and is searchable; selecting filters
the rows; "All projects" clears; resuming the last session in the selected
project resets the filter; both sidebar and mobile variants behave the same.

## Implementation order

1. Backend: add `repo_root` to `ArchivedSessionSnapshot` + the
   `listArchivedSessions` map.
2. Frontend type: `repo_root?` on `ArchivedSession`.
3. `lib/archived-projects.ts` + tests.
4. `ArchivedListView.vue`: project-label pill on rows (both variants).
5. `ArchivedListView.vue`: searchable filter dropdown (both variants) + state +
   auto-reset.
