// The shell's own state, shared by both hosts (cluster G8; this was desktop's `ShellUiState`
// inside `shell/AppShell.kt`).
//
// It holds what the SHELL owns — the sidebar chrome, the selected session, the Nav3 back stack and
// the one-shot headless hooks — and nothing about what is inside a pane: that is the workspace's
// own broker-stored layout tree, drawn by `PaneHost`.
//
// Desktop creates one in `Main.kt` (so the native MenuBar can act on the same instance) and
// Android holds one in `rememberSaveable(saver = ShellUiState.Saver)`, which is why the back stack
// and the selection are saveable and the window-host wiring is a SEAM ([ShellWindows]) rather than
// the registry itself: only desktop and Android have extra OS windows.
package dev.supermux.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.workspace.LayoutNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * Extra OS windows hosting slices of a workspace layout.
 *
 * `windows/RegistryShellWindows` implements this over the shared `WindowHostRegistry` on the
 * hosts that have extra windows (desktop, Android); a host with none keeps
 * [NoShellWindows], where nothing is ever claimed, [layoutFor] is the whole tree and every verb is
 * a no-op. This is only what the SHARED pane host needs; opening the windows is each host's.
 */
interface ShellWindows {
    /** The id of the window this shell is drawing (desktop's "main"). */
    val mainHostId: String

    /** The slice of [tree] this window shows: what another window claimed is hidden. */
    fun layoutFor(hostId: String, tree: LayoutNode): LayoutNode

    /** A view just created in [hostId]'s strip belongs to that window. */
    fun expandClaim(hostId: String, addedViewIds: Set<String>, tree: LayoutNode)

    /** A tab dropped into [toHostId]'s strip moves its claim there. */
    fun transfer(viewId: String, toHostId: String, tree: LayoutNode)

    /** The workspace's tree changed — re-derive claims, and hydrate any persisted window for it. */
    fun onWorkspaceTree(workspaceId: String, tree: LayoutNode)

    /** The main window is showing this workspace now. */
    fun setWorkspaceOnMain(workspaceId: String)

    /** The workspace on the main window, or "" before one is set. */
    fun mainWorkspaceId(): String

    /** Workspaces an extra window still claims a slice of — they must stay composed. */
    fun extraWorkspaceIds(): Set<String>
}

/** One window, no claims: the whole tree, every verb a no-op. */
object NoShellWindows : ShellWindows {
    override val mainHostId: String = "main"
    override fun layoutFor(hostId: String, tree: LayoutNode): LayoutNode = tree
    override fun expandClaim(hostId: String, addedViewIds: Set<String>, tree: LayoutNode) {}
    override fun transfer(viewId: String, toHostId: String, tree: LayoutNode) {}
    override fun onWorkspaceTree(workspaceId: String, tree: LayoutNode) {}
    override fun setWorkspaceOnMain(workspaceId: String) {}
    override fun mainWorkspaceId(): String = ""
    override fun extraWorkspaceIds(): Set<String> = emptySet()
}

/**
 * Holder for the shell UI state that both [SupermuxApp] and desktop's window MenuBar act on.
 *
 * The sidebar width/collapse below is the ONLY layout state the shell itself still owns.
 */
@Stable
class ShellUiState {
    var sidebarCollapsed by mutableStateOf(false)

    // Private backing state + read-only property (rather than `by mutableStateOf(...); private
    // set`) because the latter's generated private JVM setter would clash with [setSidebarWidth].
    private val sidebarWidthState = mutableStateOf(320.dp)
    val sidebarWidth: Dp get() = sidebarWidthState.value
    fun setSidebarWidth(w: Dp) { sidebarWidthState.value = w.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX) }

    var selectedId by mutableStateOf<String?>(null)

    /**
     * A pending "bring this chat's tab to the front" from the sidebar's chat row. Selecting a
     * session alone doesn't do it: the desktop tree keeps its own per-group tab, and clicking the
     * chat that is already [selectedId] changes nothing. The workspace panel that owns the chat
     * applies it and clears it ([consumeChatTabFocus]).
     */
    var chatTabFocus by mutableStateOf<String?>(null)
        private set

    fun focusChatTab(sessionId: String) {
        selectSession(sessionId)
        chatTabFocus = sessionId
    }

    fun consumeChatTabFocus() {
        chatTabFocus = null
    }

    /**
     * Archived workspace highlighted in the sidebar fold. Never copied into [selectedId]
     * (that id is a session id and was once wrongly set to a workspace id).
     */
    var selectedArchivedWorkspaceId by mutableStateOf<String?>(null)

    /**
     * Project-group keys collapsed in the workspace list — the in-memory copy.
     *
     * PERSISTED since cluster F1 under `SettingsKeys.SESSION_LIST_COLLAPSED_PATHS` (through
     * `UiPrefs`), the same key Android's list writes.
     */
    var collapsedProjectPaths by mutableStateOf(setOf<String>())

    /** Local light/dark (not a broker setting). Persisted through `SettingsKeys.APPEARANCE`. */
    var appearance by mutableStateOf(AppearanceMode.DARK)

    /**
     * Extra OS windows. [NoShellWindows] until a host that has them binds its own (desktop's
     * `Main.kt`, Android's `MainActivity`). A `var` because the registry outlives the composition
     * that uses it.
     */
    var windows: ShellWindows = NoShellWindows

    /**
     * Per-workspace bind for extra OS windows. Kept for every workspace that has a pop-out, not
     * only the selected one — switching sessions must not dispose another workspace's extra window.
     */
    val panesBinds = mutableStateMapOf<String, WorkspacePanesBind>()

    /** Selected (or last-known main) workspace bind — shortcuts / File menu. */
    val panesBind: WorkspacePanesBind?
        get() {
            val id = windows.mainWorkspaceId()
            if (id.isNotEmpty()) panesBinds[id]?.let { return it }
            return panesBinds.values.firstOrNull()
        }

    fun panesBindFor(workspaceId: String): WorkspacePanesBind? = panesBinds[workspaceId]

    /**
     * Nav3 back stack — the sole source of truth for every destination, on BOTH hosts.
     * Always starts with [Route.Home]; everything else is pushed with [navigate].
     *
     * Cluster G8 folded desktop's two out-of-stack booleans in here: the New-Session launcher is
     * [Route.NewSession] and the Usage card is [Route.Usage]. On a wide host those two are PANEL
     * routes — their Nav3 entry paints nothing and Home's own detail pane / sidebar footer draws
     * them in place, which is exactly the detail-pane swap and the anchored popover desktop always
     * had. Under Compact they are ordinary full-screen pushes, which is what Android always had.
     */
    val backStack: SnapshotStateList<Route> = mutableStateListOf(Route.Home)

    /** Top of [backStack] (never null — Home is always present). */
    val currentRoute: Route get() = backStack.lastOrNull() ?: Route.Home

    /** True when any destination above Home is up (gates the workspace/pane shortcuts). */
    val overlayOpen: Boolean get() = backStack.size > 1

    // Read-only views of the stack (for load effects / assertions). Open via [navigate] / open*.
    val archivedOpen: Boolean get() = currentRoute is Route.Archived
    val displaysOpen: Boolean get() = currentRoute is Route.Displays
    val settingsOpen: Boolean get() = currentRoute is Route.Settings
    val appUpdateOpen: Boolean get() = currentRoute is Route.AppUpdate
    val addHostOpen: Boolean get() = currentRoute is Route.AddHost
    /** The New-Session launcher is showing (a detail-pane swap on a wide host). */
    val launcherOpen: Boolean get() = currentRoute is Route.NewSession
    /** When set, the launcher reopens this draft session (web /new?draft=). */
    val launcherDraftId: String? get() = (currentRoute as? Route.NewSession)?.draftId?.takeIf { it.isNotBlank() }
    /**
     * When set, the launcher preselects this persistent project (host record id to project id).
     *
     * ONE-SHOT per navigation: once the launcher applies it, [consumeLauncherProject] marks that
     * very route INSTANCE consumed, so a remount of the launcher (or any later recomposition) no
     * longer overrides whatever the user or a restored draft chose since. A fresh
     * [openLauncherInProject] — even for the same project — pushes a NEW instance, so it applies
     * again. The route itself is left untouched: replacing it would re-key its Nav3 entry and
     * rebuild the launcher under Compact.
     */
    val launcherProject: Pair<String, String>? get() = (currentRoute as? Route.NewSession)
        ?.takeIf { it.projectId.isNotBlank() && it !== consumedLauncherRoute }
        ?.let { it.projectHostId to it.projectId }

    // Referential: every navigation is a new instance even when it is structurally equal to the last.
    private var consumedLauncherRoute by mutableStateOf<Route?>(null, referentialEqualityPolicy())

    /** The launcher applied [launcherProject]; don't hand it out again for this navigation. */
    fun consumeLauncherProject() {
        (currentRoute as? Route.NewSession)?.let { consumedLauncherRoute = it }
    }
    /** The Usage card is showing (an anchored popover on a wide host). */
    val usageOpen: Boolean get() = currentRoute is Route.Usage
    val lspSettingsOpen: Boolean
        get() = (currentRoute as? Route.Settings)?.section == SettingsSection.EditorLsp
    val personalAssistantsOpen: Boolean
        get() = (currentRoute as? Route.Settings)?.section == SettingsSection.PersonalAssistants

    /**
     * Settings rail section. When Settings is on the stack, reads/writes that route's section;
     * when closed, remembers the last section for the next open (and for tests).
     */
    var settingsSection: SettingsSection
        get() = (currentRoute as? Route.Settings)?.section ?: lastSettingsSection
        set(value) {
            lastSettingsSection = value
            val i = backStack.indexOfLast { it is Route.Settings }
            if (i >= 0) backStack[i] = Route.Settings(value)
        }
    private var lastSettingsSection by mutableStateOf(SettingsSection.Agents)

    /**
     * Push [route]. [Route.Home] clears everything above it; any other route REPLACES whatever is
     * open (`[Home, route]`) rather than stacking — the destinations above Home are mutually
     * exclusive on both hosts, which is what desktop's booleans encoded and what Android's
     * single-level pushes did in practice.
     */
    fun navigate(route: Route) {
        popToHome()
        if (route is Route.Home) return
        if (route is Route.Settings) lastSettingsSection = route.section
        backStack.add(route)
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

    /** New-session UI: a detail-pane swap on a wide host, a pushed screen under Compact. */
    fun openLauncher(draftId: String? = null) = navigate(Route.NewSession(draftId.orEmpty()))

    /** New-session UI preselecting persistent project [projectId] of host [hostId]. */
    fun openLauncherInProject(hostId: String, projectId: String) =
        navigate(Route.NewSession(projectHostId = hostId, projectId = projectId))

    fun closeLauncher() {
        if (launcherOpen) goBack()
    }

    fun selectSession(id: String) {
        selectedId = id
        selectedArchivedWorkspaceId = null
        if (launcherOpen) popToHome()
    }

    fun selectArchivedWorkspace(id: String) {
        selectedArchivedWorkspaceId = id
        selectedId = null
        if (launcherOpen) popToHome()
    }

    // Menu / chrome conveniences → navigate
    fun openArchived() = navigate(Route.Archived)
    fun openDisplays() = navigate(Route.Displays)
    fun openAddHost() = navigate(Route.AddHost)

    /** Icon-anchored Usage card. Toggle when already open; otherwise open. */
    fun openUsage() {
        if (usageOpen) {
            goBack()
            return
        }
        navigate(Route.Usage)
    }

    fun closeUsage() {
        if (usageOpen) goBack()
    }

    fun openSettings(section: SettingsSection = SettingsSection.Agents) =
        navigate(Route.Settings(section))
    fun openLspSettings() = openSettings(SettingsSection.EditorLsp)
    fun openPersonalAssistants() = openSettings(SettingsSection.PersonalAssistants)
    fun openAppUpdate() = navigate(Route.AppUpdate)

    /**
     * The session list's legacy string-keyed navigation callback (`onNavigate("settings")`).
     * Unknown names are ignored — the list also names destinations no host renders.
     */
    fun navigateByName(dest: String) {
        when (dest) {
            "new" -> openLauncher()
            "settings" -> openSettings()
            "usage" -> openUsage()
            "devices" -> navigate(Route.Devices)
            "archived" -> openArchived()
            "proxies" -> navigate(Route.Proxies)
            "appearance" -> navigate(Route.Appearance)
            "displays" -> openDisplays()
            "addhost" -> openAddHost()
        }
    }

    /**
     * One-shot external "open this file" request (sessionId → ref), consumed by the workspace shell
     * and routed through the SAME `WorkspaceFileOpener` a chat file-path tap and an explorer click
     * use. Set by the off-by-default `SM_OPEN_FILE` headless hook in Main.kt; null in normal
     * operation. Cleared once the open has been requested.
     */
    var externalOpen by mutableStateOf<Pair<String, dev.supermux.ui.FilePathRef>?>(null)

    /**
     * One-shot "force-open the git-badge menu" request (session id + [GitMenuForceOp]), consumed by
     * [WorkspaceHeader] → [GitBadgeMenu] when that session is the open workspace's git session.
     * Set by the off-by-default `SM_GIT_MENU` headless hook in Main.kt.
     */
    var forceGitMenuFor by mutableStateOf<Pair<String, GitMenuForceOp>?>(null)

    /**
     * One-shot "force-open the session-links (proxies) globe menu" request (session id), consumed
     * by that session's CHAT VIEW header → `SessionLinksMenu`. Never opens a URL.
     */
    var forceLinksMenuFor by mutableStateOf<String?>(null)

    /**
     * One-shot "stage this file into the chat composer, upload it, then send" request (session id +
     * `ComposerExternalAttach`), consumed by the matching pane's Composer — the SAME
     * `stageFiles`/`sendWith` funnel the Attach dialog and Send button use.
     */
    var externalAttach by mutableStateOf<Pair<String, dev.supermux.ui.chat.ComposerExternalAttach>?>(null)

    /**
     * One-shot "transcribe this WAV file into the chat composer's draft" request (session id +
     * `ComposerExternalDictate`), consumed by the matching pane's Composer.
     */
    var externalDictate by mutableStateOf<Pair<String, dev.supermux.ui.chat.ComposerExternalDictate>?>(null)

    /**
     * One-shot "paste image from clipboard into the selected session's composer" request. Bumped by
     * Edit ▸ Paste image in the native MenuBar. Zero in normal operation.
     */
    var pasteImageRequestNonce by mutableStateOf(0L)

    /** Bump [pasteImageRequestNonce] so the selected composer's paste-image path runs once. */
    fun requestPasteImage() {
        pasteImageRequestNonce = pasteImageRequestNonce + 1
    }

    /**
     * One-shot "open this archived session's read-only transcript" request (an ARCHIVED session
     * id), consumed by `ArchivedScreen`. Set by the off-by-default `SM_ARCHIVED_OPEN` hook.
     */
    var forceArchivedOpenFor by mutableStateOf<String?>(null)

    /**
     * One-shot "open a view of this kind in the workspace on screen" request (view kind → its
     * initial `state`). Set by the off-by-default `SM_DIFF` / `SM_DISPLAY` headless hooks.
     */
    var forceWorkspaceView by mutableStateOf<Pair<String, kotlinx.serialization.json.JsonObject>?>(null)

    /**
     * Reconciles the hydrated UI state against the [live] session-id set: drops a selection whose
     * session vanished (killed elsewhere / agent exit).
     *
     * GUARD: an EMPTY [live] set is treated as "sessions not loaded yet", NOT "everything died".
     */
    fun reconcileSessions(live: Set<String>) {
        if (live.isEmpty()) return
        if (selectedId != null && selectedId !in live) selectedId = null
    }

    companion object {
        val SIDEBAR_MIN = 220.dp
        val SIDEBAR_MAX = 560.dp

        private val routeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /**
         * Android's holder: the shell survives a rotation AND process death.
         *
         * Only what the user would notice losing is saved — the back stack (as JSON, since every
         * [Route] is `@Serializable`), the selection and the sidebar chrome. The one-shot headless
         * hooks and the pane binds are deliberately not: they are consumed within a frame, or
         * belong to a window that did not survive either.
         */
        val Saver: Saver<ShellUiState, Any> = listSaver(
            save = { s ->
                listOf(
                    runCatching {
                        routeJson.encodeToString(ListSerializer(Route.serializer()), s.backStack.toList())
                    }.getOrDefault(""),
                    s.selectedId ?: "",
                    s.selectedArchivedWorkspaceId ?: "",
                    s.sidebarCollapsed,
                    s.sidebarWidth.value,
                )
            },
            restore = { parts ->
                ShellUiState().apply {
                    (parts.getOrNull(0) as? String)?.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching {
                            routeJson.decodeFromString(ListSerializer(Route.serializer()), raw)
                        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { restored ->
                            backStack.clear()
                            backStack.addAll(restored)
                        }
                    }
                    selectedId = (parts.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }
                    selectedArchivedWorkspaceId = (parts.getOrNull(2) as? String)?.takeIf { it.isNotBlank() }
                    sidebarCollapsed = parts.getOrNull(3) as? Boolean ?: false
                    (parts.getOrNull(4) as? Float)?.dp?.let(::setSidebarWidth)
                }
            },
        )
    }
}
