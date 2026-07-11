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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.supermux.desktop.host.AddHostScreen
import dev.supermux.desktop.host.FleetState
import dev.supermux.desktop.host.HostView
import dev.supermux.desktop.notify.NoopNotificationManager
import dev.supermux.desktop.notify.NotificationController
import dev.supermux.desktop.session.ArchivedScreen
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.session.SessionLauncherScreen
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.desktop.settings.LspSettingsScreen
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.usage.UsageScreen
import dev.supermux.net.ArchivedDto
import dev.supermux.net.UsageResponse
import dev.supermux.session.inferHomeDir
import kotlinx.coroutines.flow.MutableStateFlow

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
     * Whether the Archived-sessions overlay (M4e Task 2) is showing. Flipped on by the File ▸
     * "Archived…" menu item in Main.kt (which reaches this shared state the same way New-Session
     * does); flipped off by the screen's back/escape or a Resume. Lives here (not local to
     * [WorkspaceRoot]) for the SAME reason as [launcherOpen] — Main's MenuBar renders outside
     * WorkspaceRoot's composition but must open it.
     */
    var archivedOpen by mutableStateOf(false)

    /**
     * Whether the Usage overlay (M4f Task 2) is showing. Flipped on by the File ▸ "Usage…" menu
     * item in Main.kt and the SessionDetail overflow ⋮ "Usage" row (both reach this shared state
     * the same way New-Session/Archived do); flipped off by the screen's back/escape. Lives here
     * (not local to [WorkspaceRoot]) for the SAME reason as [launcherOpen]/[archivedOpen] — Main's
     * MenuBar renders outside WorkspaceRoot's composition but must open it.
     */
    var usageOpen by mutableStateOf(false)

    /**
     * Whether the LSP settings overlay (M4g-4) is showing. Flipped on by the File ▸
     * "Editor / LSP…" menu item in Main.kt and the SessionDetail overflow ⋮ row (both reach this
     * shared state the same way New-Session/Archived/Usage do); flipped off by the screen's own
     * back button or Escape. Lives here (not local to [WorkspaceRoot]) for the SAME reason as
     * [launcherOpen]/[archivedOpen]/[usageOpen] — Main's MenuBar renders outside WorkspaceRoot's
     * composition but must open it.
     */
    var lspSettingsOpen by mutableStateOf(false)

    /**
     * Any full-pane modal overlay ([launcherOpen], [archivedOpen], [usageOpen], or
     * [lspSettingsOpen]) is up. The workspace pane/sidebar shortcuts (Ctrl+B/L/E/T/D) are gated
     * OFF while this is true, so a chord an overlay leaves unhandled can't bubble to
     * [workspaceShortcuts] and silently mutate the layout behind it. One gate for every overlay,
     * so new overlays don't each have to remember to extend the guard.
     */
    val overlayOpen: Boolean get() = launcherOpen || archivedOpen || usageOpen || lspSettingsOpen

    /**
     * Open the New-Session launcher, enforcing the "at most one overlay" invariant (closes the
     * other overlays if they were up). ALL launcher open sites route through here (Ctrl+N /
     * File ▸ New Session / the rail `+`) so the full-pane overlays can never both be open — each
     * draws opaquely over the others, and a stale one surfacing when another closes would be a
     * confusing back-stack. Every future overlay adds a matching openX().
     */
    fun openLauncher() {
        launcherOpen = true
        archivedOpen = false
        usageOpen = false
        lspSettingsOpen = false
    }

    /** Open the Archived-sessions overlay; the "at most one overlay" mirror of [openLauncher]. */
    fun openArchived() {
        archivedOpen = true
        launcherOpen = false
        usageOpen = false
        lspSettingsOpen = false
    }

    /** Open the Usage overlay; the "at most one overlay" mirror of [openLauncher]/[openArchived]. */
    fun openUsage() {
        usageOpen = true
        launcherOpen = false
        archivedOpen = false
        lspSettingsOpen = false
    }

    /** Open the LSP settings overlay; the "at most one overlay" mirror of [openLauncher]/
     *  [openArchived]/[openUsage]. */
    fun openLspSettings() {
        lspSettingsOpen = true
        launcherOpen = false
        archivedOpen = false
        usageOpen = false
    }

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
     * One-shot "transcribe this WAV file into the chat composer's draft" request (session id +
     * [dev.supermux.desktop.chat.ComposerExternalDictate]), consumed by the matching
     * [SessionDetail] -> [dev.supermux.desktop.chat.ChatPanel] -> `DesktopComposer`'s
     * `externalDictate` — the SAME `onTranscribeAudio` seam the mic button uses (see
     * `ComposerExternalDictate`'s KDoc). Set by the off-by-default `SM_DICTATE` headless hook in
     * Main.kt; null in normal operation. Cleared once the matching composer consumes it.
     */
    var externalDictate by mutableStateOf<Pair<String, dev.supermux.desktop.chat.ComposerExternalDictate>?>(null)

    /**
     * One-shot "open this archived session's read-only transcript" request (an ARCHIVED session
     * id, not a live one), consumed by [dev.supermux.desktop.session.ArchivedScreen] — it seeds the
     * internal list⇄chat nav (`openedId`) so the matching row's read-only `ArchivedChatView` renders
     * without a click, the same way [forceFinishDialogFor] seeds the Finish dialog. Set by the
     * off-by-default `SM_ARCHIVED_OPEN` headless hook in Main.kt (which also flips [archivedOpen] via
     * [openArchived] so the overlay is showing); null in normal operation. Cleared once
     * [dev.supermux.desktop.session.ArchivedScreen] consumes it (mirrors [forceLinksMenuFor]'s
     * consumed-callback pattern — see [WorkspaceRoot]'s wiring).
     */
    var forceArchivedOpenFor by mutableStateOf<String?>(null)

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
    // M5-3: injectable so production (Main.kt) passes the real Tray-backed controller while the
    // existing WorkspaceRootTest suite (and any other caller that doesn't care about
    // notifications) keeps compiling unmodified with the null-object default.
    notify: NotificationController = NotificationController(NoopNotificationManager),
    // Multi-host fleet (spec §5): when present, the merged session list + per-row host badges + the
    // `All · <host…> · +` chip row + add-host + per-session/active-host routing come from here.
    // Default null = single-host: EVERY flow/op falls back to `app`, so the existing behavior and
    // the whole WorkspaceRootTest suite are unchanged.
    fleet: FleetState? = null,
) {
    val layout = ui.layout
    // Prefer the merged fleet flows when multi-host; else the single [app]'s. `fleet` is stable
    // across recompositions (remembered in Main), so the `?:` picks the same flow each time.
    val sessions by (fleet?.sessions ?: app.sessions).collectAsState()
    val messages by (fleet?.messages ?: app.messages).collectAsState()
    val agentState by (fleet?.agentState ?: app.agentState).collectAsState()
    val lastBySession = remember(messages) { messages.mapValues { it.value.lastOrNull() } }

    // Stable empty fallbacks so collectAsState never re-subscribes when fleet == null.
    val emptyHostViews = remember { MutableStateFlow<List<HostView>>(emptyList()) }
    val emptySessionHost = remember { MutableStateFlow<Map<String, String>>(emptyMap()) }
    val nullActiveHost = remember { MutableStateFlow<String?>(null) }
    val hostViews by (fleet?.hostViews ?: emptyHostViews).collectAsState()
    val sessionHost by (fleet?.sessionHost ?: emptySessionHost).collectAsState()
    val activeHostId by (fleet?.activeHost ?: nullActiveHost).collectAsState()

    // The active host's app (host-global ops: spawn / archived / usage / LSP settings). Falls back
    // to [app] in single-host mode.
    val hostApp = fleet?.appForRecord(activeHostId) ?: fleet?.activeApp() ?: app
    // The app owning a given session (per-session ops: rename/kill/mute/detail). Single-host → [app].
    val appFor: (String) -> DesktopAppState = { id -> fleet?.appFor(id) ?: app }

    // Host filter chip selection (recordId, or null = All) + the add-host overlay flag.
    var hostFilter by remember { mutableStateOf<String?>(null) }
    var addHostOpen by remember { mutableStateOf(false) }

    val focused = LocalWindowInfo.current.isWindowFocused

    // New-Session launcher (M4a Task 5): Ctrl+N (workspaceShortcuts below), the rail `+`
    // (SessionsRail/SessionListPanel — already wired to onNewSession) and Main's File menu item
    // (which flips ui.launcherOpen directly, since WorkspaceUiState is shared with Main) all reach
    // the SAME overlay via ui.launcherOpen.
    val onNewSession: () -> Unit = { ui.openLauncher() }

    // Scope for fire-and-forget overlay actions (e.g. the archived Resume POST) that must outlive
    // the overlay's composition — it closes the instant Resume is tapped.
    val overlayScope = rememberCoroutineScope()

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
    // "viewing" tracker keys off (session id, visible)). ALSO clears this session's notification
    // cooldown (M5-3) the moment it becomes actively viewed (selected AND focused), so the NEXT
    // reply after the user looks away again notifies immediately rather than waiting out a stale
    // dedup window from before they opened it.
    LaunchedEffect(ui.selectedId, focused) {
        // Route viewing presence to the OWNING host (fleet) so only that broker treats the chat as
        // foreground; single-host falls back to [app].
        if (fleet != null) fleet.updateViewing(ui.selectedId, focused) else app.updateViewing(ui.selectedId, focused)
        val sid = ui.selectedId
        if (sid != null && focused) notify.onSessionFocused(sid)
    }

    // M5-3: observe live agent replies and decide whether to raise a tray notification. Keyed on
    // [focused] (not Unit) so the collector RELAUNCHES with a freshly captured `focused` whenever
    // it changes — `LocalWindowInfo.current` cannot be re-read from inside a plain suspend
    // `collect{}` body (it needs an active Composer). `ui.selectedId`/`app.sessions.value` don't
    // have that problem (plain State/StateFlow reads valid from anywhere) so those ARE read live,
    // fresh per event, from inside the long-running collector below.
    LaunchedEffect(focused) {
        (fleet?.agentReplies ?: app.agentReplies).collect { event ->
            val target = (fleet?.sessions ?: app.sessions).value.firstOrNull { it.id == event.session }
            notify.onAgentReply(
                entry = event.entry,
                session = event.session,
                sessionName = target?.name ?: event.session,
                selectedId = ui.selectedId,
                windowFocused = focused,
                muted = target?.mute ?: false,
            )
        }
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
                // Gate the pane/sidebar shortcuts (Ctrl+B/L/E/T/D) OFF while ANY full-pane overlay
                // (launcher or archived) is up: it's modal, so a chord it leaves unhandled must NOT
                // bubble here and silently mutate the layout behind it (sidebar collapse / pane
                // toggles the user can't see). Each overlay handles its own Escape; Ctrl+N is
                // idempotent and reopening an already-open launcher is a no-op, so dropping it here
                // too costs nothing. `ui.overlayOpen` is the single gate for every overlay.
                .then(if (ui.overlayOpen || addHostOpen) Modifier else Modifier.workspaceShortcuts(layout, ui.selectedId, onNewSession)),
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
                        // Per-session ops route to the OWNING host (multi-host); single-host → [app].
                        onRename = { id, name -> appFor(id).rename(id, name) },
                        onKill = { id -> appFor(id).kill(id) { if (ui.selectedId == id) ui.selectedId = null } },
                        onMute = { id, muted -> appFor(id).setMute(id, muted) },
                        onNewSession = onNewSession,
                        // Fleet badges/chips (multi-host only; empty in single-host mode).
                        hosts = hostViews,
                        sessionHost = sessionHost,
                        hostFilter = hostFilter,
                        onSelectHostFilter = { hostFilter = it },
                        onAddHost = { addHostOpen = true },
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
                        // Route the detail (chat/editor/terminal/display) to the session's OWNING
                        // host in multi-host mode; single-host → [app].
                        app = appFor(session.id),
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
                        // SM_DICTATE hook (Main.kt) delivery: hand the pending transcribe request to
                        // the SessionDetail whose id matches.
                        externalDictate = ui.externalDictate?.takeIf { it.first == session.id }?.second,
                        onExternalDictateConsumed = { ui.externalDictate = null },
                        // Overflow ⋮ "Usage" row (M4f): the SAME ui.openUsage() the File ▸
                        // "Usage…" menu item calls.
                        onUsage = { ui.openUsage() },
                        // Overflow ⋮ "Editor / LSP…" row (M4g-4): the SAME ui.openLspSettings()
                        // the File ▸ "Editor / LSP…" menu item calls.
                        onLspSettings = { ui.openLspSettings() },
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
                        // Host-global lookups + spawn target the ACTIVE host (`hostApp`); the host
                        // picker below switches it. Single-host → [app].
                        loadProjects = { hostApp.listProjects() },
                        validatePath = { hostApp.validatePath(it) },
                        loadModels = { hostApp.launcherModels(it) },
                        loadReasoningLevels = { a, m -> hostApp.launcherReasoning(a, m) },
                        loadRepoInfo = { hostApp.launcherRepoInfo(it) },
                        transcribeAudio = { bytes, name -> hostApp.transcribeAudio(null, bytes, name)?.text },
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
                            val id = hostApp.createSessionWithFirstMessage(
                                workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch,
                            )
                            if (id == null) {
                                throw IllegalStateException(
                                    "Couldn't create the session — check the working directory and try again.",
                                )
                            }
                            ui.selectedId = id
                            hostApp.sendMessage(id, text, hostApp.consumeFirstUploads(id))
                            ui.launcherOpen = false
                        },
                        // Multi-host: pick which broker to spawn on (hidden with one host).
                        hosts = hostViews,
                        selectedHost = activeHostId,
                        onSelectHost = { fleet?.setActiveHost(it) },
                    )
                }
            }

            // ── Archived-sessions: a FULL-PANE overlay above the workspace (M4e Task 2) ──
            // Same shape as the launcher overlay (a Box drawn last, over the still-mounted
            // workspace). The list is loaded from `app.archived()` each time the overlay opens
            // (not kept live — an archived list is a point-in-time snapshot); reset to empty on
            // close so a re-open always re-fetches. `archivedLoading` distinguishes "still fetching"
            // (spinner) from "resolved empty" (empty text), so a slow fetch never flashes the empty
            // state. Escape / back / Resume close it via onBack; Resume additionally kicks the
            // un-archive (the resumed session returns live via a WS frame — no snackbar, the
            // M4-polish gap).
            var archivedList by remember { mutableStateOf<List<ArchivedDto>>(emptyList()) }
            var archivedLoading by remember { mutableStateOf(false) }
            LaunchedEffect(ui.archivedOpen) {
                if (ui.archivedOpen) {
                    archivedLoading = true
                    archivedList = hostApp.archived()
                    archivedLoading = false
                } else {
                    archivedList = emptyList()
                    archivedLoading = false
                }
            }
            if (ui.archivedOpen) {
                Box(Modifier.fillMaxSize().testTag("archived_overlay")) {
                    ArchivedScreen(
                        archived = archivedList,
                        loading = archivedLoading,
                        home = home,
                        onBack = { ui.archivedOpen = false },
                        onResume = { id ->
                            // Fire-and-forget: kick the un-archive POST (its Boolean return is
                            // irrelevant to the UI — the resumed session returns via a WS frame),
                            // then close the overlay immediately.
                            overlayScope.launch { hostApp.resume(id) }
                            ui.archivedOpen = false
                        },
                        loadLogs = { hostApp.archivedLogs(it) },
                        forceOpenId = ui.forceArchivedOpenFor,
                        onForceOpenConsumed = { ui.forceArchivedOpenFor = null },
                    )
                }
            }

            // ── Usage: a FULL-PANE overlay above the workspace (M4f Task 2) ──
            // Same shape as the launcher/archived overlays. `app.usage()` is loaded fresh each time
            // the overlay opens (a point-in-time snapshot, not kept live) and reset to null on
            // close so a re-open always re-fetches; `usageLoading` distinguishes "still fetching"
            // from "resolved null" the same way `archivedLoading` does. A successful redeem
            // (`code == "reset"`) swaps the codex slice in place so the card's numbers move without
            // a full re-fetch; any other code just surfaces CodexUsageCard's own inline note.
            var usageData by remember { mutableStateOf<UsageResponse?>(null) }
            var usageLoading by remember { mutableStateOf(false) }
            LaunchedEffect(ui.usageOpen) {
                if (ui.usageOpen) {
                    usageLoading = true
                    usageData = hostApp.usage()
                    usageLoading = false
                } else {
                    usageData = null
                    usageLoading = false
                }
            }
            if (ui.usageOpen) {
                // Self-focusing (unlike the launcher/archived overlay Boxes, which rely on an inner
                // field grabbing focus): UsageScreen has no text field a user would naturally focus
                // first, and Compose's onPreviewKeyEvent only fires along the path to whatever node
                // currently holds focus. Claiming focus on THIS Box on open makes it that path's
                // leaf, so Escape works the instant the overlay opens — not just after some other
                // interaction happens to move focus into it.
                val usageFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { usageFocus.requestFocus() } }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("usage_overlay")
                        .focusRequester(usageFocus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                ui.usageOpen = false
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    UsageScreen(
                        usage = usageData,
                        loading = usageLoading,
                        onBack = { ui.usageOpen = false },
                        onRedeem = {
                            val r = hostApp.redeemCodexReset()
                            if (r?.code == "reset" && r.codex != null) {
                                usageData = usageData?.copy(codex = r.codex)
                            }
                            r
                        },
                    )
                }
            }

            // ── LSP settings: a FULL-PANE overlay above the workspace (M4g-4 Task 3) ──
            // Same shape as the launcher/archived/usage overlays, but UNLIKE them the SCREEN itself
            // owns its server-list load/toggle/install/add/remove state (LspSettingsScreen's own
            // LaunchedEffect(Unit) — mirrors Android's EditorLspSection, since toggle/install/add/
            // remove all need to mutate the list in place, unlike Usage's single redeem-swap).
            // WorkspaceRoot only supplies the app.lsp* lambdas + the live app.lspInstallLog/
            // app.lspInstallDone StateFlows (folded from lsp_install_progress/lsp_install_done
            // frames by DesktopAppState) — because the composable is torn down + rebuilt each time
            // ui.lspSettingsOpen flips off/on, a re-open always re-fetches (same net effect as
            // Usage's explicit reset-to-null-on-close).
            if (ui.lspSettingsOpen) {
                // Self-focusing (mirrors the Usage overlay, NOT the launcher/archived Boxes): this
                // screen has no text field a user would naturally focus first when it opens, so
                // Escape needs a focus owner from frame one.
                val lspFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { lspFocus.requestFocus() } }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("lsp_settings_overlay")
                        .focusRequester(lspFocus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                ui.lspSettingsOpen = false
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    LspSettingsScreen(
                        lspLoad = { hostApp.lspLoad() },
                        lspToggle = { id, enabled -> hostApp.lspToggle(id, enabled) },
                        lspInstall = { id -> hostApp.lspInstall(id) },
                        lspInstallLog = hostApp.lspInstallLog,
                        lspInstallDone = hostApp.lspInstallDone,
                        lspAddCustom = { args ->
                            hostApp.lspAddCustom(args.id, args.label, args.command, args.extensions, args.args, args.languageId, args.installCmd)
                        },
                        lspRemoveCustom = { id -> hostApp.lspRemoveCustom(id) },
                        onBack = { ui.lspSettingsOpen = false },
                    )
                }
            }

            // ── Add host: a FULL-PANE overlay above the workspace (multi-host, spec §3.4/§5) ──
            // Opened by the fleet chip row's `+`. Wired to the fleet's claim seams; a successful
            // add closes the overlay and jumps the filter to the new host so its (soon-arriving)
            // sessions are front-and-center. Only reachable when `fleet != null`.
            if (addHostOpen && fleet != null) {
                val addFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { addFocus.requestFocus() } }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("add_host_overlay")
                        .focusRequester(addFocus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                addHostOpen = false
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    AddHostScreen(
                        onBack = { addHostOpen = false },
                        defaultDeviceName = remember { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.ifBlank { null } ?: "This desktop" },
                        onClaim = { payload, name -> fleet.addHost(payload, name) },
                        onClaimByUrl = { url, name -> fleet.addHostByUrl(url, name) },
                        onAdded = { addHostOpen = false },
                    )
                }
            }
        }
    }
}
