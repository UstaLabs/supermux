// Root of the paired app shell. M1 Task 9 (this) is the workspace chrome: a collapsible,
// drag-resizable sidebar, the multi-pane SessionDetail with pane toggles, keyboard shortcuts, and
// UI-state persistence (ShellStateStore → ui-state.json).
//
// State that the menu bar (Main.kt) also needs — the ShellLayout + the selected session id —
// lives in a small [ShellUiState] holder created in Main and passed down here, so File/View
// menu actions and the in-app shortcuts drive the same state.
package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.supermux.net.PatchWorkspaceBody
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.ViewDto
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.toDto
import dev.supermux.workspace.chatSessionIds
import dev.supermux.desktop.host.AddHostScreen
import dev.supermux.desktop.host.FleetState
import dev.supermux.desktop.host.HostView
import dev.supermux.desktop.host.HostDot
import dev.supermux.desktop.notify.NoopNotificationManager
import dev.supermux.desktop.notify.NotificationController
import dev.supermux.desktop.session.ArchivedScreen
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.session.SessionLauncherScreen
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.settings.SettingsHub
import dev.supermux.desktop.update.AppUpdateBanner
import dev.supermux.desktop.update.AppUpdateScreen
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.usage.UsageScreen
import dev.supermux.net.ArchivedDto
import dev.supermux.net.UsageResponse
import dev.supermux.session.inferHomeDir
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sections of the Settings hub (left rail), in rail order.
 */
enum class SettingsSection(val label: String) {
    Agents("Agents"),
    Devices("Devices"),
    System("System"),
    GitHosting("Git hosting"),
    Proxies("Proxies"),
    /** PA name + soul.md — distinct from [PersonalAssistants] fleet and [Curator]. */
    Assistant("Identity"),
    /** Nightly ~/.mux curator schedule + run-now. */
    Curator("Curator"),
    Voice("Voice"),
    EditorLsp("Editor / LSP"),
    PersonalAssistants("Personal assistants"),
}

/**
 * Holder for the workspace UI state that both [AppShell] and the window MenuBar (Main.kt) act
 * on: the shared [ShellLayout] and the selected session id. Created once in Main (so the menu
 * can reach it), hydrated from [ShellStateStore] at startup.
 */
@Stable
class ShellUiState {
    val layout = ShellLayout()
    var selectedId by mutableStateOf<String?>(null)

    /**
     * Whether the New-Session launcher is showing in the **detail pane** (sidebar stays mounted).
     * Not a [DesktopRoute]: it is a side panel inside [DesktopRoute.Home], not a full-pane push.
     */
    var launcherOpen by mutableStateOf(false)
    /** When set, the launcher reopens this draft session (web /new?draft=). */
    var launcherDraftId by mutableStateOf<String?>(null)

    /**
     * Nav3 back stack — sole source of truth for full-pane destinations.
     * Always starts with [DesktopRoute.Home]; overlays are pushed with [navigate].
     */
    val backStack: SnapshotStateList<DesktopRoute> = mutableStateListOf(DesktopRoute.Home)

    /** Top of [backStack] (never null — Home is always present). */
    val currentRoute: DesktopRoute get() = backStack.lastOrNull() ?: DesktopRoute.Home

    /** True when launcher is open or any route is above Home (gates workspace shortcuts). */
    val overlayOpen: Boolean get() = launcherOpen || backStack.size > 1

    // Read-only views of the stack (for load effects / assertions). Open via [navigate] / open*.
    val archivedOpen: Boolean get() = currentRoute is DesktopRoute.Archived
    val usageOpen: Boolean get() = currentRoute is DesktopRoute.Usage
    val settingsOpen: Boolean get() = currentRoute is DesktopRoute.Settings
    val appUpdateOpen: Boolean get() = currentRoute is DesktopRoute.AppUpdate
    val lspSettingsOpen: Boolean
        get() = (currentRoute as? DesktopRoute.Settings)?.section == SettingsSection.EditorLsp
    val personalAssistantsOpen: Boolean
        get() = (currentRoute as? DesktopRoute.Settings)?.section == SettingsSection.PersonalAssistants

    /**
     * Settings rail section. When Settings is on the stack, reads/writes that route's [DesktopRoute.Settings.section];
     * when closed, remembers the last section for the next open (and for tests).
     */
    var settingsSection: SettingsSection
        get() = (currentRoute as? DesktopRoute.Settings)?.section ?: lastSettingsSection
        set(value) {
            lastSettingsSection = value
            val i = backStack.indexOfLast { it is DesktopRoute.Settings }
            if (i >= 0) backStack[i] = DesktopRoute.Settings(value)
        }
    private var lastSettingsSection by mutableStateOf(SettingsSection.Agents)

    /**
     * Push [route]. [DesktopRoute.Home] clears overlays; other routes replace any open overlay
     * (`[Home, route]`) and close the detail-pane launcher.
     */
    fun navigate(route: DesktopRoute) {
        when (route) {
            is DesktopRoute.Home -> popToHome()
            else -> {
                launcherOpen = false
                popToHome()
                if (route is DesktopRoute.Settings) lastSettingsSection = route.section
                backStack.add(route)
            }
        }
    }

    /** Pop one entry; false if already at Home. NavDisplay onBack. */
    fun goBack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    private fun popToHome() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Detail-pane new-session UI; also clears full-pane routes. */
    fun openLauncher(draftId: String? = null) {
        launcherDraftId = draftId
        launcherOpen = true
        navigate(DesktopRoute.Home)
    }

    fun selectSession(id: String) {
        selectedId = id
        launcherOpen = false
        launcherDraftId = null
    }

    // Menu / chrome conveniences → navigate
    fun openArchived() = navigate(DesktopRoute.Archived)
    fun openUsage() = navigate(DesktopRoute.Usage)
    fun openSettings(section: SettingsSection = SettingsSection.Agents) =
        navigate(DesktopRoute.Settings(section))
    fun openLspSettings() = openSettings(SettingsSection.EditorLsp)
    fun openPersonalAssistants() = openSettings(SettingsSection.PersonalAssistants)
    fun openAppUpdate() = navigate(DesktopRoute.AppUpdate)

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
     * One-shot "paste image from clipboard into the selected session's composer" request. Bumped by
     * Edit ▸ Paste image in the native MenuBar; the selected session's [DesktopComposer] runs the
     * same [launchPasteImages] path as Ctrl/Cmd+V / Attach ▸ Paste image, then clears the nonce.
     * Zero in normal operation.
     */
    var pasteImageRequestNonce by mutableStateOf(0L)

    /** Bump [pasteImageRequestNonce] so the selected composer's paste-image path runs once. */
    fun requestPasteImage() {
        pasteImageRequestNonce = pasteImageRequestNonce + 1
    }

    /**
     * One-shot "open this archived session's read-only transcript" request (an ARCHIVED session
     * id, not a live one), consumed by [dev.supermux.desktop.session.ArchivedScreen] — it seeds the
     * internal list⇄chat nav (`openedId`) so the matching row's read-only `ArchivedChatView` renders
     * without a click, the same way [forceFinishDialogFor] seeds the Finish dialog. Set by the
     * off-by-default `SM_ARCHIVED_OPEN` headless hook in Main.kt (which also [openArchived]s);
     * null in normal operation. Cleared once ArchivedScreen consumes it.
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
fun AppShell(
    app: DesktopAppState,
    ui: ShellUiState,
    store: ShellStateStore,
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
    /** Appearance mode shown in the sidebar theme toggle; toggled via [onToggleTheme]. */
    appearance: AppearanceMode = AppearanceMode.DARK,
    onToggleTheme: () -> Unit = {},
) {
    val layout = ui.layout
    // Prefer the merged fleet flows when multi-host; else the single [app]'s. `fleet` is stable
    // across recompositions (remembered in Main), so the `?:` picks the same flow each time.
    val sessions by (fleet?.sessions ?: app.sessions).collectAsState()
    // Design-review flag: SM_WORKSPACES=1 swaps the session sidebar for the workspace one.
    // Default OFF, so the shipping shell is unchanged until the row design is signed off.
    // Read once — an env var cannot change under a hot reload anyway.
    val workspaceSidebar = remember { System.getenv("SM_WORKSPACES")?.isNotBlank() == true }
    val workspaces by app.workspaces.collectAsState()
    // Shared across the sidebar and the layout host so a tab can drop onto a
    // workspace row (cross-workspace move).
    val tabDragState = remember { TabDragState() }
    var archivedForList by remember { mutableStateOf<List<dev.supermux.net.ArchivedDto>>(emptyList()) }
    LaunchedEffect(sessions, ui.selectedId) {
        // Refresh settled fold when the live list changes (settle/resume/snapshot).
        archivedForList = runCatching { app.archived() }.getOrDefault(emptyList())
    }
    val messages by (fleet?.messages ?: app.messages).collectAsState()
    val agentState by (fleet?.agentState ?: app.agentState).collectAsState()
    val lastRead by (fleet?.lastRead ?: app.lastRead).collectAsState()
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
    val activeHostSessions = remember(sessions, sessionHost, hostViews, activeHostId) {
        if (hostViews.size >= 2 && activeHostId != null) {
            sessions.filter { sessionHost[it.id] == activeHostId }
        } else {
            sessions
        }
    }
    // The app owning a given session (per-session ops: rename/kill/mute/detail). Single-host → [app].
    val appFor: (String) -> DesktopAppState = { id -> fleet?.appFor(id) ?: app }

    // Host filter chip selection (recordId, or null = All) + the add-host overlay flag.
    var hostFilter by remember { mutableStateOf<String?>(null) }
    var addHostOpen by remember { mutableStateOf(false) }

    val focused = LocalWindowInfo.current.isWindowFocused

    // New-Session launcher (M4a Task 5): Ctrl+N (shellShortcuts below), the rail `+`
    // (SessionsRail/SessionListPanel — already wired to onNewSession) and Main's File menu item
    // (which flips ui.launcherOpen directly, since ShellUiState is shared with Main) all reach
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
    // Live layout of the open workspace (tab switches update this before the broker PATCH lands)
    // so Viewing frames track the active view of each group immediately. Only used when
    // SM_WORKSPACES is on; null otherwise — classic path ignores it.
    var workspaceViewingLayout by remember { mutableStateOf<LayoutNode?>(null) }
    var workspaceViewingViews by remember { mutableStateOf<Map<String, ViewDto>>(emptyMap()) }

    // Re-assert viewing presence whenever the selection or window focus changes (broker per-device
    // "viewing" tracker). ALSO clears this session's notification cooldown (M5-3) the moment it
    // becomes actively viewed (selected AND focused), so the NEXT reply after the user looks away
    // again notifies immediately rather than waiting out a stale dedup window.
    //
    // SM_WORKSPACES=1: one Viewing frame per visible chat view (active view of each group).
    // SM_WORKSPACES unset: classic single selected session — behaviour EXACTLY as before.
    LaunchedEffect(ui.selectedId, focused, workspaceSidebar, workspaceViewingLayout, workspaceViewingViews) {
        ui.selectedId?.let { sessionHost[it] }?.let { fleet?.setActiveHost(it) }
        if (workspaceSidebar) {
            // Derive visible chat sessions from the open workspace's layout tree — never send a
            // workspace id as a session. Phase 3 kept ui.selectedId as a SESSION id, so the
            // classic path below was never broken; this multi path is for two chats on screen.
            val visibleChatIds = if (focused) {
                val tree = workspaceViewingLayout
                if (tree != null) {
                    collectActiveViewIds(tree).mapNotNull { vid ->
                        workspaceViewingViews[vid]?.chatSessionId()
                    }
                } else {
                    // Fallback: the selected session alone (sidebar still stores a session id).
                    listOfNotNull(ui.selectedId)
                }
            } else {
                emptyList()
            }
            if (fleet != null) {
                // Fleet still has the classic single-session API; send the first visible (or null).
                fleet.updateViewing(visibleChatIds.firstOrNull(), focused)
            } else {
                app.updateViewingSessions(visibleChatIds, focused)
            }
            for (sid in visibleChatIds) notify.onSessionFocused(sid)
        } else {
            // Classic path — unchanged when SM_WORKSPACES is unset.
            if (fleet != null) fleet.updateViewing(ui.selectedId, focused) else app.updateViewing(ui.selectedId, focused)
            val sid = ui.selectedId
            if (sid != null && focused) notify.onSessionFocused(sid)
        }
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
    // ShellUiState.reconcileSessions) and prune stale drafts.
    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) return@LaunchedEffect // first Snapshot not in yet — don't wipe state
        val live = sessions.mapTo(mutableSetOf()) { it.id }
        ui.reconcileSessions(live)
        drafts.keys.filterNot { it in live }.forEach(drafts::remove)
    }

    // Debounced persistence, observed through snapshotFlow rather than a composition-scope
    // layout.snapshot() read — the latter would subscribe the whole AppShell to every
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
                .then(if (ui.overlayOpen || addHostOpen) Modifier else Modifier.shellShortcuts(layout, ui.selectedId, onNewSession)),
        ) {
            Column(Modifier.fillMaxSize()) {
            AppUpdateBanner(onOpenPage = { ui.openAppUpdate() })
            Box(Modifier.weight(1f).fillMaxWidth()) {
            // Re-fetch when the matching route is on top of the Nav3 stack.
            var archivedList by remember { mutableStateOf<List<ArchivedDto>>(emptyList()) }
            var archivedLoading by remember { mutableStateOf(false) }
            LaunchedEffect(ui.currentRoute, activeHostId) {
                if (ui.currentRoute is DesktopRoute.Archived) {
                    archivedLoading = true
                    archivedList = hostApp.archived()
                    archivedLoading = false
                } else {
                    archivedList = emptyList()
                    archivedLoading = false
                }
            }
            var usageData by remember { mutableStateOf<UsageResponse?>(null) }
            var usageLoading by remember { mutableStateOf(false) }
            LaunchedEffect(ui.currentRoute, activeHostId) {
                if (ui.currentRoute is DesktopRoute.Usage) {
                    usageLoading = true
                    usageData = hostApp.usage()
                    usageLoading = false
                } else {
                    usageData = null
                    usageLoading = false
                }
            }

            // Settings dirty-soul close (SettingsHub); Esc / NavDisplay onBack honor it.
            var settingsTryClose by remember { mutableStateOf<(() -> Unit)?>(null) }
            val fullPaneOverlay = remember { FullPaneOverlaySceneStrategy<DesktopRoute>() }

            NavDisplay(
                backStack = ui.backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = {
                    when (ui.currentRoute) {
                        is DesktopRoute.Settings -> settingsTryClose?.invoke() ?: run { ui.goBack() }
                        else -> ui.goBack()
                    }
                },
                sceneStrategies = listOf(fullPaneOverlay),
                entryProvider = entryProvider {
                    entry<DesktopRoute.Home> {
                Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    // ── Sidebar: collapsed rail, or the full list ──
                    if (layout.sidebarCollapsed) {
                        SessionsRail(
                            sessions = sessions,
                            selectedId = ui.selectedId,
                            agentState = agentState,
                            onSelect = { ui.selectSession(it) },
                            onExpand = { layout.sidebarCollapsed = false },
                            onNewSession = onNewSession,
                            lastBySession = lastBySession,
                            lastRead = lastRead,
                        )
                    } else if (workspaceSidebar) {
                        // Design-review swap only: the sidebar lists workspaces, but selection
                        // semantics are UNCHANGED — ui.selectedId is still a SESSION id, and the
                        // detail pane below is untouched. Opening a workspace row selects that
                        // workspace's first chat session, so everything downstream behaves exactly
                        // as it does with the session list.
                        //
                        // The full swap (spec §13.6 + a workspace-scoped selection model) is Phase 3
                        // Task 5 onward. This is the minimum needed to look at the rows in context.
                        val wsOf = { wid: String -> workspaces.firstOrNull { it.id == wid } }
                        WorkspaceListPanel(
                            workspaces = workspaces,
                            home = home,
                            activeId = workspaces.firstOrNull { w -> w.chatSessionIds().contains(ui.selectedId) }?.id,
                            onOpen = { wid -> wsOf(wid)?.chatSessionIds()?.firstOrNull()?.let { ui.selectSession(it) } },
                            sessions = sessions,
                            lastBySession = lastBySession,
                            lastRead = lastRead,
                            agentState = agentState,
                            sessionNames = remember(sessions) { sessions.associate { it.id to it.name } },
                            sessionRoles = remember(sessions) { sessions.associate { it.id to it.role } },
                            onOpenSession = { _, sid -> ui.selectSession(sid) },
                            // Workspace-scoped ops resolve the primary/chat sessions, then route
                            // to the OWNING host (multi-host); single-host → [app]. Same targets as
                            // the SessionListPanel branch below.
                            onRename = { wid, name ->
                                val sid = wsOf(wid)?.primarySessionId ?: wsOf(wid)?.chatSessionIds()?.firstOrNull()
                                if (sid != null) appFor(sid).rename(sid, name)
                            },
                            onKill = { wid ->
                                // Archive the WORKSPACE, not each chat session. The broker archives
                                // the sessions and the workspace row together and broadcasts
                                // workspace_removed. Killing sessions one by one leaves the row
                                // behind whenever they are already archived — which is exactly what
                                // made an rpc-worker workspace look impossible to archive.
                                if (wsOf(wid)?.chatSessionIds()?.contains(ui.selectedId) == true) ui.selectedId = null
                                app.archiveWorkspace(wid)
                            },
                            onMute = { wid, muted ->
                                val sid = wsOf(wid)?.primarySessionId ?: wsOf(wid)?.chatSessionIds()?.firstOrNull()
                                if (sid != null) appFor(sid).setMute(sid, muted)
                            },
                            onNewSession = onNewSession,
                            archived = archivedForList,
                            onResume = { id ->
                                overlayScope.launch {
                                    appFor(id).resume(id)
                                    archivedForList = runCatching { app.archived() }.getOrDefault(emptyList())
                                }
                            },
                            onOpenDraft = { id -> ui.openLauncher(draftId = id) },
                            onReorder = { ids ->
                                // Workspace reorder: map to primary session ids when the broker
                                // only knows session order (Phase 3 may grow a workspace reorder API).
                                val sessionIds = ids.mapNotNull { wid ->
                                    wsOf(wid)?.primarySessionId ?: wsOf(wid)?.chatSessionIds()?.firstOrNull()
                                }
                                if (sessionIds.isNotEmpty()) app.reorderSessions(sessionIds)
                            },
                            hosts = hostViews,
                            sessionHost = sessionHost,
                            hostFilter = hostFilter,
                            onSelectHostFilter = { hostFilter = it },
                            onAddHost = { addHostOpen = true },
                            onUsage = { ui.openUsage() },
                            onSettings = { ui.openSettings() },
                            onDevices = { ui.openSettings(SettingsSection.Devices) },
                            appearance = appearance,
                            onToggleTheme = onToggleTheme,
                            tabDragState = tabDragState,
                            modifier = Modifier.width(layout.sidebarWidth).fillMaxHeight(),
                        )
                    } else {
                        SessionListPanel(
                            sessions = sessions,
                            home = home,
                            activeId = ui.selectedId,
                            onOpen = { ui.selectSession(it) },
                            lastBySession = lastBySession,
                            lastRead = lastRead,
                            agentState = agentState,
                            // Per-session ops route to the OWNING host (multi-host); single-host → [app].
                            onRename = { id, name -> appFor(id).rename(id, name) },
                            onKill = { id -> appFor(id).kill(id) { if (ui.selectedId == id) ui.selectedId = null } },
                            onMute = { id, muted -> appFor(id).setMute(id, muted) },
                            onNewSession = onNewSession,
                            archived = archivedForList,
                            onResume = { id -> overlayScope.launch { appFor(id).resume(id); archivedForList = runCatching { app.archived() }.getOrDefault(emptyList()) } },
                            onOpenDraft = { id -> ui.openLauncher(draftId = id) },
                            onReorder = { ids -> app.reorderSessions(ids) },
                            // Fleet badges/chips (multi-host only; empty in single-host mode).
                            hosts = hostViews,
                            sessionHost = sessionHost,
                            hostFilter = hostFilter,
                            onSelectHostFilter = { hostFilter = it },
                            onAddHost = { addHostOpen = true },
                            // Footer: usage/devices/settings match File menu; theme toggles [appearance].
                            onUsage = { ui.openUsage() },
                            onSettings = { ui.openSettings() },
                            onDevices = { ui.openSettings(SettingsSection.Devices) },
                            appearance = appearance,
                            onToggleTheme = onToggleTheme,
                            modifier = Modifier.width(layout.sidebarWidth).fillMaxHeight(),
                        )
                    }

                    // ── Detail: launcher (detail-pane only), SessionDetail, or empty prompt ──
                    // New-session is a *side* panel — sidebar + resizer stay mounted. Other modals
                    // (archived / usage / settings) remain full-workspace overlays below.
                    val id = ui.selectedId
                    val session = id?.let { sel -> sessions.firstOrNull { it.id == sel } }
                        // Close-view candidate (Task 7 wires the confirm dialog). Set by
                        // LayoutHost onCloseView; never ends work by itself.
                        var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }
    // The new-session composer, usable in TWO places: as the full-pane launcher
    // and inside a workspace tab (a chat view that has no session yet). Extracted
    // so both render exactly the same thing — the tab is not a reimplementation.
    //
    // [onCreated] receives the new session id; the caller decides what that means
    // (select it, or bind the pending view to it).
    val launcherPane: @Composable (onBack: () -> Unit, onCreated: (String) -> Unit) -> Unit =
        { onBack, onCreated ->
                    SessionLauncherScreen(
                        sessions = activeHostSessions,
                        home = home,
                        onBack = onBack,
                        lastBySession = lastBySession,
                        // Host-global lookups + spawn target the ACTIVE host (`hostApp`);
                        // the host picker below switches it. Single-host → [app].
                        loadProjects = { hostApp.listProjects() },
                        validatePath = { hostApp.validatePath(it) },
                        loadModels = { hostApp.launcherModels(it) },
                        loadReasoningLevels = { a, m -> hostApp.launcherReasoning(a, m) },
                        loadRepoInfo = { wd, fetch -> hostApp.launcherRepoInfo(wd, fetch) },
                        transcribeAudio = { bytes, name -> hostApp.transcribeAudio(null, bytes, name)?.text },
                        loadPrefs = { launcherStore.loadPrefs() },
                        onPrefsChange = { launcherStore.savePrefs(it) },
                        loadDraft = { launcherStore.loadDraft() },
                        onDraftChange = { launcherStore.saveDraft(it) },
                        onClearDraft = { launcherStore.clearDraft() },
                        // Spawn → select + send the first message → close. A null id is
                        // surfaced by THROWING — SessionLauncherScreen's doSubmit try/catch
                        // turns any thrown message into the inline launcher_error text.
                        onSubmit = { workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch, replaceDraftId ->
                            val newId = hostApp.createSessionWithFirstMessage(
                                workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch,
                                replaceDraftId = replaceDraftId,
                            )
                            if (newId == null) {
                                throw IllegalStateException(
                                    "Couldn't create the session — check the working directory and try again.",
                                )
                            }
                            ui.selectedId = newId
                            hostApp.sendMessage(newId, text, hostApp.consumeFirstUploads(newId))
                            ui.launcherOpen = false; ui.launcherDraftId = null
                        },
                        onSaveDraft = { workdir, agent, model, reasoningLevel, text, replaceDraftId ->
                            hostApp.createDraftSession(
                                workdir, agent, model, text,
                                reasoningLevel = reasoningLevel,
                                replaceDraftId = replaceDraftId,
                            )
                        },
                        initialDraftId = ui.launcherDraftId,
                        initialDraft = ui.launcherDraftId?.let { dId -> sessions.find { it.id == dId } },
                        hosts = hostViews,
                        selectedHost = activeHostId,
                        onSelectHost = { fleet?.setActiveHost(it) },
                        loadAgents = { hostApp.launcherAgents() },
                        loadForges = { hostApp.listForges() },
                        searchForge = { hostApp.searchForge(it) },
                        cloneForge = { cid, owner, name -> hostApp.cloneForge(cid, owner, name) },
                        createLocalRepo = { hostApp.createLocalRepo(it) },
                        createForge = { cid, name -> hostApp.createForge(cid, name) },
                    )
        }
                    when {
                        ui.launcherOpen -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("launcher_overlay")
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                            ui.launcherOpen = false; ui.launcherDraftId = null
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            ) {
                                launcherPane(
                                    { ui.launcherOpen = false; ui.launcherDraftId = null },
                                    { newId -> ui.selectedId = newId; ui.launcherOpen = false; ui.launcherDraftId = null },
                                )
                            }
                        }
                        session == null -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("select a session", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        workspaceSidebar -> {
                            val current = workspaces.firstOrNull { w ->
                                w.id == id || w.chatSessionIds().contains(id)
                            }
                            if (current == null) {
                                LaunchedEffect(Unit) {
                                    workspaceViewingLayout = null
                                    workspaceViewingViews = emptyMap()
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .testTag("workspace_welcome"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("select a workspace", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                val serverTree = current.layout.toDomainOrNull()
                                    ?: LayoutNode.Group(id = "g", viewIds = emptyList())
                                // Local tree for drag responsiveness; PATCH is debounced (below).
                                var localLayout by remember(current.id) { mutableStateOf(serverTree) }
                                var layoutDirty by remember(current.id) { mutableStateOf(false) }
                                // Adopt broker updates when the user is not mid-drag.
                                LaunchedEffect(current.layout) {
                                    if (!layoutDirty) {
                                        localLayout = current.layout.toDomainOrNull()
                                            ?: LayoutNode.Group(id = "g", viewIds = emptyList())
                                    }
                                }
                                // Debounce layout writes: a splitter drag fires on every pointer move.
                                // One PATCH per move would flood the broker and every peer device.
                                // Hold the tree in local state, render from local, PATCH at most every
                                // ~300ms (trailing write also covers drag end).
                                LaunchedEffect(localLayout, layoutDirty) {
                                    if (!layoutDirty) return@LaunchedEffect
                                    delay(300)
                                    runCatching {
                                        app.api.patchWorkspace(
                                            current.id,
                                            PatchWorkspaceBody(layout = localLayout.toDto()),
                                        )
                                    }
                                    layoutDirty = false
                                }
                                val viewsById = remember(current) { current.views.associateBy { it.id } }
                                val sessionNames = remember(sessions) { sessions.associate { it.id to it.name } }
                                // Feed the viewing LaunchedEffect so a tab switch re-asserts
                                // Viewing frames for only the active chat of each group.
                                LaunchedEffect(localLayout, viewsById) {
                                    workspaceViewingLayout = localLayout
                                    workspaceViewingViews = viewsById
                                }
                                LayoutHost(
                                    layout = localLayout,
                                    titleFor = { vid -> viewsById[vid]?.let { viewTitle(it) } ?: "view" },
                                    onCloseView = { closeCandidate = viewsById[it] },
                                    onLayoutChange = { next ->
                                        localLayout = next
                                        layoutDirty = true
                                    },
                                    // "+" on the tab strip → pick a kind → it opens as a new tab in
                                    // THAT group. A chat needs an agent, so it goes through the
                                    // launcher (spec §9.2); the other kinds are pure views and are
                                    // created straight away.
                                    onAddView = { groupId, kind ->
                                        // Every kind, chat included, becomes a TAB in this group.
                                        // A chat tab starts as the new-session composer and binds to
                                        // its session on first send — the pane is never replaced.
                                        app.addWorkspaceView(current.id, kind, groupId)
                                    },
                                    dragState = tabDragState,
                                    onMoveToWorkspace = { viewId, toWs ->
                                        if (toWs != current.id) {
                                            app.moveViewToWorkspace(viewId, toWs)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize().testTag("workspace_layout_host"),
                                ) { viewId ->
                                    val v = viewsById[viewId]
                                    if (v != null && v.kind == "chat" && v.chatSessionId() == null) {
                                        // A chat tab with no session yet: render the SAME new-session
                                        // composer the full-pane launcher uses, inside this tab. The
                                        // pane and sidebar do not move. On send we create the session
                                        // in THIS workspace and bind the view to it, so the very same
                                        // tab becomes the conversation.
                                        launcherPane(
                                            { app.closeWorkspaceView(current.id, v.id) },
                                            { newId ->
                                                app.bindChatView(current.id, v.id, newId)
                                                ui.selectedId = newId
                                            },
                                        )
                                    } else if (v != null) {
                                        ViewHost(
                                            view = v,
                                            workspaceId = current.id,
                                            workdir = current.workdir,
                                            app = appFor(v.chatSessionId() ?: current.primarySessionId ?: session?.id ?: ""),
                                            drafts = drafts,
                                            primarySessionId = current.primarySessionId,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                // Spec §9.3: a close that ends work asks first. Editor skips the dialog.
                                // NOT the Finish flow — one question, two buttons (Close / Cancel).
                                closeCandidate?.let { v ->
                                    if (!v.closeNeedsConfirmation()) {
                                        LaunchedEffect(v.id) {
                                            runCatching { app.api.closeView(v.workspaceId, v.id) }
                                            closeCandidate = null
                                        }
                                    } else {
                                        CloseViewDialog(
                                            view = v,
                                            sessionNames = sessionNames,
                                            onDismiss = { closeCandidate = null },
                                            onConfirm = {
                                                overlayScope.launch {
                                                    runCatching { app.api.closeView(v.workspaceId, v.id) }
                                                    closeCandidate = null
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        session == null -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("select a session", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> {
                            SessionDetail(
                                // Route the detail to the session's OWNING host in multi-host mode.
                                app = appFor(session.id),
                                session = session,
                                agent = agentState[session.id],
                                layout = layout,
                                draft = drafts[session.id] ?: "",
                                onDraftChange = { drafts[session.id] = it },
                                externalOpen = ui.externalOpen?.takeIf { it.first == session.id }?.second,
                                onExternalOpenConsumed = { ui.externalOpen = null },
                                forceFinishDialog = ui.forceFinishDialogFor == session.id,
                                onForceFinishConsumed = { ui.forceFinishDialogFor = null },
                                forceGitMenu = ui.forceGitMenuFor?.takeIf { it.first == session.id }?.second,
                                onForceGitMenuConsumed = { ui.forceGitMenuFor = null },
                                forceLinksMenu = ui.forceLinksMenuFor == session.id,
                                onForceLinksMenuConsumed = { ui.forceLinksMenuFor = null },
                                forceOverflowMenu = ui.forceOverflowFor == session.id,
                                onForceOverflowMenuConsumed = { ui.forceOverflowFor = null },
                                externalAttach = ui.externalAttach?.takeIf { it.first == session.id }?.second,
                                onExternalAttachConsumed = { ui.externalAttach = null },
                                externalDictate = ui.externalDictate?.takeIf { it.first == session.id }?.second,
                                onExternalDictateConsumed = { ui.externalDictate = null },
                                pasteImageRequestNonce = ui.pasteImageRequestNonce,
                                onPasteImageRequestConsumed = { ui.pasteImageRequestNonce = 0L },
                                onUsage = { ui.openUsage() },
                                onLspSettings = { ui.openLspSettings() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // Resize + collapse OVERLAY on the sidebar seam (not a Row child — zero layout width,
                // paints above both panes so the chip stays visible). Sits next to the detail-pane
                // launcher, not over a full-workspace modal.
                if (!layout.sidebarCollapsed) {
                    SidebarDivider(
                        onDragDelta = { d -> layout.setSidebarWidth(layout.sidebarWidth + d) },
                        onCollapse = { layout.sidebarCollapsed = true },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = layout.sidebarWidth - SidebarDividerCenterOffset)
                            .zIndex(20f),
                    )
                }


                }
                    }

                    entry<DesktopRoute.Settings>(
                        metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                    ) { route ->
                        val settingsFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { settingsFocus.requestFocus() } }
                        val overlayTag = when (route.section) {
                            SettingsSection.EditorLsp -> "lsp_settings_overlay"
                            SettingsSection.PersonalAssistants -> "personal_assistants_overlay"
                            else -> "settings_overlay"
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag(overlayTag)
                                .focusRequester(settingsFocus)
                                .focusable()
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                        settingsTryClose?.invoke() ?: run { ui.goBack() }
                                        true
                                    } else false
                                },
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                HostScopeBar(hostViews, activeHostId) { fleet?.setActiveHost(it) }
                                Box(Modifier.weight(1f)) {
                                    androidx.compose.runtime.key(activeHostId, route.section) {
                                        SettingsHub(
                                            section = route.section,
                                            onSectionChange = { ui.settingsSection = it },
                                            onBack = { ui.goBack() },
                                            onRegisterCloseHandler = { settingsTryClose = it },
                                            agentStatuses = { hostApp.agentStatuses() },
                                            agentStartLogin = { hostApp.startAgentLogin(it) },
                                            agentPollLogin = { hostApp.agentLoginState(it) },
                                            agentSendCode = { kind, code -> hostApp.sendAgentLoginCode(kind, code) },
                                            agentCancelLogin = { hostApp.cancelAgentLogin(it) },
                                            agentSaveSecret = { kind, value -> hostApp.saveAgentSecret(kind, value) },
                                            agentStartInstall = { hostApp.startAgentInstall(it) },
                                            agentPollInstall = { hostApp.agentInstallState(it) },
                                            openCodeProviders = { hostApp.openCodeProviders() },
                                            openCodeSetKey = { id, k -> hostApp.setOpenCodeKey(id, k) },
                                            openCodeStartOAuth = { id, method -> hostApp.startOpenCodeOAuth(id, method) },
                                            openCodeFinishOAuth = { id, method, code -> hostApp.finishOpenCodeOAuth(id, method, code) },
                                            devicesLoad = { hostApp.devices() },
                                            deviceAdd = { name -> hostApp.addDevice(name) },
                                            deviceRevoke = { name -> hostApp.revokeDevice(name) },
                                            updateStatus = { hostApp.updateStatus() },
                                            checkUpdate = { hostApp.checkUpdate() },
                                            runUpdate = { hostApp.runUpdate() },
                                            restartBroker = { hostApp.restartBroker() },
                                            proxiesLoad = { hostApp.proxiesForSettings() },
                                            proxySessionNames = { hostApp.sessions.value.map { it.name } },
                                            proxyCreate = { session, port, domain ->
                                                hostApp.createProxy(session, port, domain)
                                            },
                                            proxySetPublic = { domain, isPublic ->
                                                hostApp.setProxyPublic(domain, isPublic)
                                            },
                                            proxyRemove = { domain -> hostApp.removeProxy(domain) },
                                            assistantLoad = { hostApp.assistantLoad() },
                                            assistantSave = { paName, soul -> hostApp.assistantSave(paName, soul) },
                                            curatorLoad = { hostApp.curatorSettings() },
                                            curatorSave = { enabled, hour, minute, agent, model, reasoning ->
                                                hostApp.saveCurator(enabled, hour, minute, agent, model, reasoning)
                                            },
                                            curatorRunNow = { hostApp.runCuratorNow() },
                                            curatorLoadModels = { agent -> hostApp.launcherModels(agent) },
                                            curatorLoadReasoning = { agent, model ->
                                                hostApp.launcherReasoning(agent, model)
                                            },
                                            voiceLoadConfig = { hostApp.appConfig() },
                                            voiceLoadModels = { family -> hostApp.launcherModels(family) },
                                            voiceSaveStt = { engine -> hostApp.saveVoiceStt(engine) },
                                            voiceSaveTts = { engine -> hostApp.saveVoiceTts(engine) },
                                            voiceSaveCleanup = { engine, model ->
                                                hostApp.saveVoiceCleanup(engine, model)
                                            },
                                            glossaryLoad = { hostApp.fetchGlossary() },
                                            glossarySave = { terms -> hostApp.updateGlossary(terms) },
                                            lspLoad = { hostApp.lspLoad() },
                                            lspToggle = { id, enabled -> hostApp.lspToggle(id, enabled) },
                                            lspInstall = { id -> hostApp.lspInstall(id) },
                                            lspInstallLog = hostApp.lspInstallLog,
                                            lspInstallDone = hostApp.lspInstallDone,
                                            lspAddCustom = { args ->
                                                hostApp.lspAddCustom(args.id, args.label, args.command, args.extensions, args.args, args.languageId, args.installCmd)
                                            },
                                            lspRemoveCustom = { id -> hostApp.lspRemoveCustom(id) },
                                            paLoad = { hostApp.personalAssistants() },
                                            paCreate = { name, agent, focus -> hostApp.createPersonalAssistant(name, agent, focus) },
                                            paKill = { hostApp.killPersonalAssistant(it) },
                                            forgesLoad = { hostApp.forgesLoad() },
                                            forgeAdd = { kind, token, host, transport ->
                                                hostApp.forgeAdd(kind, token, host, transport)
                                            },
                                            forgeImport = { kind, transport -> hostApp.forgeImport(kind, transport) },
                                            forgeRemove = { id -> hostApp.forgeRemove(id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    entry<DesktopRoute.Archived>(
                        metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                    ) {
                        Column(Modifier.fillMaxSize().testTag("archived_overlay")) {
                            HostScopeBar(hostViews, activeHostId) { fleet?.setActiveHost(it) }
                            Box(Modifier.weight(1f)) {
                                ArchivedScreen(
                                    archived = archivedList,
                                    loading = archivedLoading,
                                    home = home,
                                    onBack = { ui.goBack() },
                                    onResume = { id ->
                                        overlayScope.launch { hostApp.resume(id) }
                                        ui.goBack()
                                    },
                                    loadLogs = { hostApp.archivedLogs(it) },
                                    forceOpenId = ui.forceArchivedOpenFor,
                                    onForceOpenConsumed = { ui.forceArchivedOpenFor = null },
                                )
                            }
                        }
                    }

                    entry<DesktopRoute.Usage>(
                        metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                    ) {
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
                                        ui.goBack()
                                        true
                                    } else false
                                },
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                HostScopeBar(hostViews, activeHostId) { fleet?.setActiveHost(it) }
                                Box(Modifier.weight(1f)) {
                                    UsageScreen(
                                        usage = usageData,
                                        loading = usageLoading,
                                        onBack = { ui.goBack() },
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
                        }
                    }

                    entry<DesktopRoute.AppUpdate>(
                        metadata = FullPaneOverlaySceneStrategy.fullPaneOverlay(),
                    ) {
                        val updFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { updFocus.requestFocus() } }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("app_update_overlay")
                                .focusRequester(updFocus)
                                .focusable()
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                        ui.goBack()
                                        true
                                    } else false
                                },
                        ) {
                            AppUpdateScreen(onBack = { ui.goBack() })
                        }
                    }
                },
            )

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
                        defaultDeviceName = remember { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.ifBlank { null } ?: "Desktop host" },
                        onClaim = { payload, name -> fleet.addHost(payload, name) },
                        onClaimByUrl = { url, name -> fleet.addHostByUrl(url, name) },
                        onAdded = { addHostOpen = false },
                    )
                }
            }
            } // weight Box (banner column content)
            } // Column (banner + content)
        }
    }
}

@Composable
private fun HostScopeBar(
    hosts: List<HostView>,
    selectedHostId: String?,
    onSelect: (String) -> Unit,
) {
    if (hosts.size < 2) return
    val selected = hosts.firstOrNull { it.recordId == selectedHostId } ?: hosts.first()
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    // Compact control (not full-width) so DropdownMenu anchors to a chip-sized box
    // instead of stretching across the settings pane.
    Column(Modifier.fillMaxWidth().background(cs.surfaceContainer)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Host", color = cs.onSurfaceVariant)
            Box {
                Row(
                    Modifier
                        .clickable { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("host_scope_picker"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HostDot(selected.colorIndex, size = 9.dp)
                    Text(
                        selected.displayLabel + if (!selected.online) " · Offline" else "",
                        modifier = Modifier.padding(start = 7.dp, end = 5.dp),
                        color = cs.onSurface,
                    )
                    Text("⌄", color = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    hosts.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host.displayLabel + if (!host.online) " (offline)" else "") },
                            leadingIcon = { HostDot(host.colorIndex, size = 10.dp) },
                            onClick = { expanded = false; onSelect(host.recordId) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
