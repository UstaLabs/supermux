package dev.supermux.android

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.navigation.compose.rememberNavController
import dev.supermux.android.platform.AndroidPlatform
import dev.supermux.ui.platform.LocalPlatform
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.supermux.ui.host.AddHostScreen
import dev.supermux.ui.host.HostScopePicker
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.rememberAssistantSettingsActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.host.HostView
import dev.supermux.host.ViewingSurface
import dev.supermux.host.WorkspaceViewingSnapshot
import dev.supermux.android.push.PushTapHandle
import dev.supermux.android.push.notificationCancelSessionIds
import dev.supermux.android.push.pushTapHandleDecision
import dev.supermux.android.push.resolvePushTap
import dev.supermux.host.viewingSurfaceVisible
import dev.supermux.android.host.visibleChatIdsForAndroid
import dev.supermux.host.visibleWorkspaceChatIds
import dev.supermux.host.workspaceForSession
import dev.supermux.android.session.SessionKeepAlivePhoneHost
import dev.supermux.android.session.SessionKeepAliveTabletHost
import dev.supermux.android.session.rememberVisitedSessions
import dev.supermux.android.workspace.ChatActivationHandle
import dev.supermux.android.workspace.chatActivationDecision
import dev.supermux.android.session.SessionLauncherScreen
import dev.supermux.android.session.SessionListScreen
import dev.supermux.ui.shell.SessionsRail
import dev.supermux.ui.shell.CompactSidebarDivider
import dev.supermux.android.workspace.SidebarState
import dev.supermux.proto.chatSessionId
import dev.supermux.android.workspace.addViewState
import dev.supermux.android.workspace.workspaceShortcuts
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.android.display.DisplaysScreen
import dev.supermux.android.settings.AppearanceSettingsPage
import dev.supermux.android.settings.ArchivedScreen
import dev.supermux.android.settings.DevicesScreen
import dev.supermux.android.settings.ProxyScreen
import dev.supermux.android.settings.SettingsScreen
import dev.supermux.android.update.AppUpdateBanner
import dev.supermux.android.update.AppUpdateNotifier
import dev.supermux.android.settings.UsageScreen
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.ui.ThemeDefaults
import dev.supermux.android.DevConfig
import dev.supermux.android.host.HostStores
import dev.supermux.android.pairing.OnboardingFlow
import dev.supermux.android.push.PushPermission
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.auth.SecureTokenStore
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.net.ArchivedDto
import dev.supermux.net.PairUrl
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.state.SidebarReorderKind
import dev.supermux.state.sidebarReorderKind
import dev.supermux.ui.nav.Route
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.android.settings.AndroidSettingsStore

/**
 * The launch steps `MainActivity`'s `setContent` runs, in the order it runs them.
 *
 * This is an executable statement of an invariant that is otherwise only visible as the physical
 * order of lines inside one very long composable — and one that has already regressed once:
 * [LaunchStep.CreateViewModel] MUST come after [LaunchStep.LegacyMigration] and
 * [LaunchStep.PairingGate], because `AppViewModel`'s `fleet` initializer snapshots the paired-host
 * list exactly once. A VM built before pairing sees zero hosts forever.
 */
internal enum class LaunchStep { DebugSeed, LegacyMigration, PairingGate, CreateViewModel }

/** The steps that actually run for a given pairing state — un-paired stops at the gate. */
internal fun launchOrder(paired: Boolean): List<LaunchStep> = buildList {
    add(LaunchStep.DebugSeed)
    add(LaunchStep.LegacyMigration)
    add(LaunchStep.PairingGate)
    if (paired) add(LaunchStep.CreateViewModel)
}

class MainActivity : ComponentActivity() {
    // Current launch/deep-link intent, surfaced to Compose. Seeded in onCreate; updated by
    // onNewIntent so a supermux://pair link delivered while foregrounded re-enters pairing.
    private val intentState = mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
    }

    @OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SecureTokenStoreContext.init(applicationContext)
        // Native push: ensure the notification channel exists and ask for POST_NOTIFICATIONS
        // (API 33+) so decrypted session pushes can be shown. Must run before the activity
        // is STARTED, hence here in onCreate before setContent.
        SupermuxMessagingService.ensureChannel(this)
        // App self-update progress / failure alerts (status bar during APK download).
        AppUpdateNotifier.ensureChannels(this)
        PushPermission.request(this)
        intentState.value = intent
        enableEdgeToEdge()
        setContent {
            val prefs = remember {
                applicationContext.getSharedPreferences("cmux-editor-settings", Context.MODE_PRIVATE)
            }
            var appearance by remember {
                mutableStateOf(
                    runCatching {
                        AppearanceMode.valueOf(prefs.getString("appearance", "SYSTEM") ?: "SYSTEM")
                    }.getOrDefault(AppearanceMode.SYSTEM)
                )
            }
            // Kept for the Settings → Appearance switch only: dynamic color (Material You) is a no-op
            // now — the brand palette is the only palette (see AndroidTheme).
            var dynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamicColor", ThemeDefaults.DYNAMIC_COLOR_ENABLED)) }
            var textScale by remember { mutableStateOf(prefs.getFloat("textScale", 1f)) }
            // The theme's persisted UI preferences. Built here — NOT from the AppViewModel —
            // because the VM must stay below the pairing gate (see the invariant there), and
            // `AndroidSettingsStore(context)` is a process-wide DataStore delegate: this instance
            // and `vm.uiPrefs` read and write exactly the same data.
            val themeUiPrefs = remember { UiPrefs(AndroidSettingsStore(applicationContext)) }
            AndroidTheme(appearance = appearance, textScale = textScale, uiPrefs = themeUiPrefs) {
                val store = remember { SecureTokenStore() }
                // Debug-only: seed token+baseUrl on debuggable builds so the already-paired
                // emulator boots past the gate (no-op on release / when DEBUG_TOKEN is empty).
                // Then run the one-time single-host → PairedHost[0] migration (spec §3.2): existing
                // paired users land in the multi-host store with zero re-pairing. Ordered after the
                // debug seed so a debug-seeded token migrates too; before the gate/connection below.
                remember {
                    DevConfig.seedDebugPairingIfEmpty(applicationContext)
                    HostStores.migrateFromLegacyIfNeeded(applicationContext)
                    Unit
                }
                // Paired ⇔ the encrypted store holds BOTH a token and a broker base URL.
                var paired by rememberSaveable {
                    mutableStateOf(
                        store.load()?.isNotBlank() == true && store.loadBaseUrl()?.isNotBlank() == true,
                    )
                }
                // Native push: re-register FCM token with the relay whenever we are (or become)
                // paired. onNewToken alone is insufficient — FCM often issues the token *before*
                // pairing, and the old path used a placeholder base URL. Parity with iOS
                // PushManager.registerIfPaired (launch + post-pair).
                LaunchedEffect(paired) {
                    if (paired) {
                        SupermuxMessagingService.registerIfPaired(applicationContext)
                    }
                }
                // Deep-link intake: parse supermux://pair (or a pasted https pair URL) from the
                // current intent. Recomputed when onNewIntent swaps the intent in while foregrounded.
                val currentIntent by intentState
                val deepLink: PairUrl? = remember(currentIntent) {
                    currentIntent?.data?.toString()?.let { PairUrl.parse(it, store.loadBaseUrl()) }
                }

                if (!paired) {
                    OnboardingFlow(
                        onPaired = { paired = true },
                        initialDeepLink = deepLink,
                    )
                    return@AndroidTheme
                }

                // ORDERING INVARIANT — the VM is created BELOW this gate, on purpose.
                // `AppViewModel`'s `fleet` initializer runs `HostStores.migrateFromLegacyIfNeeded`
                // and `FleetStore.init` then snapshots `store.list()` ONCE. Pairing (and the debug
                // seed) writes only the legacy single-host store, so a VM built before the gate
                // would snapshot an empty host list and never re-sync — a fresh install would sit
                // hostless after its first pairing until the process restarts. See
                // `launchOrder(paired)` / `MainActivityLaunchOrderTest`. Everything the VM needs
                // (the debug seed + the legacy migration in the `remember` above, and `paired`)
                // has happened by the time this line runs.
                //
                // Multi-host (spec §5): the VM owns N per-host connections from the
                // PairedHostStore, re-running the idempotent single-host→PairedHost[0] migration
                // on init so existing users — and the session where onboarding just paired —
                // always have a host to drive.
                val vm: AppViewModel = viewModel(factory = AppViewModel.factory(application))
                val sessions by vm.fleet.sessions.collectAsStateWithLifecycle()
                val archivedSessions by vm.fleet.archivedSessions.collectAsStateWithLifecycle()
                val workspaces by vm.fleet.workspaces.collectAsStateWithLifecycle()
                val archivedWorkspaces by vm.fleet.archivedWorkspaces.collectAsStateWithLifecycle()
                val messages by vm.fleet.messages.collectAsStateWithLifecycle()
                val activity by vm.fleet.activity.collectAsStateWithLifecycle()
                val agentState by vm.fleet.agentState.collectAsStateWithLifecycle()
                val pendingSend by vm.fleet.pendingSend.collectAsStateWithLifecycle()
                val commands by vm.fleet.commands.collectAsStateWithLifecycle()
                val commandsResolved by vm.fleet.commandsResolved.collectAsStateWithLifecycle()
                val lastRead by vm.fleet.lastRead.collectAsStateWithLifecycle()
                // Merged-fleet state: the paired hosts (identity + reachability), the sessionId→host
                // owner index (per-row badges), and the persisted host-filter chip selection.
                val hostViews by vm.fleet.hostViews.collectAsStateWithLifecycle()
                val sessionHost by vm.fleet.sessionHost.collectAsStateWithLifecycle()
                val activeHost by vm.fleet.activeHost.collectAsStateWithLifecycle()
                val activeHostSessions = remember(sessions, sessionHost, hostViews, activeHost) {
                    if (hostViews.size >= 2 && activeHost != null) {
                        sessions.filter { sessionHost[it.id] == activeHost }
                    } else {
                        sessions
                    }
                }
                var hostFilter by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { hostFilter = vm.fleet.hostFilter.first() }
                val setHostFilter: (String?) -> Unit = { hostFilter = it; vm.fleet.saveHostFilter(it) }
                val loadHostAgents: suspend () -> List<String> = { vm.fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind } }
                val lastBySession = messages.mapValues { it.value.lastOrNull() }
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                val newChatScope = rememberCoroutineScope()
                val activityContext = LocalContext.current
                val onNewChatInWorkspace: (dev.supermux.proto.WorkspaceDto) -> Unit = { w ->
                    newChatScope.launch {
                        val recordId = activeHost
                        if (recordId == null) {
                            Toast.makeText(activityContext, "No host connected", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        runCatching {
                            val id = vm.fleet.newChatInWorkspace(recordId, w.id, w.workdir)
                            selected = id
                        }.onFailure {
                            Toast.makeText(
                                activityContext,
                                it.message ?: "Failed to create session",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                val liveSessionIds = remember(sessions) { sessions.map { it.id }.toSet() }
                val (visitedSessions, removeVisited) = rememberVisitedSessions(selected, liveSessionIds)
                // Shared multi-pane layout for wide screens — one instance across all sessions,
                // saved across config-change/process-death, pruned when the broker drops a session.
                val sidebarState = rememberSaveable(saver = SidebarState.Saver) { SidebarState() }
                // A session resumed from archive arrives via `session_added` (no history), so its
                // transcript would be empty until the next snapshot/restart. Seed it whenever a chat
                // is opened — a no-op for sessions the snapshot already populated. (iOS parity:
                // ChatPane.loadPane → BrokerSession.ensureMessagesLoaded.)
                // Wide = anything but Compact, i.e. available width ≥600dp. "!= Compact" (not only
                // Expanded ≥840) means the unfolded Galaxy Z Fold 7 qualifies; narrower (phones /
                // folded cover) keeps single-pane chat.
                val wide = LocalWindowWidthClass.current != WindowWidthClass.Compact
                val cs = MaterialTheme.colorScheme

                val navController = rememberNavController()
                val navEntry by navController.currentBackStackEntryAsState()
                val homeRoute = navEntry?.destination?.hasRoute<Route.Home>() == true
                val overlayOpen = navEntry != null && !homeRoute

                // Report which chats are foreground so the broker suppresses a push (spec §11).
                val lifecycleOwner = LocalLifecycleOwner.current
                var appVisible by remember {
                    mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                }
                DisposableEffect(lifecycleOwner) {
                    val obs = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> appVisible = true
                            Lifecycle.Event.ON_STOP -> appVisible = false
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }

                LaunchedEffect(selected, workspaces, wide, appVisible, overlayOpen, homeRoute) {
                    if (!appVisible || overlayOpen || !homeRoute) return@LaunchedEffect
                    selected?.let {
                        sessionHost[it]?.let(vm.fleet::setActiveHost)
                        vm.fleet.ensureMessagesLoaded(it)
                        val hostId = sessionHost[it] ?: vm.fleet.activeHost.value
                        val ws = hostId?.let { h -> vm.fleet.workspaceForSession(h, it) }
                        val layout = ws?.layout?.toDomainOrNull()
                        val visibleIds = if (ws != null) {
                            visibleChatIdsForAndroid(wide, ws, layout)
                        } else {
                            emptyList()
                        }
                        for (id in notificationCancelSessionIds(visibleIds, it)) {
                            SupermuxMessagingService.cancelForSession(applicationContext, id)
                        }
                    }
                }
                // Activate the opened session's chat view once per selection — never in
                // response to WorkspaceChanged (that frame is the broker acknowledging a
                // user tab switch). Cold start retries while workspaces are still empty.
                var lastActivatedSelection by rememberSaveable { mutableStateOf<String?>(null) }
                var handledPushSessionId by rememberSaveable { mutableStateOf<String?>(null) }
                // Reset must stay declared BEFORE the activation effect: Compose runs
                // LaunchedEffects in declaration order, so null→A clears handled ids first.
                LaunchedEffect(selected) {
                    if (selected == null) {
                        lastActivatedSelection = null
                        handledPushSessionId = null
                    }
                }
                LaunchedEffect(selected, workspaces, sessionHost) {
                    val sid = selected ?: return@LaunchedEffect
                    val hostId = sessionHost[sid] ?: vm.fleet.activeHost.value
                    val ws = hostId?.let { h -> vm.fleet.workspaceForSession(h, sid) }
                    val chatView = ws?.views?.firstOrNull { v -> v.chatSessionId() == sid }
                    val decision = chatActivationDecision(sid, lastActivatedSelection, ws, chatView)
                    if (decision == ChatActivationHandle.Skip) return@LaunchedEffect
                    if (decision == ChatActivationHandle.ApplyConsume) {
                        if (ws != null && chatView != null && ws.activeViewId != chatView.id) {
                            vm.fleet.setActiveView(ws.id, chatView.id)
                        }
                        lastActivatedSelection = sid
                    }
                }
                // A tapped push carries the chat id. Resolve the owning workspace (Phase 4) and
                // activate that chat view without PATCHing layout. Old broker / no workspace →
                // session-only screen, same as before. Consume the extra once workspaces are
                // ready so later workspaces/sessionHost updates cannot yank the user back.
                LaunchedEffect(currentIntent, workspaces) {
                    val extra = currentIntent
                        ?.getStringExtra(SupermuxMessagingService.EXTRA_SESSION_ID)
                    val decision = pushTapHandleDecision(extra, handledPushSessionId, workspaces.isNotEmpty())
                    if (decision == PushTapHandle.Skip) return@LaunchedEffect
                    val sid = extra!!
                    val hostId = sessionHost[sid] ?: vm.fleet.activeHost.value
                    val owned = hostId?.let { vm.fleet.workspaceForSession(it, sid) }
                    val tap = resolvePushTap(sid, owned?.let { listOf(it) } ?: workspaces)
                    selected = sid
                    if (tap.workspaceId != null && tap.activeViewId != null) {
                        vm.fleet.setActiveView(tap.workspaceId, tap.activeViewId)
                    }
                    if (decision == PushTapHandle.ApplyConsume) {
                        handledPushSessionId = sid
                        currentIntent?.removeExtra(SupermuxMessagingService.EXTRA_SESSION_ID)
                    }
                }
                val selectedWorkspace = selected?.let { sid ->
                    val hostId = sessionHost[sid] ?: vm.fleet.activeHost.value
                    hostId?.let { h -> vm.fleet.workspaceForSession(h, sid) }
                        ?: workspaceForSession(workspaces, sid)
                }
                val viewingSnapshot = run {
                    val surface = ViewingSurface(
                        homeRoute = homeRoute,
                        overlayOpen = overlayOpen,
                        workspaceResolved = selectedWorkspace != null,
                        appForeground = appVisible,
                    )
                    val layout = selectedWorkspace?.layout?.toDomainOrNull()
                    val ids = selectedWorkspace?.let {
                        visibleChatIdsForAndroid(wide, it, layout)
                    }.orEmpty()
                    val snap = selectedWorkspace?.let {
                        WorkspaceViewingSnapshot(
                            workspaceId = it.id,
                            visibleChatSessionIds = ids,
                            appForeground = appVisible,
                        )
                    }
                    val visibleIds = visibleWorkspaceChatIds(
                        surfaceVisible = viewingSurfaceVisible(surface),
                        selectedWorkspaceId = selectedWorkspace?.id,
                        snapshot = snap,
                    )
                    when {
                        viewingSurfaceVisible(surface) && snap != null ->
                            snap.copy(visibleChatSessionIds = visibleIds)
                        homeRoute && !overlayOpen && appVisible ->
                            WorkspaceViewingSnapshot(
                                workspaceId = "",
                                visibleChatSessionIds = emptyList(),
                                appForeground = true,
                            )
                        else -> null
                    }
                }
                LaunchedEffect(viewingSnapshot) { vm.fleet.updateViewing(viewingSnapshot) }
                // Maps the screens' legacy string-route callbacks to type-safe NavHost destinations.
                val navTo: (String) -> Unit = { dest ->
                    when (dest) {
                        "new" -> navController.navigate(Route.NewSession())
                        "settings" -> navController.navigate(Route.Settings())
                        "usage" -> navController.navigate(Route.Usage)
                        "devices" -> navController.navigate(Route.Devices)
                        "archived" -> navController.navigate(Route.Archived)
                        "proxies" -> navController.navigate(Route.Proxies)
                        "appearance" -> navController.navigate(Route.Appearance)
                        "addhost" -> navController.navigate(Route.AddHost)
                        // "displays"/"theme"/"list" → no destinations (stubs)
                    }
                }

                Column(Modifier.fillMaxSize()) {
                // App self-update strip (versions.json). One-tap install for sideloaded APKs.
                AppUpdateBanner(
                    onOpenPage = { navController.navigate(Route.Settings()) },
                )
                NavHost(
                    navController = navController,
                    startDestination = Route.Home,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTagsAsResourceId = true },
                ) {
                    // ── Home: list ↔ chat (keep-alive). Bodies are the old `else`-branch, verbatim,
                    //    with `route = …` swapped for nav. The keep-alive / shared-element / predictive-back
                    //    code lives inside the hosts below and is unchanged. ──
                    composable<Route.Home> {
                        if (wide) {
                            // Container focus so hardware-keyboard shortcuts (Ctrl/Cmd + …) are
                            // received; onPreviewKeyEvent still sees events when a descendant (chat
                            // input / terminal) holds focus, so it intercepts combos yet lets typing
                            // pass. Requesting focus once on first composition seeds the focus owner.
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            // Suppress the collapse/expand width spring while the divider is being
                            // dragged (otherwise the spring chases the finger and feels laggy).
                            var resizing by remember { mutableStateOf(false) }
                            val collapsed = sidebarState.sidebarCollapsed
                            val sidebarWidth by animateDpAsState(
                                targetValue = if (collapsed) 64.dp else sidebarState.sidebarWidth,
                                animationSpec = if (resizing) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                                label = "sidebarWidth",
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .focusRequester(focusRequester)
                                    .workspaceShortcuts(
                                        sidebar = sidebarState,
                                        selectedId = selected,
                                        onNewSession = { navController.navigate(Route.NewSession()) },
                                        onAddKind = { kind ->
                                            val sid = selected ?: return@workspaceShortcuts
                                            val hostId = sessionHost[sid] ?: vm.fleet.activeHost.value ?: return@workspaceShortcuts
                                            val ws = vm.fleet.workspaceForSession(hostId, sid) ?: return@workspaceShortcuts
                                            val tree = ws.layout.toDomainOrNull()
                                            val views = ws.views.associateBy { it.id }
                                            val open = tree?.let { openSingletonView(it, views, kind) }
                                            if (open != null) {
                                                vm.fleet.setActiveView(ws.id, open.first)
                                            } else {
                                                vm.fleet.addWorkspaceView(
                                                    ws.id,
                                                    kind.wire,
                                                    addViewState(kind, System.currentTimeMillis()),
                                                )
                                            }
                                        },
                                    )
                                    .focusable(),
                            ) {
                              Row(Modifier.fillMaxSize()) {
                                // Sidebar: collapsed avatar rail OR the full list; the animating
                                // parent Box clips (surfaceContainerHigh backs the reveal gap).
                                Box(
                                    Modifier
                                        .width(sidebarWidth)
                                        .fillMaxHeight()
                                        .background(cs.surfaceContainerHigh)
                                        .clipToBounds(),
                                ) {
                                    if (collapsed) {
                                        SessionsRail(
                                            sessions = sessions,
                                            selectedId = selected,
                                            agentState = agentState,
                                            onSelect = { selected = it },
                                            onExpand = { sidebarState.sidebarCollapsed = false },
                                            onNewSession = { navController.navigate(Route.NewSession()) },
                                            lastBySession = lastBySession,
                                            lastRead = lastRead,
                                        )
                                    } else {
                                        // requiredWidth keeps the list at its full width while the
                                        // narrower animating parent clips it during the reveal.
                                        Box(Modifier.requiredWidth(sidebarState.sidebarWidth).fillMaxHeight()) {
                                            SessionListScreen(
                                                sessions = sessions,
                                                home = DevConfig.HOME,
                                                activeId = selected,
                                                onOpen = { selected = it },
                                                lastBySession = lastBySession,
                                                lastRead = lastRead,
                                                agentState = agentState,
                                                onNewSession = { navController.navigate(Route.NewSession()) },
                                                loadProjects = { vm.fleet.listProjects() },
                                                validatePath = { vm.fleet.validatePath(it) },
                                                onNavigate = navTo,
                                                onRename = { id, name -> vm.fleet.rename(id, name) },
                                                onKill = { id -> vm.fleet.kill(id) },
                                                onMute = { id, m -> vm.fleet.setMute(id, m) },
                                                archived = archivedSessions,
                                                onResume = { id -> vm.fleet.resume(id) },
                                                onOpenDraft = { id -> navController.navigate(Route.NewSession(draftId = id)) },
                                                onReorder = { ids -> if (sidebarReorderKind(workspaces) == SidebarReorderKind.SESSIONS) vm.fleet.reorderSessions(ids) else vm.fleet.reorderWorkspaces(ids) },
                                                workspaces = workspaces,
                                                archivedWorkspaces = archivedWorkspaces,
                                                onArchiveWorkspace = { id -> vm.fleet.archiveWorkspace(id) },
                                                onRestoreWorkspace = { id -> vm.fleet.restoreWorkspace(id) },
                                                onNewChatInWorkspace = onNewChatInWorkspace,
                                                hosts = hostViews,
                                                sessionHost = sessionHost,
                                                hostFilter = hostFilter,
                                                onHostFilter = setHostFilter,
                                                onAddHost = { navController.navigate(Route.AddHost) },
                                                onRenameHost = { id, name -> vm.fleet.renameHost(id, name) },
                                                onForgetHost = { id -> vm.fleet.forgetHost(id) },
                                            )
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    if (selected == null) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(cs.surfaceContainerLow),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("Select a session", color = cs.onSurfaceVariant)
                                        }
                                    }
                                    SessionKeepAliveTabletHost(
                                        selected = selected,
                                        onSelect = { selected = it },
                                        visited = visitedSessions,
                                        onRemoveVisited = removeVisited,
                                        sessions = sessions,
                                        messages = messages,
                                        activityMap = activity,
                                        agentState = agentState,
                                        pendingSend = pendingSend,
                                        commands = commands,
                                        commandsResolved = commandsResolved,
                                        archived = archivedSessions,
                                        vm = vm,
                                        wide = true,
                                        workspaces = workspaces,
                                        onNavigate = navTo,
                                        onOpenDisplays = { navController.navigate(Route.Displays) },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                              }
                              // Resize divider as an OVERLAY on the seam (x = sidebarWidth): it holds
                              // no layout width, so the detail column fills the full space and only a
                              // hairline (+ the collapse chevron) floats on the boundary.
                              if (collapsed) {
                                  Box(
                                      Modifier
                                          .offset(x = sidebarWidth)
                                          .width(1.dp)
                                          .fillMaxHeight()
                                          .background(cs.outlineVariant),
                                  )
                              } else {
                                  CompactSidebarDivider(
                                      modifier = Modifier.offset(x = sidebarWidth - 7.dp),
                                      onDragDelta = { d ->
                                          sidebarState.setSidebarWidth(sidebarState.sidebarWidth + d)
                                      },
                                      onCollapse = { sidebarState.sidebarCollapsed = true },
                                      onStartDrag = { resizing = true },
                                      onEndDrag = { resizing = false },
                                  )
                              }
                            }
                        } else {
                            PhoneNavHost(
                                selected = selected,
                                onSelect = { selected = it },
                                onClearSelected = { selected = null },
                                visited = visitedSessions,
                                onRemoveVisited = removeVisited,
                                sessions = sessions,
                                messages = messages,
                                activityMap = activity,
                                agentState = agentState,
                                pendingSend = pendingSend,
                                commands = commands,
                                commandsResolved = commandsResolved,
                                lastBySession = lastBySession,
                                lastRead = lastRead,
                                archived = archivedSessions,
                                vm = vm,
                                onNavigate = navTo,
                                onOpenDraft = { id -> navController.navigate(Route.NewSession(draftId = id)) },
                                onOpenDisplays = { navController.navigate(Route.Displays) },
                                hosts = hostViews,
                                sessionHost = sessionHost,
                                hostFilter = hostFilter,
                                onHostFilter = setHostFilter,
                                onAddHost = { navController.navigate(Route.AddHost) },
                                workspaces = workspaces,
                                archivedWorkspaces = archivedWorkspaces,
                            )
                        }
                    }
                    // ── New-session launcher (old "new" branch, verbatim, route→nav) ──
                    composable<Route.NewSession> { entry ->
                        val ns = entry.toRoute<Route.NewSession>()
                        val draftId = ns.draftId.takeIf { it.isNotBlank() }
                        val draftSession = draftId?.let { id -> sessions.find { it.id == id } }
                        if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(320.dp)) {
                                    SessionListScreen(
                                        sessions = sessions,
                                        home = DevConfig.HOME,
                                        activeId = selected,
                                        onOpen = { selected = it; navController.popBackStack() },
                                        lastBySession = lastBySession,
                                        lastRead = lastRead,
                                        agentState = agentState,
                                        onNewSession = { },
                                        loadProjects = { vm.fleet.listProjects() },
                                        validatePath = { vm.fleet.validatePath(it) },
                                        onNavigate = navTo,
                                        onRename = { id, name -> vm.fleet.rename(id, name) },
                                        onKill = { id -> vm.fleet.kill(id) },
                                        onMute = { id, m -> vm.fleet.setMute(id, m) },
                                        archived = archivedSessions,
                                        onResume = { id -> vm.fleet.resume(id) },
                                        onOpenDraft = { id -> navController.navigate(Route.NewSession(draftId = id)) },
                                        onReorder = { ids -> if (sidebarReorderKind(workspaces) == SidebarReorderKind.SESSIONS) vm.fleet.reorderSessions(ids) else vm.fleet.reorderWorkspaces(ids) },
                                        workspaces = workspaces,
                                        archivedWorkspaces = archivedWorkspaces,
                                        onArchiveWorkspace = { id -> vm.fleet.archiveWorkspace(id) },
                                        onRestoreWorkspace = { id -> vm.fleet.restoreWorkspace(id) },
                                        onNewChatInWorkspace = onNewChatInWorkspace,
                                        hosts = hostViews,
                                        sessionHost = sessionHost,
                                        hostFilter = hostFilter,
                                        onHostFilter = setHostFilter,
                                        onAddHost = { navController.navigate(Route.AddHost) },
                                        onRenameHost = { id, name -> vm.fleet.renameHost(id, name) },
                                        onForgetHost = { id -> vm.fleet.forgetHost(id) },
                                    )
                                }
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(cs.outlineVariant),
                                )
                                Box(Modifier.weight(1f)) {
                                    SessionLauncherScreen(
                                        sessions = activeHostSessions,
                                        home = DevConfig.HOME,
                                        lastBySession = lastBySession,
                                        onBack = { navController.popBackStack() },
                                        loadProjects = { vm.fleet.listProjects() },
                                        validatePath = { vm.fleet.validatePath(it) },
                                        loadModels = { vm.fleet.launcherModels(it) },
                                        loadReasoningLevels = { ag, md -> vm.fleet.launcherReasoning(ag, md) },
                                        loadRepoInfo = { wd, fetch -> vm.fleet.launcherRepoInfo(wd, fetch) },
                                        loadCommands = { ag, wd -> vm.fleet.launcherCommands(ag, wd) },
                                        loadForges = { vm.fleet.listForges() },
                                        searchForge = { vm.fleet.searchForge(it)?.repos.orEmpty() },
                                        cloneForge = { cid, owner, name -> vm.fleet.cloneForge(cid, owner, name) },
                                        createLocalRepo = { vm.fleet.createLocalRepo(it) },
                                        createForge = { cid, name -> vm.fleet.createForge(cid, name) },
                                        loadGlossary = { vm.fleet.fetchGlossary() },
                                        transcribeDraft = { draft -> vm.fleet.transcribeDraft(null, draft) },
                                        transcribeAudio = { bytes, name -> vm.fleet.transcribeAudio(null, bytes, name) },
                                        loadLauncherPrefs = { vm.fleet.launcherPrefs.first() },
                                        onLauncherPrefsChange = { vm.fleet.saveLauncherPrefs(it) },
                                        loadLauncherDraft = { vm.fleet.launcherDraft.first() },
                                        onLauncherDraftChange = { vm.fleet.saveLauncherDraft(it) },
                                        onSubmit = { wd, ag, md, rl, msg, wt, base, staged, replaceDraftId ->
                                            vm.fleet.createSessionWithFirstMessageOrThrow(wd, ag, md, rl, msg, staged, worktree = wt, baseBranch = base, replaceDraftId = replaceDraftId)
                                        },
                                        onSaveDraft = { wd, ag, md, rl, msg, replaceDraftId ->
                                            vm.fleet.createDraftSession(wd, ag, md, msg, reasoningLevel = rl, replaceDraftId = replaceDraftId)
                                        },
                                        initialDraftId = draftId,
                                        initialDraft = draftSession,
                                        onOpenSession = { selected = it; navController.popBackStack() },
                                        hosts = hostViews,
                                        selectedHostId = activeHost,
                                        onSelectHost = { vm.fleet.setActiveHost(it) },
                                        loadAgents = loadHostAgents,
                                    )
                                }
                            }
                        } else {
                            SessionLauncherScreen(
                                sessions = activeHostSessions,
                                home = DevConfig.HOME,
                                lastBySession = lastBySession,
                                onBack = { navController.popBackStack() },
                                loadProjects = { vm.fleet.listProjects() },
                                validatePath = { vm.fleet.validatePath(it) },
                                loadModels = { vm.fleet.launcherModels(it) },
                                loadReasoningLevels = { ag, md -> vm.fleet.launcherReasoning(ag, md) },
                                loadRepoInfo = { wd, fetch -> vm.fleet.launcherRepoInfo(wd, fetch) },
                                loadCommands = { ag, wd -> vm.fleet.launcherCommands(ag, wd) },
                                loadForges = { vm.fleet.listForges() },
                                searchForge = { vm.fleet.searchForge(it)?.repos.orEmpty() },
                                cloneForge = { cid, owner, name -> vm.fleet.cloneForge(cid, owner, name) },
                                createLocalRepo = { vm.fleet.createLocalRepo(it) },
                                createForge = { cid, name -> vm.fleet.createForge(cid, name) },
                                loadGlossary = { vm.fleet.fetchGlossary() },
                                transcribeDraft = { draft -> vm.fleet.transcribeDraft(null, draft) },
                                transcribeAudio = { bytes, name -> vm.fleet.transcribeAudio(null, bytes, name) },
                                loadLauncherPrefs = { vm.fleet.launcherPrefs.first() },
                                onLauncherPrefsChange = { vm.fleet.saveLauncherPrefs(it) },
                                loadLauncherDraft = { vm.fleet.launcherDraft.first() },
                                onLauncherDraftChange = { vm.fleet.saveLauncherDraft(it) },
                                onSubmit = { wd, ag, md, rl, msg, wt, base, staged, replaceDraftId ->
                                            vm.fleet.createSessionWithFirstMessageOrThrow(wd, ag, md, rl, msg, staged, worktree = wt, baseBranch = base, replaceDraftId = replaceDraftId)
                                        },
                                onSaveDraft = { wd, ag, md, rl, msg, replaceDraftId ->
                                            vm.fleet.createDraftSession(wd, ag, md, msg, reasoningLevel = rl, replaceDraftId = replaceDraftId)
                                        },
                                        initialDraftId = draftId,
                                        initialDraft = draftSession,
                                onOpenSession = { selected = it; navController.popBackStack() },
                                hosts = hostViews,
                                selectedHostId = activeHost,
                                onSelectHost = { vm.fleet.setActiveHost(it) },
                                loadAgents = loadHostAgents,
                            )
                        }
                    }
                    composable<Route.AddHost> {
                        AddHostScreen(
                            onBack = { navController.popBackStack() },
                            defaultDeviceName = android.os.Build.MODEL?.ifBlank { "Android phone" } ?: "Android phone",
                            onClaim = { payload, name -> vm.fleet.addHost(payload, name) },
                            onClaimLegacy = { pair -> vm.fleet.addLegacyHost(pair) },
                            onClaimByUrl = { url, name, allowInsecure -> vm.fleet.addHostByUrl(url, name, allowInsecure) },
                            onAdded = {
                                // New host needs its own relay bootstrap → broker /push/device row.
                                SupermuxMessagingService.registerIfPaired(applicationContext)
                                navController.popBackStack()
                            },
                            needsInsecureOptIn = { vm.fleet.urlNeedsInsecureOptIn(it) },
                            // A scan that completed after an activity recreation is re-delivered
                            // here instead of being dropped (see AndroidPlatform.pendingScans).
                            pendingScans = (LocalPlatform.current as? AndroidPlatform)
                                ?.pendingScans() ?: emptyFlow(),
                        )
                    }
                    composable<Route.Settings> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { SettingsScreen(
                            onBack = { navController.popBackStack() },
                            // Personal assistants
                            paLoad = { vm.fleet.personalAssistants() },
                            paCreate = { name, agent, focus -> vm.fleet.createPersonalAssistant(name, agent, focus) },
                            paKill = { vm.fleet.killPersonalAssistant(it) },
                            // Assistant
                            assistantActions = rememberAssistantSettingsActions(vm.fleet),
                            // Agents
                            agentActions = rememberAgentSettingsActions(vm.fleet),
                            // Curator
                            curatorLoad = { vm.fleet.curatorSettings() },
                            curatorSave = { e, h, m, agent, model, reasoning ->
                                vm.fleet.saveCurator(e, h, m, agent, model, reasoning)
                            },
                            curatorRunNow = { vm.fleet.runCuratorNow() },
                            curatorLoadModels = { agent -> vm.fleet.launcherModels(agent) },
                            curatorLoadReasoning = { agent, model -> vm.fleet.launcherReasoning(agent, model) },
                            // Voice
                            voiceLoadModels = { family -> vm.fleet.launcherModels(family) },
                            voiceLoadConfig = { vm.fleet.appConfig() },
                            voiceSaveVoiceStt = { engine -> vm.fleet.saveVoiceStt(engine) },
                            voiceSaveVoiceTts = { engine -> vm.fleet.saveVoiceTts(engine) },
                            voiceSaveVoiceCleanup = { engine, model -> vm.fleet.saveVoiceCleanup(engine, model) },
                            glossaryLoad = { vm.fleet.fetchGlossary() },
                            glossarySave = { vm.fleet.updateGlossary(it) },
                            // Editor / LSP
                            lspLoad = { vm.fleet.lspLoad() },
                            lspToggle = { id, enabled -> vm.fleet.lspToggle(id, enabled) },
                            lspInstall = { vm.fleet.lspInstall(it) },
                            lspInstallLog = vm.fleet.lspInstallLog,
                            lspInstallDone = vm.fleet.lspInstallDone,
                            lspAddCustom = { vm.fleet.lspAddCustom(it.id, it.label, it.command, it.extensions, it.args, it.languageId, it.installCmd) },
                            lspRemoveCustom = { vm.fleet.lspRemoveCustom(it) },
                            // Git hosting
                            gitHostingActions = rememberGitHostingActions(vm.fleet),
                            // System
                            systemActions = rememberSystemSettingsActions(vm.fleet),
                            // Devices + Proxies are hub sections too (same screens as their routes).
                            devicesLoad = { vm.fleet.devices() },
                            deviceAdd = { vm.fleet.addDevice(it) },
                            deviceRevoke = { vm.fleet.revoke(it) },
                            proxiesLoad = { vm.fleet.proxies() },
                            proxySessions = activeHostSessions,
                            proxyCreate = { s, p, d -> vm.fleet.createProxy(s, p, d) },
                            proxySetPublic = { d, pub -> vm.fleet.setProxyPublic(d, pub) },
                            proxyRemove = { vm.fleet.removeProxy(it) },
                            appearanceContent = { back ->
                                AppearanceSettingsPage(
                                    appearance = appearance,
                                    dynamicColor = dynamicColor,
                                    textScale = textScale,
                                    onAppearanceChange = {
                                        appearance = it
                                        prefs.edit().putString("appearance", it.name).apply()
                                    },
                                    onDynamicChange = {
                                        dynamicColor = it
                                        prefs.edit().putBoolean("dynamicColor", it).apply()
                                    },
                                    onTextScaleChange = {
                                        textScale = it
                                        prefs.edit().putFloat("textScale", it).apply()
                                    },
                                    onBack = back,
                                )
                            },
                        ) } }
                    }
                    composable<Route.Usage> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { UsageScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.fleet.usageRaw() },
                            onRedeem = { vm.fleet.redeemCodexReset() },
                        ) } }
                    }
                    composable<Route.Devices> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { DevicesScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.fleet.devices() },
                            onAdd = { vm.fleet.addDevice(it) },
                            onRevoke = { vm.fleet.revoke(it) },
                        ) } }
                    }
                    composable<Route.Archived> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { ArchivedScreen(
                            onBack = { navController.popBackStack() },
                            workspaces = archivedWorkspaces,
                            onRestore = { vm.fleet.restoreWorkspace(it) },
                            home = DevConfig.HOME,
                            useWorkspaces = workspaces.isNotEmpty(),
                            loadArchivedSessions = { vm.fleet.archived() },
                            onResumeSession = { vm.fleet.resume(it) },
                            loadLogs = { vm.fleet.archivedLogs(it) },
                        ) } }
                    }
                    composable<Route.Proxies> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { ProxyScreen(
                            onLoad = { vm.fleet.proxies() },
                            sessions = activeHostSessions,
                            onCreate = { s, p, d -> vm.fleet.createProxy(s, p, d) },
                            onTogglePublic = { d, pub -> vm.fleet.setProxyPublic(d, pub) },
                            onRemove = { vm.fleet.removeProxy(it) },
                            onBack = { navController.popBackStack() },
                        ) } }
                    }
                    composable<Route.Displays> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) {
                            LaunchedEffect(activeHost) { vm.fleet.listDisplays() }
                            DisplaysScreen(
                                onBack = { navController.popBackStack() },
                                displays = vm.fleet.displays,
                                onStart = { sessionName -> vm.fleet.startDisplay(sessionName) },
                                onStop = { id -> vm.fleet.stopDisplay(id) },
                                connectVnc = { vm.fleet.connectVnc(it) },
                                connectScrcpy = { vm.fleet.connectScrcpy(it) },
                            )
                        } }
                    }
                    composable<Route.Appearance> {
                        AppearanceSettingsPage(
                            appearance = appearance,
                            dynamicColor = dynamicColor,
                            textScale = textScale,
                            onAppearanceChange = {
                                appearance = it
                                prefs.edit().putString("appearance", it.name).apply()
                            },
                            onDynamicChange = {
                                dynamicColor = it
                                prefs.edit().putBoolean("dynamicColor", it).apply()
                            },
                            onTextScaleChange = {
                                textScale = it
                                prefs.edit().putFloat("textScale", it).apply()
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                } // Column (banner + NavHost)
            }
        }
    }
}

@Composable
private fun HostScopedPage(
    hosts: List<HostView>,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HostScopePicker(hosts, selectedHostId, onSelectHost)
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * Phone navigation: session list overlays a keep-alive stack of visited [ChatScreen]s.
 */
@Composable
private fun PhoneNavHost(
    selected: String?,
    onSelect: (String) -> Unit,
    onClearSelected: () -> Unit,
    visited: Set<String>,
    onRemoveVisited: (String) -> Unit,
    sessions: List<SessionInfo>,
    messages: Map<String, List<LogEntry>>,
    activityMap: Map<String, List<ActivityEvent>>,
    agentState: Map<String, AgentStatus?>,
    pendingSend: Set<String> = emptySet(),
    commands: Map<String, List<SlashCommand>>,
    commandsResolved: Map<String, Boolean>,
    lastBySession: Map<String, LogEntry?>,
    lastRead: Map<String, String> = emptyMap(),
    archived: List<ArchivedDto> = emptyList(),
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
    onOpenDraft: (String) -> Unit = {},
    onOpenDisplays: () -> Unit,
    hosts: List<dev.supermux.host.HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
    workspaces: List<dev.supermux.proto.WorkspaceDto> = emptyList(),
    archivedWorkspaces: List<dev.supermux.proto.WorkspaceDto> = emptyList(),
) {
    SessionKeepAlivePhoneHost(
        selected = selected,
        onSelect = onSelect,
        onClearSelected = onClearSelected,
        visited = visited,
        onRemoveVisited = onRemoveVisited,
        sessions = sessions,
        messages = messages,
        activityMap = activityMap,
        agentState = agentState,
        pendingSend = pendingSend,
        commands = commands,
        commandsResolved = commandsResolved,
        lastBySession = lastBySession,
        lastRead = lastRead,
        archived = archived,
        vm = vm,
        onNavigate = onNavigate,
        onOpenDraft = onOpenDraft,
        onOpenDisplays = onOpenDisplays,
        hosts = hosts,
        sessionHost = sessionHost,
        hostFilter = hostFilter,
        onHostFilter = onHostFilter,
        onAddHost = onAddHost,
        workspaces = workspaces,
        archivedWorkspaces = archivedWorkspaces,
    )
}
