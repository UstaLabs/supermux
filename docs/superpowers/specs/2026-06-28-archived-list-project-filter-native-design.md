# Archived List — Filter & Show Projects (iOS + Android)

**Status:** proposed
**Author:** supermux-4 (with Ahmet)
**Date:** 2026-06-28
**Related:** [2026-06-27 web archived-list project filter](./2026-06-27-archived-list-project-filter-design.md)

## Summary

Bring the web "archived list — filter & show projects" feature to the native
**iOS (SwiftUI)** and **Android (Compose)** apps: show each archived session's
**project** as a per-row label, and add a **filter by project** control (pick
one project, or "All projects"). A "project" is a session's `repo_root ?? workdir`
— the same notion the active session list already groups by on both platforms.

The project-derivation logic goes in the **shared KMP module** (one tested
helper used by both apps); each platform adds thin, native-idiomatic UI.

## Goals

1. Each archived row shows a recognizable project label (shortened path with
   parent folder), on both iOS and Android.
2. A native filter control narrows the archived list to one project, with an
   "All projects" default, on both platforms.
3. Project identity matches the active list (`repo_root ?? workdir`).
4. Derivation/label/filter logic is shared (KMP) and unit-tested; UI is per-platform.

## Non-goals

- Grouping archived rows under collapsible project headers (flat list + filter,
  matching the web decision).
- Any change to the active session list on either platform.
- Pixel-identical labels across web and native (see Label note).
- Search-as-you-type inside the filter (web has it; native uses a simple menu —
  archived project counts are small). Can be added later if wanted.
- Persisting the selected filter across screen revisits (resets to "All").

## Background (current state)

Both apps fetch archived sessions from the broker (`GET /archived-sessions`) via
the shared `BrokerApi.archived()` → `ArchivedDto`. The broker **already sends**
`repo_root` for archived sessions (added in the web work); the shared
`ArchivedDto` just doesn't model it yet.

- **Shared:** `dev.supermux.session.SessionGrouping` already provides
  `formatWorkdir(workdir, home)` (→ `~/…` under home, else `.../parent/leaf`),
  `inferHomeDir(workdir)`, and `groupSessions(...)` (active list groups by
  `repo_root ?: workdir`). Both apps already use `formatWorkdir`.
- **iOS:** `ArchivedView` (`apps/iosApp/Supermux/Sessions/InfoPages.swift`) — flat
  list; row already renders `formatWorkdir(a.workdir, …)`; no filter; no toolbar.
- **Android:** `ArchivedScreen` (`apps/android/.../settings/MoreScreens.kt`) — flat
  list; `ArchivedRow` renders the **raw** `session.workdir`; no filter; the
  `TopAppBar` `actions` slot is empty.

## Shared (KMP) changes

### 1. `ArchivedDto` gains `repo_root`

`apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` — add a
nullable field (defaults keep it backward compatible; `ignoreUnknownKeys` already
tolerates it server→client):

```kotlin
data class ArchivedDto(
    val id: String,
    val name: String,
    val workdir: String = "",
    val agent: String = "claude",
    val killed_at: String? = null,
    val repo_root: String? = null,   // NEW
)
```

### 2. `archivedProjects` helper (new, tested)

New file `apps/shared/src/commonMain/kotlin/dev/supermux/session/ArchivedProjects.kt`,
package `dev.supermux.session`, reusing `formatWorkdir`:

```kotlin
data class ArchivedProject(val key: String, val label: String, val count: Int)

/** Distinct projects across archived sessions, most-recently-archived first. */
fun archivedProjects(sessions: List<ArchivedDto>, home: String?): List<ArchivedProject>

/** Sessions in the given project (key = repo_root ?: workdir). null key → all. */
fun filterArchivedByProject(sessions: List<ArchivedDto>, key: String?): List<ArchivedDto>
```

- Key = `repo_root ?: workdir` (identical to `groupSessions`).
- Label = `formatWorkdir(key, home)`.
- Count per key; order by each project's newest `killed_at` (ISO string compare),
  tie-break by label.
- `filterArchivedByProject(.., null)` returns all; otherwise matches by the same key.

Unit-tested in `apps/shared/src/commonTest/.../session/ArchivedProjectsTest.kt`
(`kotlin.test`): dedupe+count, worktree→`repo_root` grouping, recency order,
empty input, label forms (`~/projects/foo`, `.../parent/leaf`), filter match +
null passthrough.

## iOS (SwiftUI) changes

File: `apps/iosApp/Supermux/Sessions/InfoPages.swift` (`ArchivedView`).

- **Per-row label:** change the row's workdir line to
  `formatWorkdir(a.repo_root ?? a.workdir, home)` (was `a.workdir`).
- **Filter state:** `@State private var projectFilter: String? = nil`.
- **Projects:** computed from the loaded `items` via the shared
  `archivedProjects(items, home)`.
- **Filtered rows:** iterate `filterArchivedByProject(items, projectFilter)`.
- **Filter control:** a `ToolbarItem(placement: .topBarTrailing)` `Menu` — "All
  projects" + one button per project (label + count); the toolbar icon uses
  `line.3.horizontal.decrease.circle` (empty) / `.fill` (filter active), the iOS
  convention. Matches the toolbar pattern used by `DevicesView`/`UsageView`.
- `home`: use the same source the iOS active list passes to `groupSessions`
  (fallback: `nil`, which `formatWorkdir` infers per path).

## Android (Compose) changes

File: `apps/android/.../settings/MoreScreens.kt` (`ArchivedScreen` + `ArchivedRow`),
plus the call site in `MainActivity.kt`.

- **Thread `home`:** add `home: String` to `ArchivedScreen`/`ArchivedRow`; wire
  `home = DevConfig.HOME` at the `composable<Archived>` call site.
- **Per-row label:** `ArchivedRow` renders `formatWorkdir(session.repo_root ?: session.workdir, home)`
  instead of the raw `session.workdir`.
- **Filter state:** `var selectedProject by remember { mutableStateOf<String?>(null) }`.
- **Projects:** `remember(sessions) { archivedProjects(sessions, home) }`.
- **Filtered rows:** `filterArchivedByProject(sessions, selectedProject)` feeding the `LazyColumn`.
- **Filter control:** an `IconButton` (filter icon) in the `TopAppBar` `actions`
  slot opening a `DropdownMenu` — "All projects" + one `DropdownMenuItem` per
  project (label + count). Matches the `DropdownMenu`-in-top-bar pattern already
  used by `SessionListScreen`/`ChatScreen`.

## Label note (deliberate)

Native reuses the existing `formatWorkdir`, so labels read `~/projects/foo` (under
home) or `.../parent/leaf` (elsewhere) — consistent with each app's active-list
group headers. This is the same "shortened path with parent folder" intent the
web feature delivers; the exact home-path rendering differs from web's `…/parent/leaf`,
but per-platform internal consistency is preferred over cross-platform pixel-matching,
and it reuses shared code (DRY).

## Error/edge states

- Empty archived list → existing empty state; no filter control shown (or shown
  disabled — per-platform, minor).
- Filter active then that project's last session resumed/removed: the project
  list recomputes from `items`; if `projectFilter`/`selectedProject` no longer
  appears, reset it to `null` (mirrors web auto-reset).
- A project whose label collides with another but has a different key → two
  entries, distinguished by count (keys are identity; labels are cosmetic).

## Testing & verification

- **Shared:** `kotlin.test` unit tests for `archivedProjects` /
  `filterArchivedByProject`, run via Gradle (`commonTest`). This is the real,
  runnable verification of the core logic.
- **Android:** compile-check (`:android` Kotlin compile / assembleDebug) **if the
  Android SDK is available on the host**; otherwise static review.
- **iOS:** static review only — SwiftUI cannot be built on this Linux host.

Verification limits will be stated explicitly in the final report; no success will
be claimed for a build that did not actually run.

## Implementation order

1. Shared: `ArchivedDto.repo_root` + `ArchivedProjects.kt` + tests (run them).
2. Android: thread `home`, per-row label, filter dropdown.
3. iOS: per-row label, filter menu.
