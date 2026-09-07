package dev.supermux.android

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.supermux.ui.host.AddHostScreen
import dev.supermux.ui.host.HostScopePicker
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.rememberAssistantSettingsActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.ui.settings.rememberVoiceSettingsActions
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
import dev.supermux.ui.session.SessionLauncherScreen
import dev.supermux.ui.session.SessionListMode
import dev.supermux.ui.session.SessionListScreen
import dev.supermux.ui.shell.SessionsRail
import dev.supermux.ui.shell.CompactSidebarDivider
import dev.supermux.android.workspace.SidebarState
import dev.supermux.proto.chatSessionId
import dev.supermux.android.workspace.addViewState
import dev.supermux.android.workspace.workspaceShortcuts
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.ui.display.DisplaysScreen
import dev.supermux.ui.display.rememberDisplayActions
import dev.supermux.android.settings.AndroidSettingsExtra
import dev.supermux.android.settings.AndroidSettingsSection
import dev.supermux.android.session.readGroupByProject
import dev.supermux.android.session.readLegacyCollapsedPaths
import dev.supermux.android.session.writeGroupByProject
import dev.supermux.android.session.seedSessionListPrefs
import dev.supermux.android.settings.seedAppearancePrefs
import dev.supermux.ui.session.rememberLauncherActions
import dev.supermux.ui.session.rememberSessionListActions
import dev.supermux.android.settings.readLegacyAppearancePrefs
import dev.supermux.ui.session.ArchivedScreen
import dev.supermux.ui.session.rememberArchivedActions
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.rememberCuratorSettingsActions
import dev.supermux.ui.settings.rememberDevicesSettingsActions
import dev.supermux.ui.settings.rememberPersonalAssistantsActions
import dev.supermux.ui.settings.rememberProxiesSettingsActions
import dev.supermux.ui.settings.AppearanceSettingsScreen
import dev.supermux.ui.settings.SettingsHub
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.update.AppUpdateBanner
import dev.supermux.android.update.AppUpdateNotifier
import dev.supermux.ui.usage.UsageScreen
import dev.supermux.ui.usage.rememberUsageActions
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.android.DevConfig
import dev.supermux.android.host.HostStores
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.asPairingStore
import dev.supermux.state.cioHttpFactory
import dev.supermux.ui.intro.OnboardingFlow
import dev.supermux.android.push.AndroidPushRegistrar
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.ui.platform.PushRegistrar
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
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
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
        // Cluster G1: both go through the `PushRegistrar` seam (`Platform.push`). The activity is
        // built before any composition, so it constructs the actual directly — the same instance
        // shape `AndroidPlatform` hands every shared caller.
        val push: PushRegistrar = AndroidPushRegistrar(this)
        push.ensureChannel()
        // App self-update progress / failure alerts (status bar during APK download).
        AppUpdateNotifier.ensureChannels(this)
        push.requestPermission()
        intentState.value = intent
        enableEdgeToEdge()

        // The theme's persisted UI preferences. Built here — NOT from the AppViewModel — because
        // the VM must stay below the pairing gate (see the invariant there), and
        // `AndroidSettingsStore(context)` is a process-wide DataStore delegate: this instance and
        // `vm.uiPrefs` read and write exactly the same data.
        //
        // Since cluster E7 the appearance values live in that store too (SettingsKeys.APPEARANCE /
        // DYNAMIC_COLOR / TEXT_SCALE) rather than in this activity's SharedPreferences, so the
        // SHARED Appearance screen can write them on either platform. The old file is drained into
        // the store once — see `AppearancePrefsMigration`.
        //
        // BLOCKING, and before `setContent`, deliberately: a DataStore read is asynchronous, so
        // collecting it with a hardcoded default would paint the first frames of every cold start
        // in the wrong theme (and flip the status-bar icon contrast with them) before the stored
        // value landed. The SharedPreferences this replaced were read synchronously inside
        // `remember` and never did that; this is the same one small disk read, in the same place.
        val settingsStore = AndroidSettingsStore(applicationContext)
        // The session list's collapsed project groups joined them in cluster F1 — same reason
        // (`cmux-session-list` was read synchronously inside `remember`, so an async read would
        // paint the first frames with every group expanded), same one-way non-destructive drain.
        val appearanceSeed: dev.supermux.android.settings.AppearanceSeed
        val collapsedPathsSeed: Set<String>
        // Same read, same place: the group-by-project toggle stays in `cmux-session-list` (a
        // per-device view preference, never synced) and is hoisted here so no frame paints the
        // wrong grouping.
        val groupByProjectSeed = readGroupByProject(applicationContext)
        runBlocking {
            appearanceSeed = seedAppearancePrefs(settingsStore, readLegacyAppearancePrefs(applicationContext))
            collapsedPathsSeed = seedSessionListPrefs(settingsStore, readLegacyCollapsedPaths(applicationContext))
        }

        setContent {
            val themeUiPrefs = remember { UiPrefs(settingsStore) }
            val appearance by themeUiPrefs.appearance(AppearanceMode.SYSTEM)
                .collectAsState(appearanceSeed.appearance)
            val textScale by themeUiPrefs.textScale.collectAsState(appearanceSeed.textScale)
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
                val pushSeam = LocalPlatform.current.push
                LaunchedEffect(paired, pushSeam) {
                    if (paired) pushSeam?.registerIfPaired()
                }
                // Deep-link intake: parse supermux://pair (or a pasted https pair URL) from the
                // current intent. Recomputed when onNewIntent swaps the intent in while foregrounded.
                val currentIntent by intentState
                val deepLink: PairUrl? = remember(currentIntent) {
                    currentIntent?.data?.toString()?.let { PairUrl.parse(it, store.loadBaseUrl()) }
                }

                if (!paired) {
                    // Cluster G6: one shared intro + pairing flow. The state machine is
                    // `:shared`'s PairingState over the same encrypted store the old
                    // `PairingViewModel` used; it is remembered here (not a ViewModel) so it
                    // stays below the pairing gate, and its probe client is released on dispose.
                    val pairingScope = rememberCoroutineScope()
                    val pairing = remember {
                        PairingState(
                            store.asPairingStore(),
                            pairingScope,
                            httpFactory = { cioHttpFactory()(null) },
                        )
                    }
                    DisposableEffect(Unit) { onDispose { pairing.close() } }
                    OnboardingFlow(
                        pairing = pairing,
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

                // ── Cluster F1 holders + the shared-store collapsed groups ─────────────────────
                // Every broker call the list and the launcher make now arrives in one holder each,
                // routed per session/active host by the fleet. The collapsed set starts at the
                // value read synchronously in `onCreate` and is written back to the SAME key
                // desktop's sidebar uses.
                val listActions = rememberSessionListActions(vm.fleet)
                val launcherActions = rememberLauncherActions(
                    vm.fleet,
                    onOpenSession = { selected = it; navController.popBackStack() },
                )
                var collapsedPaths by remember { mutableStateOf(collapsedPathsSeed) }
                var groupByProject by remember { mutableStateOf(groupByProjectSeed) }
                val onGroupByProjectChange: (Boolean) -> Unit = { value ->
                    groupByProject = value
                    writeGroupByProject(applicationContext, value)
                }
                val prefsScope = rememberCoroutineScope()
                val onCollapsedPathsChange: (Set<String>) -> Unit = { paths ->
                    collapsedPaths = paths
                    prefsScope.launch { vm.uiPrefs.putCollapsedProjectPaths(paths) }
                }
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
                            pushSeam?.cancelForSession(id)
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
                                                onOpenSession = { _, sid -> selected = sid },
                                                actions = listActions,
                                                onNavigate = navTo,
                                                archived = archivedSessions,
                                                onOpenDraft = { id -> navController.navigate(Route.NewSession(draftId = id)) },
                                                onReorder = { ids -> if (sidebarReorderKind(workspaces) == SidebarReorderKind.SESSIONS) listActions.reorderSessions(ids) else listActions.reorderWorkspaces(ids) },
                                                workspaces = workspaces,
                                                archivedWorkspaces = archivedWorkspaces,
                                                onNewChatInWorkspace = onNewChatInWorkspace,
                                                initialCollapsedPaths = collapsedPaths,
                                                onCollapsedPathsChange = onCollapsedPathsChange,
                                                hosts = hostViews,
                                                sessionHost = sessionHost,
                                                hostFilter = hostFilter,
                                                onHostFilter = setHostFilter,
                                                onAddHost = { navController.navigate(Route.AddHost) },
                                                initialGroupByProject = groupByProject,
                                                onGroupByProjectChange = onGroupByProjectChange,
                                                mode = SessionListMode.Fleet,
                                                openWorkspaceByWorkspaceId = false,
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
                                initialCollapsedPaths = collapsedPaths,
                                onCollapsedPathsChange = onCollapsedPathsChange,
                                initialGroupByProject = groupByProject,
                                onGroupByProjectChange = onGroupByProjectChange,
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
                                        onOpenSession = { _, sid -> selected = sid; navController.popBackStack() },
                                        actions = listActions,
                                        onNavigate = navTo,
                                        archived = archivedSessions,
                                        onOpenDraft = { id -> navController.navigate(Route.NewSession(draftId = id)) },
                                        onReorder = { ids -> if (sidebarReorderKind(workspaces) == SidebarReorderKind.SESSIONS) listActions.reorderSessions(ids) else listActions.reorderWorkspaces(ids) },
                                        workspaces = workspaces,
                                        archivedWorkspaces = archivedWorkspaces,
                                        onNewChatInWorkspace = onNewChatInWorkspace,
                                        initialCollapsedPaths = collapsedPaths,
                                        onCollapsedPathsChange = onCollapsedPathsChange,
                                        hosts = hostViews,
                                        sessionHost = sessionHost,
                                        hostFilter = hostFilter,
                                        onHostFilter = setHostFilter,
                                        onAddHost = { navController.navigate(Route.AddHost) },
                                        initialGroupByProject = groupByProject,
                                        onGroupByProjectChange = onGroupByProjectChange,
                                        mode = SessionListMode.Fleet,
                                        openWorkspaceByWorkspaceId = false,
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
                                        actions = launcherActions,
                                        loadPrefs = { vm.uiPrefs.launcherPrefs.first() },
                                        onPrefsChange = { prefsScope.launch { vm.uiPrefs.putLauncherPrefs(it) } },
                                        loadDraft = { vm.uiPrefs.launcherDraft.first() },
                                        onDraftChange = { prefsScope.launch { vm.uiPrefs.putLauncherDraft(it) } },
                                        onClearDraft = { prefsScope.launch { vm.uiPrefs.clearLauncherDraft() } },
                                        onSubmit = { wd, ag, md, rl, msg, staged, wt, base, replaceDraftId ->
                                            launcherActions.createSessionWithFirstMessage(wd, ag, md, rl, msg, staged, wt, base, replaceDraftId)
                                        },
                                        onSaveDraft = { wd, ag, md, rl, msg, replaceDraftId ->
                                            launcherActions.createDraftSession(wd, ag, md, rl, msg, replaceDraftId)
                                        },
                                        onOpenSession = launcherActions.openSession,
                                        initialDraftId = draftId,
                                        initialDraft = draftSession,
                                        hosts = hostViews,
                                        selectedHost = activeHost,
                                        // A route, not a pane: the screen owns its own bar + Back
                                        // at every width (cluster E's chrome rule).
                                        standalone = true,
                                    )
                                }
                            }
                        } else {
                            SessionLauncherScreen(
                                sessions = activeHostSessions,
                                home = DevConfig.HOME,
                                lastBySession = lastBySession,
                                onBack = { navController.popBackStack() },
                                actions = launcherActions,
                                loadPrefs = { vm.uiPrefs.launcherPrefs.first() },
                                onPrefsChange = { prefsScope.launch { vm.uiPrefs.putLauncherPrefs(it) } },
                                loadDraft = { vm.uiPrefs.launcherDraft.first() },
                                onDraftChange = { prefsScope.launch { vm.uiPrefs.putLauncherDraft(it) } },
                                onClearDraft = { prefsScope.launch { vm.uiPrefs.clearLauncherDraft() } },
                                onSubmit = { wd, ag, md, rl, msg, staged, wt, base, replaceDraftId ->
                                    launcherActions.createSessionWithFirstMessage(wd, ag, md, rl, msg, staged, wt, base, replaceDraftId)
                                },
                                onSaveDraft = { wd, ag, md, rl, msg, replaceDraftId ->
                                    launcherActions.createDraftSession(wd, ag, md, rl, msg, replaceDraftId)
                                },
                                onOpenSession = launcherActions.openSession,
                                initialDraftId = draftId,
                                initialDraft = draftSession,
                                hosts = hostViews,
                                selectedHost = activeHost,
                                // A route, not a pane: the screen owns its own bar + Back
                                // at every width (cluster E's chrome rule).
                                standalone = true,
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
                                pushSeam?.registerIfPaired()
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
                        // The shared hub owns everything now: the index + push on a phone, the
                        // rail + detail on a tablet, the pushed detail's chrome (every Android
                        // settings page became a shared screen in E7, so none paints its own) and
                        // the system-back that pops a pushed detail before leaving Settings.
                        // `AndroidSettingsSections.kt` is the only wiring left.
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) {
                            // `Route.Settings.section` is deliberately NOT read here: Android has
                            // always opened its hub on Personal assistants (the index's first row),
                            // and nothing on this app navigates with a section. Desktop, which
                            // does, keeps the section on its route instead.
                            var section by remember { mutableStateOf(SettingsSection.PersonalAssistants) }
                            SettingsHub(
                                section = section,
                                onSectionChange = { section = it },
                                onBack = { navController.popBackStack() },
                                // Already inside `key(activeHost)` above, so the hub's own host
                                // scoping has nothing left to reset — one owner of that behaviour.
                                hostKey = null,
                                extraContent = { extra, scope -> AndroidSettingsExtra(extra, scope) },
                            ) { s, scope -> AndroidSettingsSection(s, scope, vm.fleet) }
                        } }
                    }
                    composable<Route.Usage> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { UsageScreen(
                            actions = rememberUsageActions(vm.fleet),
                            onBack = { navController.popBackStack() },
                            // Its own destination, not desktop's anchored popover: it paints the
                            // title and Back at every width (a phone in landscape is Medium).
                            standalone = true,
                        ) } }
                    }
                    composable<Route.Devices> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { DevicesSettingsScreen(
                            actions = rememberDevicesSettingsActions(vm.fleet),
                            onBack = { navController.popBackStack() },
                            // Its own destination, not a hub section: it paints the title and Back
                            // at every width (a phone in landscape is Medium, not Compact).
                            standalone = true,
                        ) } }
                    }
                    composable<Route.Archived> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { ArchivedScreen(
                            actions = rememberArchivedActions(vm.fleet),
                            home = DevConfig.HOME,
                            onBack = { navController.popBackStack() },
                            standalone = true,
                        ) } }
                    }
                    composable<Route.Proxies> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) { ProxiesSettingsScreen(
                            actions = rememberProxiesSettingsActions(vm.fleet),
                            onBack = { navController.popBackStack() },
                            standalone = true,
                        ) } }
                    }
                    composable<Route.Displays> {
                        HostScopedPage(hostViews, activeHost, vm.fleet::setActiveHost) { key(activeHost) {
                            DisplaysScreen(
                                actions = rememberDisplayActions(vm.fleet),
                                onBack = { navController.popBackStack() },
                                standalone = true,
                            )
                        } }
                    }
                    composable<Route.Appearance> {
                        // Standalone destination (deep link / the sidebar shortcut): the shared
                        // screen paints its own title and Back at every width. It reads and writes
                        // the same SettingsKeys the theme above collects, so a change here
                        // repaints the app live with nothing threaded through this file.
                        AppearanceSettingsScreen(
                            onBack = { navController.popBackStack() },
                            standalone = true,
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
    initialCollapsedPaths: Set<String> = emptySet(),
    onCollapsedPathsChange: (Set<String>) -> Unit = {},
    initialGroupByProject: Boolean = false,
    onGroupByProjectChange: (Boolean) -> Unit = {},
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
        initialCollapsedPaths = initialCollapsedPaths,
        onCollapsedPathsChange = onCollapsedPathsChange,
        initialGroupByProject = initialGroupByProject,
        onGroupByProjectChange = onGroupByProjectChange,
    )
}
