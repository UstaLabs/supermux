# Workspaces Phase 0 — Shell Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename every client-side symbol that currently uses the word "Workspace" for the *window layout shell*, so that the word "Workspace" becomes free for the new server-side entity introduced in the workspaces-and-views spec.

**Architecture:** This is a pure refactor. Zero behaviour changes, zero new tests, zero protocol changes. Four independent codebases each get a directory rename plus a symbol rename, verified by the existing test suites. Each codebase is its own task and its own commit, so a failure in one does not block the others.

**Tech Stack:** Kotlin/Compose (`apps/desktop`, `apps/android`), Swift/SwiftUI (`apps/iosApp`), Vue 3 + TypeScript (`src/web-app`). Gradle for the Kotlin modules, XcodeGen + Xcode for Swift, Vite + `vue-tsc` for the web app.

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md`, section 12.

---

## Why this comes first

The spec introduces `Workspace` as a **server-side entity**: a container that holds views, has a work directory, and is stored in a new `workspaces` table. The clients already use `Workspace` to mean **the window layout shell** — the thing that draws `Sessions │ Chat │ (Editor/Terminal) │ Display`.

If Phase 1 lands before this rename, `apps/desktop` will hold both a `WorkspaceLayout` class (the local pane splits) and a `Workspace` DTO (the server entity) at the same time. Every later task then has to disambiguate. Do this first, land it fast, and the ambiguity never exists.

⚠ This is a large mechanical diff across four codebases. `dev` moves fast in this repo. **Land each task the same day you start it.** Do not let this sit on a branch for a week — it will conflict with everything.

### Execution order under the desktop-first decision (2026-08-06)

The user decided the workspaces work goes **desktop-first**: only `apps/desktop` and `apps/shared` change on the client side until the desktop client works. Web, iOS, macOS, and Android get their plans after that.

That splits this plan:

| Task | When |
|---|---|
| **Task 1 — desktop** | **Now.** Phase 3 of this series assumes the package is `dev.supermux.desktop.shell`. |
| Task 6 — verification | **Now**, limited to the desktop parts of Step 1. |
| Task 2 — android | Defer to the Android client plan. |
| Task 3 — iOS and macOS | Defer to the iOS/macOS client plan. |
| Task 4 — web | Defer to the web client plan. |
| Task 5 — the dead view report | Defer with Task 4 (it is a web file). |

Deferring is safe: each codebase has its own package namespace, so a renamed desktop and an unrenamed Android cannot collide. The only cross-file link is Task 2 Step 7, which repoints a desktop header comment at the Android file's new path — skip that step while Task 2 is deferred, and do it when Task 2 runs.

⚠ Task 6 Step 1's repository-wide grep will find hits in `apps/android`, `apps/iosApp`, and `src/web-app` until their tasks run. Restrict it to `apps/desktop/src` for now.

---

## File structure

Nothing is created and nothing is deleted. Directories and files move; symbols get new names.

### `apps/desktop` — package `dev.supermux.desktop.workspace` → `dev.supermux.desktop.shell`

| Today | New |
|---|---|
| `.../workspace/WorkspaceRoot.kt` | `.../shell/AppShell.kt` |
| `.../workspace/WorkspaceLayout.kt` | `.../shell/ShellLayout.kt` |
| `.../workspace/WorkspaceStateStore.kt` | `.../shell/ShellStateStore.kt` |
| `.../workspace/WorkspaceShortcuts.kt` | `.../shell/ShellShortcuts.kt` |
| `.../workspace/AgentViewToggle.kt` | `.../shell/AgentViewToggle.kt` (package line only) |
| `.../workspace/PaneToggleCluster.kt` | `.../shell/PaneToggleCluster.kt` (package line only) |
| `.../workspace/ResizableSplit.kt` | `.../shell/ResizableSplit.kt` (package line only) |
| `.../workspace/SessionDetail.kt` | `.../shell/SessionDetail.kt` (package line only) |
| `.../workspace/SessionHeaderMenus.kt` | `.../shell/SessionHeaderMenus.kt` (package line only) |
| `.../workspace/SessionsRail.kt` | `.../shell/SessionsRail.kt` (package line only) |
| `.../workspace/SidebarDivider.kt` | `.../shell/SidebarDivider.kt` (package line only) |

Test files move the same way: `src/test/kotlin/dev/supermux/desktop/workspace/` → `.../desktop/shell/`, and `WorkspaceRootTest.kt` → `AppShellTest.kt`, `WorkspaceLayoutTest.kt` → `ShellLayoutTest.kt`, `WorkspaceStateStoreTest.kt` → `ShellStateStoreTest.kt`, `WorkspaceShortcutsTest.kt` → `ShellShortcutsTest.kt`, `WorkspaceUiStateTest.kt` → `ShellUiStateTest.kt`. `SessionDetailTest.kt` and `SessionHeaderMenusTest.kt` keep their names.

### `apps/android` — package `dev.supermux.android.workspace` → `dev.supermux.android.shell`

| Today | New |
|---|---|
| `.../workspace/WorkspaceLayout.kt` | `.../shell/ShellLayout.kt` |
| `.../workspace/SessionWorkspaceDetail.kt` | `.../shell/SessionShellDetail.kt` |
| `.../workspace/WorkspaceShortcuts.kt` | `.../shell/ShellShortcuts.kt` |
| `.../workspace/AgentViewToggle.kt`, `Breakpoint.kt`, `PaneToggleCluster.kt`, `ResizableSplit.kt`, `SessionsRail.kt`, `SidebarDivider.kt` | same names under `.../shell/` (package line only) |

Tests: `src/test/kotlin/dev/supermux/android/workspace/` → `.../android/shell/`, with `WorkspaceLayoutTest.kt` → `ShellLayoutTest.kt` and `WorkspaceShortcutsTest.kt` → `ShellShortcutsTest.kt`. `BreakpointTest.kt` keeps its name.

### `apps/iosApp` — directory `Supermux/Shell/` already has the right name

| Today | New |
|---|---|
| `Supermux/Shell/IPadWorkspace.swift` | `Supermux/Shell/PadShell.swift` |
| `Supermux/Shell/WorkspaceLayoutModel.swift` | `Supermux/Shell/ShellLayoutModel.swift` |
| `Supermux/Shell/WorkspaceShortcuts.swift` | `Supermux/Shell/ShellShortcuts.swift` |
| `SupermuxTests/WorkspaceLayoutModelTests.swift` | `SupermuxTests/ShellLayoutModelTests.swift` |
| `SupermuxTests/WorkspaceCommandTests.swift` | `SupermuxTests/ShellCommandTests.swift` |

`apps/iosApp/project.yml` lists sources as **directory globs** (`- path: Supermux`), not as individual files. A Swift file rename inside `Supermux/` therefore needs **no** `project.yml` edit.

### `src/web-app`

| Today | New |
|---|---|
| `src/composables/useWorkspaceShortcuts.ts` | `src/composables/useShellShortcuts.ts` |
| `src/views/WorkspaceWelcomeView.vue` | `src/views/ShellWelcomeView.vue` |

⚠ `WorkspaceWelcomeView.vue` has **zero** references in `src/web-app` — nothing imports it and `router.ts` does not route to it. It looks like dead code. This plan renames it and does **not** delete it. Deletion is a separate decision for the user (see Task 5).

### Symbol renames (all codebases)

| Today | New | Where |
|---|---|---|
| `WorkspaceRoot` (composable) | `AppShell` | desktop |
| `WorkspaceUiState` (class) | `ShellUiState` | desktop, `WorkspaceRoot.kt:104` |
| `WorkspaceLayout` (class) | `ShellLayout` | desktop, android |
| `WorkspaceSnapshot` (data class) | `ShellSnapshot` | desktop, android |
| `WorkspaceStateStore` (class) | `ShellStateStore` | desktop |
| `WorkspaceShortcut` (enum) | `ShellShortcut` | desktop, android |
| `mapWorkspaceShortcut` (fun) | `mapShellShortcut` | desktop, android |
| `applyWorkspaceShortcut` (fun) | `applyShellShortcut` | desktop, android |
| `Modifier.workspaceShortcuts` (ext fun) | `Modifier.shellShortcuts` | desktop, android |
| `SessionWorkspaceDetail` (composable) | `SessionShellDetail` | android |
| `IPadWorkspace` (struct) | `PadShell` | iOS |
| `WorkspaceCommand` (enum) | `ShellCommand` | iOS |
| `useWorkspaceShortcuts` (fun) | `useShellShortcuts` | web |

`PaneVisibility` keeps its name in all four codebases. Phase 3 replaces it with the view model; renaming it now creates churn for nothing.

---

## Task 1: Desktop rename (`apps/desktop`)

**Files:**
- Move: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/` → `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/`
- Move: `apps/desktop/src/test/kotlin/dev/supermux/desktop/workspace/` → `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/`
- Modify (importers): `Main.kt`, `chat/ChatPanel.kt`, `chat/DesktopComposer.kt`, `editor/EditorPanel.kt`, `editor/EditorPrefs.kt`, `host/FleetState.kt`, `notify/NotificationController.kt`, `notify/NotificationManager.kt`, `notify/NotifyDecision.kt`, `session/ArchivedScreen.kt`, `session/LauncherStore.kt`, `session/SessionLauncherScreen.kt`, `session/SessionListPanel.kt`, `settings/EditorLspScreen.kt`, `settings/SettingsHub.kt`, `state/DesktopAppState.kt`, `usage/UsageScreen.kt`
- Modify (tests): every file under `apps/desktop/src/test/kotlin/` that imports the package

- [ ] **Step 1: Record the green baseline**

A rename is verified by the tests that already exist. Prove they pass *before* you touch anything, so a later red is unambiguously yours.

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`. Write down the test count from `apps/desktop/build/reports/tests/test/index.html`.

⚠ The desktop Compose UI tests need a display. On this Linux box they need `xvfb-run` and `SKIKO_RENDER_API=SOFTWARE`. Without them the run either hangs or throws `HeadlessException`.

⚠ If the baseline is already red, **stop and report**. Do not start a rename on top of a broken suite.

- [ ] **Step 2: Move the main source directory**

```bash
cd apps/desktop/src/main/kotlin/dev/supermux/desktop
git mv workspace shell
git mv shell/WorkspaceRoot.kt shell/AppShell.kt
git mv shell/WorkspaceLayout.kt shell/ShellLayout.kt
git mv shell/WorkspaceStateStore.kt shell/ShellStateStore.kt
git mv shell/WorkspaceShortcuts.kt shell/ShellShortcuts.kt
```

- [ ] **Step 3: Move the test source directory**

```bash
cd apps/desktop/src/test/kotlin/dev/supermux/desktop
git mv workspace shell
git mv shell/WorkspaceRootTest.kt shell/AppShellTest.kt
git mv shell/WorkspaceLayoutTest.kt shell/ShellLayoutTest.kt
git mv shell/WorkspaceStateStoreTest.kt shell/ShellStateStoreTest.kt
git mv shell/WorkspaceShortcutsTest.kt shell/ShellShortcutsTest.kt
git mv shell/WorkspaceUiStateTest.kt shell/ShellUiStateTest.kt
```

- [ ] **Step 4: Rewrite the package name and the symbols**

Run this from the repository root. The order matters: rename the longer symbols before the shorter ones, or `WorkspaceLayout` gets half-rewritten by the `Workspace` → `Shell` pass.

```bash
cd /path/to/repo   # replace with the real repo root
FILES=$(git ls-files 'apps/desktop/src/**/*.kt')
perl -pi -e '
  s/dev\.supermux\.desktop\.workspace/dev.supermux.desktop.shell/g;
  s/\bSessionWorkspaceDetail\b/SessionShellDetail/g;
  s/\bWorkspaceStateStore\b/ShellStateStore/g;
  s/\bWorkspaceSnapshot\b/ShellSnapshot/g;
  s/\bWorkspaceLayout\b/ShellLayout/g;
  s/\bWorkspaceShortcuts\b/ShellShortcuts/g;
  s/\bWorkspaceShortcut\b/ShellShortcut/g;
  s/\bmapWorkspaceShortcut\b/mapShellShortcut/g;
  s/\bapplyWorkspaceShortcut\b/applyShellShortcut/g;
  s/\bworkspaceShortcuts\b/shellShortcuts/g;
  s/\bWorkspaceUiState\b/ShellUiState/g;
  s/\bWorkspaceRoot\b/AppShell/g;
' $FILES
```

- [ ] **Step 5: Check that no "workspace" text is left in the desktop module**

```bash
grep -rin "workspace" apps/desktop/src || echo "CLEAN"
```

Expected: `CLEAN`.

If a hit remains, read it. A comment that says "the workspace layout" must become "the shell layout". A hit in `apps/desktop/build/` does not count — that directory is generated; do not edit it.

- [ ] **Step 6: Compile**

```bash
cd apps
./gradlew :desktop:compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

If it fails with `unresolved reference`, an importer was missed. `git ls-files 'apps/desktop/src/**/*.kt'` in Step 4 covers every tracked Kotlin file in the module, so a miss means the file is untracked. Run `git status` and add it.

- [ ] **Step 7: Run the tests**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`, with the **same test count as Step 1**. A rename must not change the count. A different count means a test file was lost in a `git mv`.

- [ ] **Step 8: Commit**

```bash
git add apps/desktop
git commit -m "$(cat <<'EOF'
refactor(desktop): rename the layout shell from Workspace to Shell

The workspaces-and-views spec introduces Workspace as a server-side entity
that holds views. The desktop client already used Workspace for the window
layout shell. Free the name before Phase 1 adds the real one.

Package dev.supermux.desktop.workspace -> dev.supermux.desktop.shell.
WorkspaceRoot -> AppShell, WorkspaceUiState -> ShellUiState, WorkspaceLayout
-> ShellLayout, WorkspaceSnapshot -> ShellSnapshot, WorkspaceStateStore ->
ShellStateStore, WorkspaceShortcut(s) -> ShellShortcut(s).

Pure refactor: no behaviour change, no new tests, same test count.

Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md
EOF
)"
```

---

## Task 2: Android rename (`apps/android`)

**Files:**
- Move: `apps/android/src/main/kotlin/dev/supermux/android/workspace/` → `.../android/shell/`
- Move: `apps/android/src/test/kotlin/dev/supermux/android/workspace/` → `.../android/shell/`
- Modify (importers): `apps/android/src/main/kotlin/dev/supermux/android/MainActivity.kt`, `apps/android/src/main/kotlin/dev/supermux/android/session/SessionKeepAlive.kt`

- [ ] **Step 1: Record the green baseline**

```bash
cd apps
./gradlew :android:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. Write down the test count.

The Android unit tests are JVM tests — they need no emulator and no `xvfb`.

- [ ] **Step 2: Move the main source directory**

```bash
cd apps/android/src/main/kotlin/dev/supermux/android
git mv workspace shell
git mv shell/WorkspaceLayout.kt shell/ShellLayout.kt
git mv shell/SessionWorkspaceDetail.kt shell/SessionShellDetail.kt
git mv shell/WorkspaceShortcuts.kt shell/ShellShortcuts.kt
```

- [ ] **Step 3: Move the test source directory**

```bash
cd apps/android/src/test/kotlin/dev/supermux/android
git mv workspace shell
git mv shell/WorkspaceLayoutTest.kt shell/ShellLayoutTest.kt
git mv shell/WorkspaceShortcutsTest.kt shell/ShellShortcutsTest.kt
```

- [ ] **Step 4: Rewrite the package name and the symbols**

```bash
cd /path/to/repo
FILES=$(git ls-files 'apps/android/src/**/*.kt')
perl -pi -e '
  s/dev\.supermux\.android\.workspace/dev.supermux.android.shell/g;
  s/\bSessionWorkspaceDetail\b/SessionShellDetail/g;
  s/\bWorkspaceSnapshot\b/ShellSnapshot/g;
  s/\bWorkspaceLayout\b/ShellLayout/g;
  s/\bWorkspaceShortcuts\b/ShellShortcuts/g;
  s/\bWorkspaceShortcut\b/ShellShortcut/g;
  s/\bmapWorkspaceShortcut\b/mapShellShortcut/g;
  s/\bapplyWorkspaceShortcut\b/applyShellShortcut/g;
  s/\bworkspaceShortcuts\b/shellShortcuts/g;
' $FILES
```

- [ ] **Step 5: Check that no "workspace" text is left**

```bash
grep -rin "workspace" apps/android/src || echo "CLEAN"
```

Expected: `CLEAN`. Fix any comment that still says "workspace".

- [ ] **Step 6: Compile and test**

```bash
cd apps
./gradlew :android:compileDebugKotlin :android:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with the same test count as Step 1.

- [ ] **Step 7: Fix the desktop header comment**

`apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/ShellLayout.kt` starts with a comment that says it is a verbatim copy of the Android file at its old path. That path no longer exists. Open the file and change the first line to:

```kotlin
// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/ShellLayout.kt —
```

⚠ This edit is in `apps/desktop`, not `apps/android`. Commit it here anyway — it is only correct once Task 2 has moved the Android file. Re-run `./gradlew :desktop:compileKotlin` after the edit to prove the comment change broke nothing.

- [ ] **Step 8: Commit**

```bash
git add apps/android apps/desktop
git commit -m "$(cat <<'EOF'
refactor(android): rename the layout shell from Workspace to Shell

Mirrors the desktop rename in the previous commit. The workspaces-and-views
spec needs the Workspace name for the new server-side entity.

Package dev.supermux.android.workspace -> dev.supermux.android.shell.
WorkspaceLayout -> ShellLayout, SessionWorkspaceDetail -> SessionShellDetail,
WorkspaceShortcut(s) -> ShellShortcut(s).

Also repoints the desktop ShellLayout.kt header comment at the Android file's
new path.

Pure refactor: no behaviour change, same test count.
EOF
)"
```

---

## Task 3: iOS and macOS rename (`apps/iosApp`)

**Files:**
- Move: `apps/iosApp/Supermux/Shell/IPadWorkspace.swift` → `PadShell.swift`
- Move: `apps/iosApp/Supermux/Shell/WorkspaceLayoutModel.swift` → `ShellLayoutModel.swift`
- Move: `apps/iosApp/Supermux/Shell/WorkspaceShortcuts.swift` → `ShellShortcuts.swift`
- Move: `apps/iosApp/SupermuxTests/WorkspaceLayoutModelTests.swift` → `ShellLayoutModelTests.swift`
- Move: `apps/iosApp/SupermuxTests/WorkspaceCommandTests.swift` → `ShellCommandTests.swift`
- Modify (importers): `Supermux/Chat/ChatView.swift`, `Supermux/Chat/SessionChrome.swift`, `Supermux/Sessions/LauncherStateStore.swift`, `Supermux/Shell/AgentViewToggle.swift`, `Supermux/Shell/PaneToggleCluster.swift`, `Supermux/Shell/RootView.swift`

⚠ **This task cannot be verified on Linux.** There is no Swift toolchain on the broker box. The `git mv` and the `perl` pass can run anywhere, but **Steps 4 and 5 must run on the Mac.** Do not commit this task from Linux without the Mac build. The memory digest records this class of mistake: a Swift change that compiled only in review and broke on the Mac.

- [ ] **Step 1: Move the files**

```bash
cd apps/iosApp
git mv Supermux/Shell/IPadWorkspace.swift Supermux/Shell/PadShell.swift
git mv Supermux/Shell/WorkspaceLayoutModel.swift Supermux/Shell/ShellLayoutModel.swift
git mv Supermux/Shell/WorkspaceShortcuts.swift Supermux/Shell/ShellShortcuts.swift
git mv SupermuxTests/WorkspaceLayoutModelTests.swift SupermuxTests/ShellLayoutModelTests.swift
git mv SupermuxTests/WorkspaceCommandTests.swift SupermuxTests/ShellCommandTests.swift
```

- [ ] **Step 2: Rewrite the symbols**

```bash
cd /path/to/repo
FILES=$(git ls-files 'apps/iosApp/**/*.swift')
perl -pi -e '
  s/\bIPadWorkspace\b/PadShell/g;
  s/\bWorkspaceLayoutModel\b/ShellLayoutModel/g;
  s/\bWorkspaceCommand\b/ShellCommand/g;
  s/\bWorkspaceShortcuts\b/ShellShortcuts/g;
' $FILES
```

Also rename the test class names inside the two moved test files. XCTest discovers classes by name, and a class called `WorkspaceLayoutModelTests` in a file called `ShellLayoutModelTests.swift` is confusing but still runs. Open each file and change:

```swift
final class WorkspaceLayoutModelTests: XCTestCase {   // before
final class ShellLayoutModelTests: XCTestCase {       // after
```

```swift
final class WorkspaceCommandTests: XCTestCase {       // before
final class ShellCommandTests: XCTestCase {           // after
```

- [ ] **Step 3: Check that no "workspace" text is left**

```bash
grep -rin "workspace" apps/iosApp/Supermux apps/iosApp/SupermuxTests || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 4: Regenerate the Xcode project and build (ON THE MAC)**

`project.yml` lists `- path: Supermux` as a directory glob, so no `project.yml` edit is needed. The project file is still generated from it.

```bash
cd apps/iosApp
xcodegen generate
xcodebuild -project Supermux.xcodeproj -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16' build
```

Expected: `BUILD SUCCEEDED`.

- [ ] **Step 5: Build the Mac target and run the tests (ON THE MAC)**

macOS is served by this same target set. A rename that builds for iOS can still break macOS.

```bash
cd apps/iosApp
xcodebuild -project Supermux.xcodeproj -scheme SupermuxMac -destination 'platform=macOS' build
xcodebuild -project Supermux.xcodeproj -scheme Supermux -destination 'platform=iOS Simulator,name=iPhone 16' test
```

Expected: `BUILD SUCCEEDED` twice, and `TEST SUCCEEDED` with the same test count as before the rename.

- [ ] **Step 6: Commit**

```bash
git add apps/iosApp
git commit -m "$(cat <<'EOF'
refactor(ios): rename the layout shell from Workspace to Shell

Mirrors the desktop and android renames. IPadWorkspace -> PadShell,
WorkspaceLayoutModel -> ShellLayoutModel, WorkspaceCommand -> ShellCommand,
WorkspaceShortcuts -> ShellShortcuts, and the two test classes with them.

project.yml lists sources as directory globs, so it needed no edit.

Verified on the Mac: iOS build, macOS build, and the test suite at its
pre-rename count.

Pure refactor: no behaviour change.
EOF
)"
```

---

## Task 4: Web rename (`src/web-app`)

**Files:**
- Move: `src/web-app/src/composables/useWorkspaceShortcuts.ts` → `useShellShortcuts.ts`
- Move: `src/web-app/src/views/WorkspaceWelcomeView.vue` → `ShellWelcomeView.vue`
- Modify: `src/web-app/src/App.vue`

- [ ] **Step 1: Record the green baseline**

```bash
bun test
cd src/web-app && bunx vue-tsc --noEmit
```

Expected: both pass. Write down the `bun test` count.

- [ ] **Step 2: Move the files**

```bash
cd src/web-app/src
git mv composables/useWorkspaceShortcuts.ts composables/useShellShortcuts.ts
git mv views/WorkspaceWelcomeView.vue views/ShellWelcomeView.vue
```

- [ ] **Step 3: Rewrite the symbols**

```bash
cd /path/to/repo
FILES=$(git ls-files 'src/web-app/src/**')
perl -pi -e '
  s/\buseWorkspaceShortcuts\b/useShellShortcuts/g;
  s/\bWorkspaceWelcomeView\b/ShellWelcomeView/g;
' $FILES
```

- [ ] **Step 4: Check that no "workspace" text is left**

```bash
grep -rin "workspace" src/web-app/src || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 5: Typecheck and test**

```bash
cd src/web-app && bunx vue-tsc --noEmit
cd /path/to/repo && bun test
```

Expected: the typecheck passes, and `bun test` reports the same count as Step 1.

- [ ] **Step 6: Commit**

```bash
git add src/web-app
git commit -m "$(cat <<'EOF'
refactor(web): rename the layout shell from Workspace to Shell

Completes the four-client rename. useWorkspaceShortcuts -> useShellShortcuts,
WorkspaceWelcomeView -> ShellWelcomeView.

Pure refactor: no behaviour change, same test count.
EOF
)"
```

---

## Task 5: Report the dead view to the user

**Files:** none. This task produces a message, not a diff.

- [ ] **Step 1: Confirm the finding**

```bash
grep -rn "ShellWelcomeView" src/web-app/src
```

Expected: exactly one hit — the file's own name is not in its contents, so expect **zero** hits, meaning nothing imports it. Also confirm it is not routed:

```bash
grep -n "Welcome" src/web-app/src/router.ts || echo "NOT ROUTED"
```

Expected: `NOT ROUTED`.

- [ ] **Step 2: Tell the user**

Report exactly this, and wait:

> `src/web-app/src/views/ShellWelcomeView.vue` (renamed from `WorkspaceWelcomeView.vue`) has no importers and no route. It looks like dead code from an earlier iteration. I renamed it rather than deleting it. Do you want it deleted?

Do **not** delete it without an answer. A file with no static importers can still be reachable through a dynamic import that the grep missed.

---

## Task 6: Full verification and the spec cross-check

**Files:** none. This task only runs commands and reads.

- [ ] **Step 1: Prove the name is free across the whole repository**

```bash
grep -rin "workspace" apps/desktop/src apps/android/src apps/iosApp/Supermux apps/iosApp/SupermuxTests src/web-app/src || echo "CLEAN"
```

Expected: `CLEAN`.

⚠ Do **not** run this over `docs/` or `src/core/`. The spec in `docs/superpowers/specs/` is *supposed* to say "workspace" everywhere — that is the new entity. `src/core/` does not have the word yet; Phase 1 adds it.

- [ ] **Step 2: Run the repository verify gate**

```bash
./.mux/verify.sh
```

Expected: `bun test` passes. This gate covers the broker and the web app. It does not build the Kotlin or the Swift clients — those were covered in Tasks 1, 2, and 3.

- [ ] **Step 3: Confirm the spec's rename table now matches reality**

Open `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md` and read section 12. Every "Today" path in that table must now be gone from disk, and every "New name" must exist. If the table and the tree disagree, the table is the stale one — fix the table, not the code, and say so in the commit.

- [ ] **Step 4: Commit any spec correction**

```bash
git add docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md
git commit -m "docs: correct the Phase 0 rename table to match what landed"
```

Skip this step if the table already matched.

---

## Notes for the engineer

- **Nothing in this plan is a behaviour change.** If any test's *assertions* need editing to stay green, stop. That means the rename hit something real, and this plan is wrong about it. Report it rather than adjusting the test.
- **The test count is the check.** Every task compares the count before and after. A rename that loses a test file compiles perfectly and silently reduces coverage — the count is what catches it.
- **`git mv`, not `mv`.** Git tracks the rename and the diff stays readable. A plain `mv` plus `git add` shows the whole file as deleted and re-added.
- **The `perl -pi -e` passes use `\b` word boundaries** so `WorkspaceLayout` does not get mangled by a broader `Workspace` rule. There is deliberately no bare `s/Workspace/Shell/g` anywhere in this plan.
- **`grep -rin` is case-insensitive on purpose.** It catches `workspaceShortcuts`, `WORKSPACE_MIN`, and prose in comments, not just the type names.
