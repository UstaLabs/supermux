// Root of the paired app shell. M1 Task 9 (this) is the workspace chrome: a collapsible,
// drag-resizable sidebar, the multi-pane SessionDetail with pane toggles, keyboard shortcuts, and
// UI-state persistence (WorkspaceStateStore → ui-state.json).
//
// State that the menu bar (Main.kt) also needs — the WorkspaceLayout + the selected session id —
// lives in a small [WorkspaceUiState] holder created in Main and passed down here, so File/View
// menu actions and the in-app shortcuts drive the same state.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.session.SessionLauncherScreen
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.session.inferHomeDir

/**
 * Holder for the workspace UI state that both [WorkspaceRoot] and the window MenuBar (Main.kt) act
 * on: the shared [WorkspaceLayout] and the selected session id. Created once in Main (so the menu
 * can reach it), hydrated from [WorkspaceStateStore] at startup.
 */
@Stable
class WorkspaceUiState {
    val layout = WorkspaceLayout()
    var selectedId by mutableStateOf<String?>(null)

    /**
     * Whether the New-Session launcher overlay (M4a Task 5) is showing. Flipped on by
     * onNewSession's three entry points (Ctrl+N via [workspaceShortcuts], the File menu item in
     * Main.kt, and the sidebar rail `+`) and by [WorkspaceRoot]'s own onNewSession; flipped off by
     * the launcher's back/escape or a successful submit. Lives here (not local to WorkspaceRoot) so
     * Main's MenuBar — which renders in the same FrameWindowScope but outside WorkspaceRoot's
     * composition — can open it too, the same reason [selectedId] lives here.
     */
    var launcherOpen by mutableStateOf(false)

    /**
     * One-shot external "open this file" request (sessionId → ref), consumed by [SessionDetail] and
     * routed through the SAME `onOpenFile` chain a chat-tap uses (toWorkdirRelativePath → pending
     * editor open + editor-pane flip). Set by the off-by-default `SM_OPEN_FILE` headless hook in
     * Main.kt; null in normal operation. Cleared once the matching SessionDetail consumes it.
     */
    var externalOpen by mutableStateOf<Pair<String, dev.supermux.ui.FilePathRef>?>(null)

    /**
     * One-shot "open the Finish dialog for this session" request (session id), consumed by the
     * matching [SessionDetail] — it drives the SAME `showFinishDialog` state the FinishButton click
     * flips, so the dialog opens in its Menu state and loads readiness. Set by the off-by-default
     * `SM_FINISH_TEST` headless hook in Main.kt; null in normal operation. Cleared once the matching
     * SessionDetail consumes it. It NEVER triggers a finish action — it only opens the menu.
     */
    var forceFinishDialogFor by mutableStateOf<String?>(null)

    /**
     * One-shot "force-open the header git-badge menu" request (session id + [GitMenuForceOp]),
     * consumed by the matching [SessionDetail] → [GitBadgeMenu]. `OPEN` only expands the dropdown;
     * `FETCH`/`PULL` additionally fire that op live through the SAME code path a real click uses
     * (see [GitMenuForceOp] KDoc — Push/Publish have no member here, so no hook can ever auto-fire
     * them). Set by the off-by-default `SM_GIT_MENU` headless hook in Main.kt; null in normal
     * operation. Cleared once the matching SessionDetail consumes it.
     */
    var forceGitMenuFor by mutableStateOf<Pair<String, GitMenuForceOp>?>(null)

    /**
     * One-shot "force-open the session-links (proxies) globe menu" request (session id), consumed
     * by the matching [SessionDetail] → [SessionLinksMenu]. Never opens a URL — only expands the
     * dropdown. Set by the off-by-default `SM_LINKS_MENU` headless hook in Main.kt; null in normal
     * operation; a no-op when the session has no proxies (the menu doesn't render). Cleared once
     * consumed.
     */
    var forceLinksMenuFor by mutableStateOf<String?>(null)

    /**
     * One-shot "force-open the ⋮ overflow menu" request (session id), consumed by the matching
     * [SessionDetail] → [OverflowMenu]. NEVER auto-clicks Rename/Mute/Kill — only expands the
     * dropdown. Set by the off-by-default `SM_OVERFLOW_MENU` headless hook in Main.kt; null in
     * normal operation. Cleared once consumed.
     */
    var forceOverflowFor by mutableStateOf<String?>(null)

    /**
     * One-shot "stage this file into the chat composer, upload it, then send" request (session id +
     * [dev.supermux.desktop.chat.ComposerExternalAttach]), consumed by the matching [SessionDetail] →
     * [dev.supermux.desktop.chat.ChatPanel] → `DesktopComposer`'s `externalAttach` — the SAME
     * `stageFiles`/`sendWith` funnel the Attach dialog and Send button use (see
     * `ComposerExternalAttach`'s KDoc). Set by the off-by-default `SM_CHAT_ATTACH` headless hook in
     * Main.kt; null in normal operation. Cleared once the matching composer consumes it (after the
     * chip reaches a terminal state and — on success — the send fires).
     */
    var externalAttach by mutableStateOf<Pair<String, dev.supermux.desktop.chat.ComposerExternalAttach>?>(null)

    /**
     * Reconciles the hydrated UI state against the [live] session-id set: drops a selection whose
     * session vanished (killed elsewhere / agent exit) and prunes the layout's per-session pane
     * state.
     *
     * GUARD: an EMPTY [live] set is treated as "sessions not loaded yet", NOT "everything died" —
     * `app.sessions` starts empty until the first WS Snapshot arrives, and reconciling against that
     * transient [] would nuke the hydrated selection + panes (and the debounced save would then
     * persist the emptied state back to ui-state.json permanently). Known edge case: a
     * genuinely-empty fleet never prunes — harmless, since with zero live sessions there is nothing
     * to select/render and stale pane entries are inert.
     */
    fun reconcileSessions(live: Set<String>) {
        if (live.isEmpty()) return
        if (selectedId != null && selectedId !in live) selectedId = null
        layout.prune(live)
    }
}

@Composable
fun WorkspaceRoot(
    app: DesktopAppState,
    ui: WorkspaceUiState,
    store: WorkspaceStateStore,
    // Injected (not `remember`-ed internally) for the SAME reason as [store]: production (Main.kt)
    // constructs the real default-path file, while tests pass a temp path so they never touch the
    // developer's real ~/.config/supermux-desktop/launcher-state.json.
    launcherStore: LauncherStore,
) {
    val layout = ui.layout
    val sessions by app.sessions.collectAsState()
    val messages by app.messages.collectAsState()
    val agentState by app.agentState.collectAsState()
    val lastBySession = remember(messages) { messages.mapValues { it.value.lastOrNull() } }

    val focused = LocalWindowInfo.current.isWindowFocused

    // New-Session launcher (M4a Task 5): Ctrl+N (workspaceShortcuts below), the rail `+`
    // (SessionsRail/SessionListPanel — already wired to onNewSession) and Main's File menu item
    // (which flips ui.launcherOpen directly, since WorkspaceUiState is shared with Main) all reach
    // the SAME overlay via ui.launcherOpen.
    val onNewSession: () -> Unit = { ui.launcherOpen = true }

    // Per-session composer drafts, hoisted here so switching sessions preserves each draft.
    // In-memory only for M1 — broker-side draft sync is M4.
    val drafts = remember { mutableStateMapOf<String, String>() }

    // Headless-verification hook (no input injection on CI boxes); harmless in production (off by
    // default). With SM_AUTOSELECT=1 we auto-select a session so the workspace renders under Xvfb
    // without a pointer: the SM_SMOKE_SEND target if one is set, otherwise the most-recently-active
    // session. Skips if a (valid) persisted selection already exists.
    if (System.getenv("SM_AUTOSELECT") == "1") {
        LaunchedEffect(sessions, lastBySession) {
            if (ui.selectedId == null && sessions.isNotEmpty()) {
                val smokeName = System.getenv("SM_SMOKE_SEND")
                    ?.substringBefore(':')?.takeIf { it.isNotBlank() }
                ui.selectedId = smokeName?.let { n -> sessions.firstOrNull { it.name == n }?.id }
                    ?: sessions.maxByOrNull { lastBySession[it.id]?.ts ?: "" }?.id
                    ?: sessions.first().id
            }
        }
    }

    // Headless-verification hook (like SM_AUTOSELECT): SM_PANES=etd force-opens Editor/Terminal/
    // Display for the selected session at startup so the 3-pane split can be screenshotted under
    // Xvfb without menu/pointer input. Each letter e/t/d flips the matching pane on.
    val panesHook = System.getenv("SM_PANES")?.takeIf { it.isNotBlank() }
    if (panesHook != null) {
        LaunchedEffect(ui.selectedId) {
            val id = ui.selectedId ?: return@LaunchedEffect
            val p = layout.panesFor(id)
            layout.setPanes(id, p.copy(
                editor = p.editor || 'e' in panesHook,
                terminal = p.terminal || 't' in panesHook,
                display = p.display || 'd' in panesHook,
            ))
        }
    }

    // Re-assert viewing presence whenever the selection or window focus changes (broker per-device
    // "viewing" tracker keys off (session id, visible)).
    LaunchedEffect(ui.selectedId, focused) {
        app.updateViewing(ui.selectedId, focused)
    }

    // Sessions changed: reconcile selection + pane state against the live set (empty-guarded — see
    // WorkspaceUiState.reconcileSessions) and prune stale drafts.
    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) return@LaunchedEffect // first Snapshot not in yet — don't wipe state
        val live = sessions.mapTo(mutableSetOf()) { it.id }
        ui.reconcileSessions(live)
        drafts.keys.filterNot { it in live }.forEach(drafts::remove)
    }

    // Debounced persistence, observed through snapshotFlow rather than a composition-scope
    // layout.snapshot() read — the latter would subscribe the whole WorkspaceRoot to every
    // fraction/pane change and recompose the root per frame during split drags. collectLatest +
    // delay(500) = settle 500ms after the last change; the file write runs off the UI thread.
    LaunchedEffect(Unit) {
        snapshotFlow { PersistedUiState(layout = layout.snapshot(), selectedId = ui.selectedId) }
            .collectLatest {
                delay(500)
                withContext(Dispatchers.IO) { store.save(it) }
            }
    }

    val home = remember(sessions) {
        inferHomeDir(sessions.firstOrNull()?.workdir) ?: System.getProperty("user.home").orEmpty()
    }

    // Root focus so the workspace shortcuts (Ctrl/Cmd B/N/L/E/T/D) resolve even before the user
    // clicks into a pane; once the composer/terminal is focused, key events still bubble up here.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .focusable()
                // Gate the pane/sidebar shortcuts (Ctrl+B/L/E/T/D) OFF while the launcher overlay is
                // up: it's modal, so a chord it leaves unhandled must NOT bubble here and silently
                // mutate the layout behind it (sidebar collapse / pane toggles the user can't see).
                // The overlay handles its own Escape; Ctrl+N is idempotent and reopening an already-
                // open launcher is a no-op, so dropping it here too costs nothing.
                .then(if (ui.launcherOpen) Modifier else Modifier.workspaceShortcuts(layout, ui.selectedId, onNewSession)),
        ) {
            Row(Modifier.fillMaxSize()) {
                // ── Sidebar: collapsed rail, or the full list + a drag-resize gutter ──
                if (layout.sidebarCollapsed) {
                    SessionsRail(
                        sessions = sessions,
                        selectedId = ui.selectedId,
                        agentState = agentState,
                        onSelect = { ui.selectedId = it },
                        onExpand = { layout.sidebarCollapsed = false },
                        onNewSession = onNewSession,
                    )
                } else {
                    SessionListPanel(
                        sessions = sessions,
                        home = home,
                        activeId = ui.selectedId,
                        onOpen = { ui.selectedId = it },
                        lastBySession = lastBySession,
                        agentState = agentState,
                        onRename = { id, name -> app.rename(id, name) },
                        onKill = { id -> app.kill(id) { if (ui.selectedId == id) ui.selectedId = null } },
                        onMute = { id, muted -> app.setMute(id, muted) },
                        modifier = Modifier.width(layout.sidebarWidth).fillMaxHeight(),
                    )
                    SidebarDivider(
                        onDragDelta = { d -> layout.setSidebarWidth(layout.sidebarWidth + d) },
                        onCollapse = { layout.sidebarCollapsed = true },
                    )
                }

                // ── Detail: the multi-pane SessionDetail, or an empty prompt ──
                val id = ui.selectedId
                val session = id?.let { sel -> sessions.firstOrNull { it.id == sel } }
                if (session == null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("select a session", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    SessionDetail(
                        app = app,
                        session = session,
                        agent = agentState[session.id],
                        layout = layout,
                        draft = drafts[session.id] ?: "",
                        onDraftChange = { drafts[session.id] = it },
                        // SM_OPEN_FILE hook (Main.kt) delivery: hand the pending external open to the
                        // SessionDetail whose id matches, which routes it through onOpenFile.
                        externalOpen = ui.externalOpen?.takeIf { it.first == session.id }?.second,
                        onExternalOpenConsumed = { ui.externalOpen = null },
                        // SM_FINISH_TEST hook (Main.kt) delivery: open the Finish dialog (menu state)
                        // for the SessionDetail whose id matches; consumed once opened.
                        forceFinishDialog = ui.forceFinishDialogFor == session.id,
                        onForceFinishConsumed = { ui.forceFinishDialogFor = null },
                        // SM_GIT_MENU/SM_LINKS_MENU/SM_OVERFLOW_MENU hook (Main.kt) delivery — see
                        // WorkspaceUiState's KDoc for each field.
                        forceGitMenu = ui.forceGitMenuFor?.takeIf { it.first == session.id }?.second,
                        onForceGitMenuConsumed = { ui.forceGitMenuFor = null },
                        forceLinksMenu = ui.forceLinksMenuFor == session.id,
                        onForceLinksMenuConsumed = { ui.forceLinksMenuFor = null },
                        forceOverflowMenu = ui.forceOverflowFor == session.id,
                        onForceOverflowMenuConsumed = { ui.forceOverflowFor = null },
                        // SM_CHAT_ATTACH hook (Main.kt) delivery: hand the pending external
                        // attach+send request to the SessionDetail whose id matches.
                        externalAttach = ui.externalAttach?.takeIf { it.first == session.id }?.second,
                        onExternalAttachConsumed = { ui.externalAttach = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ── New-Session launcher: a FULL-PANE overlay above the workspace (M4a Task 5) ──
            // Desktop takes the overlay shape rather than Android's route-navigation — there's no
            // back stack here, and a Box drawn last (top of z-order) over the still-mounted
            // workspace keeps the sidebar/session list state alive underneath while the launcher is
            // up. Escape closes it (same as the back button) without spawning; the draft persists
            // either way (SessionLauncherScreen's own dispose-flush, T4).
            if (ui.launcherOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("launcher_overlay")
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                ui.launcherOpen = false
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    SessionLauncherScreen(
                        sessions = sessions,
                        home = home,
                        onBack = { ui.launcherOpen = false },
                        loadProjects = { app.listProjects() },
                        validatePath = { app.validatePath(it) },
                        loadModels = { app.launcherModels(it) },
                        loadReasoningLevels = { a, m -> app.launcherReasoning(a, m) },
                        loadRepoInfo = { app.launcherRepoInfo(it) },
                        loadPrefs = { launcherStore.loadPrefs() },
                        onPrefsChange = { launcherStore.savePrefs(it) },
                        loadDraft = { launcherStore.loadDraft() },
                        onDraftChange = { launcherStore.saveDraft(it) },
                        onClearDraft = { launcherStore.clearDraft() },
                        // Spawn → select + send the first message → close. A null id (invalid
                        // workdir / spawn failure) is surfaced by THROWING — SessionLauncherScreen's
                        // own doSubmit try/catch turns any thrown message into the inline
                        // launcher_error text (its onSubmit contract is Unit-returning, so a failure
                        // has no other channel back to the screen).
                        onSubmit = { workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch ->
                            val id = app.createSessionWithFirstMessage(
                                workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch,
                            )
                            if (id == null) {
                                throw IllegalStateException(
                                    "Couldn't create the session — check the working directory and try again.",
                                )
                            }
                            ui.selectedId = id
                            app.sendMessage(id, text, app.consumeFirstUploads(id))
                            ui.launcherOpen = false
                        },
                    )
                }
            }
        }
    }
}
