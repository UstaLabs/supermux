# Archived List — Filter & Show Projects (iOS + Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the web "archived list — filter & show projects" feature to the native iOS (SwiftUI) and Android (Compose) apps.

**Architecture:** Add `repo_root` to the shared KMP `ArchivedDto`, put project derivation/label/filter in one shared, unit-tested helper (`ArchivedProjects.kt`, reusing the existing `formatWorkdir`), then add thin native UI: an iOS toolbar `Menu` and an Android top-bar `DropdownMenu`, each with a per-row project label.

**Tech Stack:** Kotlin Multiplatform (`dev.supermux.session`/`dev.supermux.net`), `kotlin.test`; SwiftUI (SKIE-bridged shared funcs); Jetpack Compose Material 3.

**Spec:** `docs/superpowers/specs/2026-06-28-archived-list-project-filter-native-design.md`

**Build/verify reality (this Linux host):** Java 17 + Gradle wrapper + Android SDK at `~/Android/Sdk` are present (export `ANDROID_HOME=$HOME/Android/Sdk`). No `kotlinc`, no iOS toolchain. So: shared logic is verified by a real Gradle `:shared:jvmTest`; Android UI by a Kotlin compile-check (`:android:compileDebugKotlin`) if Gradle cooperates; iOS UI by static review only. First Gradle run downloads everything and is slow.

---

## File Structure

- **Modify** `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` — `ArchivedDto.repo_root`.
- **Create** `apps/shared/src/commonMain/kotlin/dev/supermux/session/ArchivedProjects.kt` — `ArchivedProject`, `archivedProjects`, `filterArchivedByProject`.
- **Create** `apps/shared/src/commonTest/kotlin/dev/supermux/session/ArchivedProjectsTest.kt` — `kotlin.test` unit tests.
- **Modify** `apps/android/src/main/kotlin/dev/supermux/android/settings/MoreScreens.kt` — `ArchivedScreen` filter + `ArchivedRow` label.
- **Modify** `apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt` — pass `home` to `ArchivedScreen`.
- **Modify** `apps/iosApp/Supermux/Sessions/InfoPages.swift` — `ArchivedView` filter + row label.

---

## Task 1: Shared KMP — `repo_root` + `archivedProjects` helper (TDD)

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt:77-83`
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/session/ArchivedProjects.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/session/ArchivedProjectsTest.kt`

- [ ] **Step 1: Add `repo_root` to `ArchivedDto`**

In `BrokerApi.kt`, the data class currently is:

```kotlin
data class ArchivedDto(
    val id: String,
    val name: String,
    val workdir: String = "",
    val agent: String = "claude",
    val killed_at: String? = null,
)
```

Change it to:

```kotlin
data class ArchivedDto(
    val id: String,
    val name: String,
    val workdir: String = "",
    val agent: String = "claude",
    val killed_at: String? = null,
    val repo_root: String? = null,
)
```

- [ ] **Step 2: Write the failing test**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/session/ArchivedProjectsTest.kt`:

```kotlin
package dev.supermux.session

import dev.supermux.net.ArchivedDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchivedProjectsTest {
    private val HOME = "/home/ahmet"

    private fun a(workdir: String, repo: String? = null, killed: String? = null) =
        ArchivedDto(id = "x", name = "n", workdir = workdir, agent = "claude", killed_at = killed, repo_root = repo)

    @Test
    fun dedupes_and_counts() {
        assertEquals(
            listOf(ArchivedProject("/home/ahmet/projects/foo", "~/projects/foo", 2)),
            archivedProjects(
                listOf(
                    a("/home/ahmet/projects/foo", killed = "2026-06-01T00:00:00Z"),
                    a("/home/ahmet/projects/foo", killed = "2026-06-02T00:00:00Z"),
                ),
                HOME,
            ),
        )
    }

    @Test
    fun groups_worktree_under_repo_root() {
        assertEquals(
            listOf(ArchivedProject("/home/ahmet/projects/foo", "~/projects/foo", 2)),
            archivedProjects(
                listOf(
                    a("/home/ahmet/.mux/worktrees/x/abc", repo = "/home/ahmet/projects/foo", killed = "2026-06-01T00:00:00Z"),
                    a("/home/ahmet/projects/foo", killed = "2026-06-02T00:00:00Z"),
                ),
                HOME,
            ),
        )
    }

    @Test
    fun orders_most_recent_first() {
        assertEquals(
            listOf("~/projects/new", "~/projects/old"),
            archivedProjects(
                listOf(
                    a("/home/ahmet/projects/old", killed = "2026-06-01T00:00:00Z"),
                    a("/home/ahmet/projects/new", killed = "2026-06-10T00:00:00Z"),
                ),
                HOME,
            ).map { it.label },
        )
    }

    @Test
    fun empty_input_yields_empty() {
        assertEquals(emptyList<ArchivedProject>(), archivedProjects(emptyList(), HOME))
    }

    @Test
    fun label_shortens_non_home_path() {
        assertEquals(
            listOf(".../www/acme"),
            archivedProjects(listOf(a("/srv/www/acme", killed = "2026-06-01T00:00:00Z")), HOME).map { it.label },
        )
    }

    @Test
    fun filter_matches_by_key() {
        val sessions = listOf(a("/home/ahmet/projects/foo"), a("/home/ahmet/projects/bar"))
        assertEquals(
            listOf(a("/home/ahmet/projects/foo")),
            filterArchivedByProject(sessions, "/home/ahmet/projects/foo"),
        )
    }

    @Test
    fun filter_matches_worktree_by_repo_root() {
        val wt = a("/home/ahmet/.mux/worktrees/x/abc", repo = "/home/ahmet/projects/foo")
        assertEquals(listOf(wt), filterArchivedByProject(listOf(wt), "/home/ahmet/projects/foo"))
    }

    @Test
    fun filter_null_returns_all() {
        val sessions = listOf(a("/home/ahmet/projects/foo"), a("/home/ahmet/projects/bar"))
        assertEquals(sessions, filterArchivedByProject(sessions, null))
    }
}
```

- [ ] **Step 3: Run the test, confirm it FAILS**

```bash
cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :shared:jvmTest --tests "dev.supermux.session.ArchivedProjectsTest"
```
Expected: FAILS to compile — `archivedProjects` / `filterArchivedByProject` / `ArchivedProject` unresolved (not created yet). (First run downloads Gradle + dependencies and is slow; that's expected.)

- [ ] **Step 4: Implement the helper**

Create `apps/shared/src/commonMain/kotlin/dev/supermux/session/ArchivedProjects.kt`:

```kotlin
package dev.supermux.session

import dev.supermux.net.ArchivedDto

/** A distinct project across archived sessions. [key] is repo_root ?: workdir. */
data class ArchivedProject(val key: String, val label: String, val count: Int)

/** A session's project key: its repo (for worktrees) else its workdir — matches groupSessions. */
private fun projectKey(s: ArchivedDto): String = s.repo_root ?: s.workdir

/**
 * Distinct projects across archived sessions, most-recently-archived first.
 * Label uses [formatWorkdir]; ties broken alphabetically by label.
 */
fun archivedProjects(sessions: List<ArchivedDto>, home: String?): List<ArchivedProject> {
    data class Acc(val key: String, val label: String, var count: Int, var latest: String)
    val byKey = LinkedHashMap<String, Acc>()
    for (s in sessions) {
        val key = projectKey(s)
        val killed = s.killed_at ?: ""
        val acc = byKey[key]
        if (acc != null) {
            acc.count += 1
            if (killed > acc.latest) acc.latest = killed
        } else {
            byKey[key] = Acc(key, formatWorkdir(key, home), 1, killed)
        }
    }
    return byKey.values
        .sortedWith(compareByDescending<Acc> { it.latest }.thenBy { it.label })
        .map { ArchivedProject(it.key, it.label, it.count) }
}

/** Sessions in the given project (by key). A null key returns all sessions. */
fun filterArchivedByProject(sessions: List<ArchivedDto>, key: String?): List<ArchivedDto> =
    if (key == null) sessions else sessions.filter { projectKey(it) == key }
```

(`formatWorkdir` is in this same package — no import needed.)

- [ ] **Step 5: Run the test, confirm it PASSES**

```bash
cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :shared:jvmTest --tests "dev.supermux.session.ArchivedProjectsTest"
```
Expected: BUILD SUCCESSFUL, 8 tests pass. If a test fails, fix the implementation (NOT the test expectations — they are the spec).

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt \
        apps/shared/src/commonMain/kotlin/dev/supermux/session/ArchivedProjects.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/session/ArchivedProjectsTest.kt
git commit -m "feat(shared): archived project derivation + repo_root on ArchivedDto

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Android (Compose) — per-row label + filter dropdown

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/settings/MoreScreens.kt` (`ArchivedScreen` ~1234, `ArchivedRow` ~1319)
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt:378-385`

This is UI wiring (no unit test — consistent with the codebase, which has no Compose UI tests). Verify by compile-check + static review.

- [ ] **Step 1: Add imports to `MoreScreens.kt`**

Ensure these imports are present (add any that are missing; many Material 3 ones already are):

```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import dev.supermux.android.DevConfig
import dev.supermux.session.archivedProjects
import dev.supermux.session.filterArchivedByProject
import dev.supermux.session.formatWorkdir
```

- [ ] **Step 2: Thread `home` + filter state into `ArchivedScreen`**

Change the signature (add `home`):

```kotlin
fun ArchivedScreen(
    onBack: () -> Unit,
    onLoad: suspend () -> List<ArchivedDto>,
    onResume: (String) -> Unit,
    home: String,
    loadLogs: suspend (String) -> List<LogEntry> = { emptyList() },
) {
```

Immediately after the existing `var openedId by remember { mutableStateOf<String?>(null) }` line, add:

```kotlin
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val projects = remember(sessions) { archivedProjects(sessions, home) }
    // Clear the filter if the selected project no longer has any archived sessions.
    LaunchedEffect(projects) {
        if (selectedProject != null && projects.none { it.key == selectedProject }) {
            selectedProject = null
        }
    }
```

- [ ] **Step 3: Add the filter action to the `TopAppBar`**

The `TopAppBar(...)` currently has `title`, `navigationIcon`, and `colors`. Add an `actions` parameter (place it after `navigationIcon`, before `colors`):

```kotlin
                actions = {
                    if (sessions.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { filterOpen = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter by project",
                                    tint = if (selectedProject != null) cs.primary else cs.onSurface,
                                )
                            }
                            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("All projects") },
                                    onClick = { selectedProject = null; filterOpen = false },
                                )
                                projects.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text("${p.label}  (${p.count})") },
                                        onClick = { selectedProject = p.key; filterOpen = false },
                                    )
                                }
                            }
                        }
                    }
                },
```

- [ ] **Step 4: Filter the list + pass `home` to rows**

In the `Box { ... }` body, the `when` block's `else ->` branch currently iterates `sessions`. Replace that `else ->` branch with one that iterates the filtered list and passes `home`:

```kotlin
                else -> {
                    val visible = filterArchivedByProject(sessions, selectedProject)
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        items(visible, key = { it.id }) { session ->
                            ArchivedRow(
                                session = session,
                                home = home,
                                resumed = session.id in resumedIds,
                                onOpen = { openedId = session.id },
                                onResume = {
                                    onResume(session.id)
                                    resumedIds = resumedIds + session.id
                                },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
```

(Leave the `loading ->` and `sessions.isEmpty() ->` branches unchanged — the empty check stays on the total `sessions`.)

- [ ] **Step 5: Show the formatted project label in `ArchivedRow`**

Change `ArchivedRow`'s signature to accept `home` and replace the raw-workdir `Text`. The function currently starts:

```kotlin
private fun ArchivedRow(session: ArchivedDto, resumed: Boolean, onOpen: () -> Unit, onResume: () -> Unit) {
```

Change to:

```kotlin
private fun ArchivedRow(session: ArchivedDto, home: String, resumed: Boolean, onOpen: () -> Unit, onResume: () -> Unit) {
```

And change the workdir `Text` from:

```kotlin
            Text(
                session.workdir,
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
```

to:

```kotlin
            Text(
                formatWorkdir(session.repo_root ?: session.workdir, home),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
```

- [ ] **Step 6: Pass `home` at the nav call site**

In `MainActivity.kt`, the `composable<Archived>` block currently is:

```kotlin
                    composable<Archived> {
                        ArchivedScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.archived() },
                            onResume = { vm.resume(it) },
                            loadLogs = { vm.archivedLogs(it) },
                        )
                    }
```

Change it to add `home = DevConfig.HOME`:

```kotlin
                    composable<Archived> {
                        ArchivedScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.archived() },
                            onResume = { vm.resume(it) },
                            home = DevConfig.HOME,
                            loadLogs = { vm.archivedLogs(it) },
                        )
                    }
```

(`DevConfig` is in the same `dev.supermux.android` package as `MainActivity`, so no import is needed there.)

- [ ] **Step 7: Compile-check (best-effort) + static review**

```bash
cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. If the Android build cannot run in this environment (SDK/AGP/network), record that and fall back to a careful static review: confirm every referenced symbol is imported, the `TopAppBar` `actions` slot is well-formed, and `ArchivedRow`/`ArchivedScreen` call sites all pass `home`.

- [ ] **Step 8: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/settings/MoreScreens.kt \
        apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt
git commit -m "feat(android): filter archived list by project + show project per row

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: iOS (SwiftUI) — per-row label + filter menu

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/InfoPages.swift` (`ArchivedView` ~572-611)

UI work; cannot build SwiftUI on this host. Verify by careful static review (especially SKIE-bridged names: `archivedProjects(sessions:home:)`, `filterArchivedByProject(sessions:key:)`, `ArchivedProject.key/.label/.count`). The shared module is already imported (`import Shared`).

- [ ] **Step 1: Add filter state + computed lists to `ArchivedView`**

`ArchivedView` currently is:

```swift
struct ArchivedView: View {
    let broker: BrokerSession
    @State private var items: [ArchivedDto] = []
    @State private var loading = true
    var body: some View {
```

Change to add a filter state and two computed properties:

```swift
struct ArchivedView: View {
    let broker: BrokerSession
    @State private var items: [ArchivedDto] = []
    @State private var loading = true
    @State private var projectFilter: String? = nil

    private var projects: [ArchivedProject] { archivedProjects(sessions: items, home: nil) }
    private var visible: [ArchivedDto] { filterArchivedByProject(sessions: items, key: projectFilter) }

    var body: some View {
```

- [ ] **Step 2: Iterate the filtered list + show the project label**

In the `List`, the `ForEach` currently is `ForEach(items, id: \.id) { a in`. Change it to `ForEach(visible, id: \.id) { a in`.

Then change the workdir line from:

```swift
                                Text(formatWorkdir(workdir: a.workdir, home: inferHomeDir(workdir: a.workdir)))
                                    .font(.caption2.monospaced()).foregroundStyle(.secondary).lineLimit(1)
```

to (use `repo_root ?? workdir`):

```swift
                                Text(formatWorkdir(workdir: a.repo_root ?? a.workdir, home: inferHomeDir(workdir: a.repo_root ?? a.workdir)))
                                    .font(.caption2.monospaced()).foregroundStyle(.secondary).lineLimit(1)
```

- [ ] **Step 3: Reset the filter when a resumed session empties its project**

The swipe action currently is:

```swift
                        .swipeActions {
                            Button("Resume") {
                                broker.resume(a.id); items.removeAll { $0.id == a.id }
                            }.tint(Theme.teal)
                        }
```

Change the button body to clear a now-empty filter:

```swift
                        .swipeActions {
                            Button("Resume") {
                                broker.resume(a.id); items.removeAll { $0.id == a.id }
                                if let f = projectFilter, !items.contains(where: { ($0.repo_root ?? $0.workdir) == f }) {
                                    projectFilter = nil
                                }
                            }.tint(Theme.teal)
                        }
```

- [ ] **Step 4: Add the toolbar filter menu**

The view currently ends with these two modifiers:

```swift
        .navigationTitle("Archived").navigationBarTitleDisplayMode(.inline)
        .task { items = await broker.archived(); loading = false }
```

Insert a `.toolbar { … }` between them:

```swift
        .navigationTitle("Archived").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if !items.isEmpty {
                    Menu {
                        Button("All projects") { projectFilter = nil }
                        ForEach(projects, id: \.key) { p in
                            Button("\(p.label) (\(p.count))") { projectFilter = p.key }
                        }
                    } label: {
                        Image(systemName: projectFilter == nil
                            ? "line.3.horizontal.decrease.circle"
                            : "line.3.horizontal.decrease.circle.fill")
                    }
                }
            }
        }
        .task { items = await broker.archived(); loading = false }
```

- [ ] **Step 5: Static review**

Confirm: `ArchivedProject` members are `.key` (String), `.label` (String), `.count` (Int32 — interpolates fine); `archivedProjects`/`filterArchivedByProject` argument labels match the Kotlin names; `items.isEmpty`/`visible` used correctly; the empty/loading branches still key off `items`.

- [ ] **Step 6: Commit**

```bash
git add apps/iosApp/Supermux/Sessions/InfoPages.swift
git commit -m "feat(ios): filter archived list by project + show project per row

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Verification + final review

**Files:** none (verification only)

- [ ] **Step 1: Shared tests (authoritative)**

```bash
cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :shared:jvmTest --tests "dev.supermux.session.ArchivedProjectsTest"
```
Expected: 8 tests pass.

- [ ] **Step 2: Android compile-check (best-effort)**

```bash
cd apps && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :android:compileDebugKotlin
```
Record success, or record that the Android toolchain could not run here.

- [ ] **Step 3: iOS — static review note**

State explicitly that iOS was reviewed statically and not built (no iOS toolchain on this Linux host).

- [ ] **Step 4: Final cross-platform review**

Dispatch a reviewer over the whole native diff: shared logic correctness + parity with the web feature; Android Compose correctness; iOS SwiftUI correctness; SKIE-bridging assumptions.

---

## Self-Review Notes

- **Spec coverage:** repo_root on DTO (T1), shared tested helper (T1), Android label+filter (T2), iOS label+filter (T3), auto-reset (T2 LaunchedEffect, T3 swipe), label reuse of `formatWorkdir` (T1/T2/T3), verification realism (T4). All spec sections mapped.
- **Types:** `ArchivedProject(key,label,count)`, `archivedProjects(sessions,home)`, `filterArchivedByProject(sessions,key)` defined in T1 and used identically in T2 (Kotlin) and T3 (Swift via SKIE). `repo_root` added in T1 consumed in T2/T3.
- **No placeholders:** every code/command step is complete.
