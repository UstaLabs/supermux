# Android Workspaces & Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan phase-by-phase. Steps use checkbox (`- [ ]`) syntax for tracking. Each phase is a separate branch + PR into `dev`.

**Goal:** Move the Android app from the flat session model onto the broker-owned workspace/view model that desktop already ships — workspace sidebar, broker layout tree, tabs on phones, full pane tree on tablets — reusing the shared `:shared` domain and the `:ui` pane layer instead of Android's private copies.

**Architecture:** Android already reads every workspace frame off the wire (they are `ServerFrame` subtypes in commonMain) but drops them in `AppViewModel.reduce`'s `else`. The plan is: (1) hold `WorkspaceDto`/`ViewDto` state in `AppViewModel`, (2) render the sidebar from `groupWorkspaces()`, (3) replace the private `apps/android/.../workspace/` pane layout with the broker's `LayoutNode` — read-only tabs on phones (spec §8.3), read-write `PaneHost` from `:ui` on ≥600 dp — and (4) hoist the generic desktop view logic (`WorkspaceLayoutState`, `ViewHost` dispatch, `DocumentStore`) into shared modules so Android and desktop run one implementation. Spec: `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md` (§8.3, §13.5, §13.6).

**Tech Stack:** Kotlin 2.3 / KMP, Compose Multiplatform, Material 3 (stable 1.4), Navigation-Compose, ktor, `:shared` (commonMain), `:ui` (currently `kotlin.jvm` → becomes multiplatform with `androidTarget()`).

---

## 0. Where Android is today (2026-08-29 inspection)

| Area | Android (`apps/android`) | Desktop (`apps/desktop`) |
|---|---|---|
| Container | `Session` / `SessionInfo`, `SessionGrouping`, `SessionReorder` | `WorkspaceDto` + `groupWorkspaces()`, `PATCH /workspaces/reorder` |
| Frames handled | 25 (no `Workspace*`/`View*`) | all 8 workspace/view frames |
| Layout | private `workspace/WorkspaceLayout.kt`: `PaneVisibility{chat,editor,terminal,display}` + a per-session `LayoutNode` tree in SharedPrefs (`cmux-workspace-layout`) — client-local, never sent to broker | broker `LayoutNode` via `WorkspaceLayoutState` (pending-edit rebase + debounced PATCH) |
| Pane UI | `SessionWorkspaceDetail`, `ResizableSplit`, `PaneToggleCluster` (own code) | `:ui` `PaneHost` / `PaneDragController` / `PaneDropOverlay` / `SplitSeam` |
| Views | fixed 4: chat, editor, terminal, display, all bound to one session | `ViewHost` switches on `ViewDto.kind` → chat / agent-terminal / workspace-terminal / explorer / file / diff / display |
| Archive | archived **sessions** (`Archived` route, `ArchivedDto`, `onResume`) | archived **workspaces** (`GET /archived-workspaces`, `POST /workspaces/:id/restore`) |
| Continue-in-new | none (`HandoffPrefill` in shared unused) | pickers + `SpawnBody{workspaceId, inheritFrom, firstMessage}` |
| Editor | CodeMirror 6 in WebView (`editor/WebCodeEditor.kt`, `AndroidLspBridge`), own `editor/EditorState.kt`, native `DiffView.kt` | JCEF CodeMirror, `DocumentStore`/`ExplorerState`/`DiffState` |
| Deps | `:shared` only (**not** `:ui`) | `:shared` + `:ui` |
| Build env | needs `apps/local.properties` (`sdk.dir=~/Android/Sdk`) + `apps/android/google-services.json` (copy from `.example`) | — |

Keep untouched (Android-only assets that must survive): FCM push (`push/`), `supermux://pair` deep link + QR/TOFU pairing, self-update (`update/`), ConnectBot terminal (`terminal/`), VNC/scrcpy `display/`, `SwipeActionRow`, Play-store config, CodeMirror WebView bridge.

## 1. Decisions locked in by this plan

1. **The broker's layout is the only layout.** `apps/android/.../workspace/WorkspaceLayout.kt`, `WorkspaceSnapshot`, `PaneVisibility`, `PaneToggleCluster`, `ResizableSplit` and the `cmux-workspace-layout` SharedPrefs blob are **deleted** at the end of Phase 3. One migration step wipes the old pref key.
2. **Phone = read-only tabs (spec §8.3).** `Breakpoint.isWorkspaceWidth` (≥600 dp) is the single gate: below it the client never calls `PATCH /workspaces/:id {layout}`; it only calls `PATCH … {activeViewId}` and `POST/DELETE views` (adding/closing a view is content, not arrangement).
3. **`:ui` becomes multiplatform** (`jvm()` + `androidTarget()`), not copied. Two known leaks to fix: `SplitSeam.kt` uses `java.awt.Cursor` for resize cursors (→ `expect`/`actual` `PointerIcon`, Android actual returns `PointerIcon.Default`), and `PaneHost.kt:621` uses `java.util.UUID` (→ shared id minter already used for client-minted view ids; grep `BrokerApi.addView` callers on desktop for it).
4. **Hoist before port, not after** (Ahmet, 2026-08-29: Q5=a). Generic desktop state that Android needs is moved to `:shared` commonMain first (Phase 3, before any Android pane work) so Android never grows a second copy: `WorkspaceLayoutState`, `WorkspaceFileOpener`, `WorkspaceKeepAliveCache`, `viewTitle`, `DocumentStore`, `ExplorerState`, `DiffState`. Android's `editor/EditorState.kt` is deleted in favour of them.
5. **Sidebar rows stay lean and keep git data** (spec §13.6 + digest rule): reuse `SessionStatusRail`/`GitBadge`, one row per workspace, children only when ≥2 views.
6. **Multi-window / tear-out / `WindowHostRegistry` / `PersistedWindowHost` are out of scope** for Android.
7. **Phone flattens the tree** (Q3=b): every view of the workspace, in `collectViewIds(layout)` order, is one tab row; groups/splits are ignored on a phone. No overflow chip.
8. **Phone may add every view kind and may close a view** (Q1, Q2), with a confirm dialog when closing ends work (terminal, display) — same as desktop.
9. **Tablets: one CodeMirror WebView per editor pane** (Q4=a), same as desktop's one-JCEF-per-view, bounded by the keep-alive LRU.

## 2. Phases

Order: 1 state → 2 sidebar → 3 hoist + `:ui` multiplatform → 4 views/layout → 5 continue → 6 presence → 7 verify. Each phase ends green on `:android:testDebugUnitTest` + `:android:assembleDebug` + `:shared:allTests` + `:ui:jvmTest` (and `:ui:testDebugUnitTest` once Android target exists), and is verified on the `pixel_api35` (phone) and `workspace_fold` (tablet, unfolded 2076×2152) emulators per the e2e recipe in `~/.mux/domains/claudemux.digest.md` ("e2e verdicts").

### Phase 1 — Workspace state in `AppViewModel` (no UI change) — DONE `cc509b8b`…`a7baf560` (2026-08-29)

**Files:** `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt`, `host/HostStores.kt`, `host/FleetModel.kt`, new `apps/android/src/test/kotlin/dev/supermux/android/WorkspaceReducerTest.kt`.

- [x] Add `workspaces: Map<String, WorkspaceDto>`, `archivedWorkspaces: Map<String, WorkspaceDto>`, `workspaceOrder: List<String>` to the per-host store (mirror the exact shape desktop uses in `DesktopAppState` — it was ported from this ViewModel, so the diff is small).
- [x] Seed from `ServerFrame.Snapshot.workspaces` / `.archivedWorkspaces`.
- [x] Reduce `WorkspaceAdded`, `WorkspaceChanged` (full replacement), `WorkspaceRemoved`, `WorkspacesReordered` (index = sort_order, same contract as `SessionsReordered`), `ViewAdded`, `ViewChanged`, `ViewRemoved`, `ViewMoved`.
- [x] Derive `workspaceForSession(sessionId)` (via `chatSessionIds` in `WorkspaceGrouping.kt`) so existing session-scoped screens can find their workspace without changing yet.
- [x] Tests: one reducer test per frame, plus the contract test pattern from `test(shared): cover sessions_reordered frame in contract test` (`d42d91e0`) for the 8 frames.
- [x] Commit: `feat(android): hold broker workspaces and views in AppViewModel`.

### Phase 2 — Sidebar shows workspaces (spec §13.6) — DONE `8e8d3ec1`, `33179b7e` (2026-08-29)

**Files:** `session/SessionListScreen.kt`, `session/SessionListRail*`, `session/SwipeActionRow.kt`, `settings/MoreScreens.kt` (Archived), `nav/Routes.kt`, tests `SessionListRailUiContract`, `SessionListInteractions`, new `WorkspaceListTest`.

- [x] Replace `groupSessions()` with shared `groupWorkspaces()` (already pins Personal Assistants, `745e41be`). Row = workspace name · `formatWorkdir` · branch + git status (reuse `GitBadge`; broker sends workspace-scoped git as of `bda1eb23`) · busiest chat state via `workspaceActivity()` · multi-agent mark via `isMultiAgent()`.
- [x] Children: when a workspace has ≥2 chat views, list its chat sessions under the row behind a disclosure; tapping a child selects that session inside the workspace.
- [x] Selection model: `selectedWorkspaceId` + `selectedSessionId` (the chat view in focus). ⚠ A workspace id must never enter the session `Viewing` frame (desktop bug `992b7602`) — `ClientFrame.Viewing(session, visible, sessions)` gets the chat session ids of the visible group only.
- [x] Reorder: drag calls `BrokerApi.reorderWorkspaces` instead of `PATCH /sessions/reorder`; apply `WorkspacesReordered` optimistically like sessions today.
- [x] Swipe actions: archive → `BrokerApi.archiveWorkspace(id)`; keep mute/rename on the primary session.
- [x] Archived screen lists `archivedWorkspaces` (`groupArchivedWorkspaces()`), Restore → `BrokerApi.restoreWorkspace(id)`; drop the session-level `onResume` path.
- [x] Update `SessionListRailUiContract` test-id vocabulary (shared across web/Android/iOS/desktop, `9868a02e`) — add workspace-row ids in lockstep with desktop's.
- [x] Commit: `feat(android): workspace sidebar with grouping, reorder, archive and restore`.

### Phase 3 — Hoist generic view logic out of `apps/desktop` and make `:ui` multiplatform — DONE `35afa52c`, `949a4791`…`5572adf6` (2026-08-29)

**Files:** `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/{WorkspaceLayoutState,WorkspaceSession,WorkspaceFileOpen,WorkspaceSingletonView,WorkspaceKeepAlive,ViewHost(viewTitle only),DocumentStore,ExplorerState,DiffState,EditorState}.kt` → `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/…` (pure state) and `apps/ui/src/commonMain/…` (composables). Desktop keeps thin imports. Android does not touch any of this yet; it consumes it in Phase 4.

- [x] **3a. `:ui` goes multiplatform.** `apps/ui/build.gradle.kts`: `kotlin.multiplatform` + `android.library`, `jvm()` + `androidTarget()`, move `src/main` → `src/commonMain`, `src/test` → `src/jvmTest`. Add `:ui:testDebugUnitTest` to `ci.yml` (the digest records CI silently not running `:ui:jvmTest` once — check the lane). `SplitSeam.kt`: `expect val ColResizeIcon: PointerIcon` / `RowResizeIcon`; jvm actual = current AWT cursors; android actual = `PointerIcon.Default`. `PaneHost.kt:621`: replace `java.util.UUID` with the shared id minter desktop uses for client-minted view ids (`3bfb400c`). Commit: `build(ui): add androidTarget and drop AWT/UUID from the pane layer`.
- [x] Move `WorkspaceLayoutState` (pending-edit rebase onto `workspace_changed`, 300 ms debounce, drop-on-refuse). Its tests move with it into `:shared` commonTest.
- [x] Move `WorkspaceKeepAliveCache` (LRU of 10 ids incl. active) + `WorkspaceKeepAliveHost`; Android's `SessionKeepAlive` becomes a caller.
- [x] Move `DocumentStore` (per-workspace, dirty tracking), `ExplorerState`, `DiffState`. (Android binds to them in Phase 4 and deletes `apps/android/.../editor/EditorState.kt` then.)
- [x] Move `viewTitle(view)` and `WorkspaceFileOpener` (transcript file-path → editor view, `55e2a15b`).
- [x] Desktop must be byte-for-byte behaviour-identical: run `:desktop:test` and the desktop hot-run smoke on the Mac (`./gradlew :desktop:hotRun --auto`).
- [x] Commit per moved unit: `refactor(shared): hoist WorkspaceLayoutState from desktop`, etc.

### Phase 4 — Views and the layout tree replace `PaneVisibility` — DONE `a3548078`…`f10a55ee` + fixes `feaaa428`…`a5e83e53` (2026-08-29)

**Files:** `workspace/*` (mostly deleted), `session/SessionWorkspaceDetail.kt`, `chat/ChatScreen.kt`, `editor/EditorScreen.kt`, `terminal/TerminalPanel.kt`, `display/DisplayPanel.kt`, `MainActivity.kt`, `apps/ui/build.gradle.kts`, `apps/ui/src/**/SplitSeam.kt`, `PaneHost.kt`, `apps/android/build.gradle.kts`.

4a. **Wire `:ui` into Android**
- [x] `apps/android/build.gradle.kts`: `implementation(project(":ui"))`; delete Android's `editor/EditorState.kt` and bind `editor/EditorTabs.kt`, `FileTree.kt`, `DiffView.kt` to the shared `DocumentStore`/`ExplorerState`/`DiffState` from Phase 3.
- [x] Commit: `build(android): depend on :ui and the shared document stores`.

4b. **`ViewHost` for Android**
- [x] New `apps/android/.../workspace/AndroidViewHost.kt`: `@Composable fun AndroidViewHost(view: ViewDto, workspace: WorkspaceDto, …)` switching on `view.kind` and its state: `chat` → `ChatPanel(sessionId)`; `terminal` scope=session → existing agent terminal, scope=workspace → workspace terminal (`24e2629c` added the broker side); `editor` mode tree/file/diff → `FileTree` / `WebCodeEditor` / `DiffView` on the **workspace-scoped** FS routes (`77d64a73`, `BrokerApi` workspace FS methods) instead of session-scoped; `display` → `DisplayPanel(displayId)`; unknown → hint. Titles via shared `viewTitle` (Phase 3).
- [x] Commit: `feat(android): AndroidViewHost dispatches broker views to native panes`.

4c. **Phone: one group at a time (spec §8.3)**
- [x] `session/SessionWorkspaceDetail.kt` becomes `WorkspaceScreen`: when `!isWorkspaceWidth`, render a `PrimaryTabRow` of **every** view id in `collectViewIds(layout)` order (groups and splits ignored — decision 7) and one `AndroidViewHost` for `activeViewId`. Tab tap → `BrokerApi.patchWorkspace(id, activeViewId=…)` only. A "+" in the tab row → `POST /workspaces/:id/views` (terminal / files / diff / display), closing a tab → `DELETE …/views/:viewId` (this ends the work — confirm for terminal/display, as desktop does; decision 8). **Never** send `layout` from a phone.
- [x] Commit: `feat(android): phone shows every view as a tab, never writes the layout`.

4d. **Tablet / unfolded: full tree**
- [x] When `isWorkspaceWidth`, render `PaneHost(layout, onEdit = layoutState::edit, tabSlot/addSlot/emptyGroupSlot = Android chrome, content = AndroidViewHost)` where `layoutState` is the shared `WorkspaceLayoutState` from Phase 3. Each editor view gets its own `WebCodeEditor` WebView (decision 9). Drag/drop, split, reorder, move-to-group all come from `:ui`.
- [x] `Breakpoint` change mid-session (fold/unfold): switching from tree to tabs must keep `activeViewId`; switching back must not PATCH anything until the user drags.
- [x] Commit: `feat(android): tablets render the broker layout tree with PaneHost`.

4e. **Delete the private layout**
- [x] Remove `workspace/WorkspaceLayout.kt`, `WorkspaceSnapshot`, `PaneVisibility`, `PaneToggleCluster`, `ResizableSplit`, `WorkspaceLayoutTest`, and the `cmux-workspace-layout` pref read path; add a one-shot `remove("cmux-workspace-layout")` in `AndroidSnapshotPersistence`.
- [x] Keep `Breakpoint.kt`, `SessionsRail.kt`, `WorkspaceShortcuts` (keyboard on tablets).
- [x] Commit: `refactor(android): drop the client-local pane layout`.

### Phase 5 — Continue-in-new-conversation and chat header parity — DONE `ef3edb4a`…`ab71a250` (2026-08-29)

**Files:** `chat/ChatScreen.kt` header, `session/SessionLauncherScreen.kt`, `chat/Pickers.kt`.

- [x] Chat header ⋮ menu: detail level / rename / mute / **continue** (desktop `4f7526e1`).
- [x] Continue: `BrokerApi.spawn(SpawnBody(workspaceId = current, inheritFrom = sessionId, firstMessage = prefill))`; do **not** fire a WS `Send` after spawn — broker delivers `firstMessage` after `session_added` (`9c78b06e`). Reuse shared `HandoffPrefill`.
- [x] "New chat in this workspace" from the workspace row joins the workspace and starts in its workdir (`55c4552d`-style: pass `workspaceId` on `POST /sessions`).
- [x] Commit: `feat(android): continue in new conversation inside the same workspace`.

### Phase 6 — Presence, unread, notifications on the workspace model

- [ ] `Viewing` frames: one per visible chat view (`a5e049f9`), using the atomic `WorkspaceViewingSnapshot` pattern (`04041157`) — visible = Home route ∧ no launcher/sheet ∧ resolved workspace ∧ app foreground.
- [ ] Unread badge on the workspace row = server-authoritative session unread (`8e153022`) aggregated over the workspace's chat sessions.
- [ ] FCM tap → open the session's **workspace** and set `activeViewId` to that chat view locally (no PATCH).
- [ ] Commit: `feat(android): workspace-scoped viewing, unread and push routing`.

### Phase 7 — Verification & release

- [ ] Emulator e2e on `pixel_api35` (phone tabs, never PATCHes layout — assert with broker log grep for `PATCH /workspaces/.*layout`) and `workspace_fold` unfolded (4 live panes, drag-merge → tab strip, survive force-stop + relaunch, fold/unfold live). Reuse the `/tmp/e2e-shots` recipe.
- [ ] Cross-client check: arrange a 3-pane layout on desktop, open on phone → tabs match; move a view on tablet → desktop updates live.
- [ ] Bump `versionCode`/name in `apps/android/build.gradle.kts`, run `play-store/RELEASE-CHECKLIST.md`, sideload APK to supermux-apk.ustalabs.com, then Play closed test.

## 3. Answers from Ahmet (2026-08-29)

1. Phone may add **all** view kinds.
2. Phone **may close** a view even though it ends the work (confirm dialog).
3. Split groups on a phone: **flatten** into one tab row (b).
4. Tablet editor: **one WebView per editor pane** (a).
5. Ordering: **hoist first** (a) — Phase 3 before Phase 4.

## 3b. Follow-ups noted during execution
- `apps/shared/.../workspace/NewViewKind.kt` carries user-facing labels ("Files", "In this pane") and desktop test tags in a Compose-free module. Keep `wire`/`placement`/`singleton` shared; move `label`/`tag` to each client's menu. (Phase 3b review, minor.)
- Workspace rename on every client still renames the primary chat session (spec §9.5 rules 1-4); rule 5 (`patchWorkspace(name)` + `name_locked`) is implemented nowhere. Cross-platform follow-up.

## 4. Risks

- `:ui` → multiplatform touches desktop's build; do it on its own branch and test `:desktop:packageDmg` path in release CI before merging.
- Android emulator runs OOM-killed gradle before (digest) — run with `-Dorg.gradle.jvmargs=-Xmx3g` and one task at a time.
- SharedPrefs blob deletion is one-way; no user data lives there beyond arrangement, acceptable.
- Play review: no new permissions; behaviour change only.
