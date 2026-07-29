package dev.supermux.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.navigation.compose.rememberNavController
import dev.supermux.android.host.AddHostScreen
import dev.supermux.android.host.HostScopePicker
import dev.supermux.android.host.HostView
import dev.supermux.android.nav.AddHost
import dev.supermux.android.nav.Appearance
import dev.supermux.android.nav.Archived
import dev.supermux.android.nav.Devices
import dev.supermux.android.nav.Displays
import dev.supermux.android.nav.Home
import dev.supermux.android.nav.NewSession
import dev.supermux.android.nav.Proxies
import dev.supermux.android.nav.Settings
import dev.supermux.android.nav.Usage
import dev.supermux.android.session.SessionKeepAlivePhoneHost
import dev.supermux.android.session.SessionKeepAliveTabletHost
import dev.supermux.android.session.rememberVisitedSessions
import dev.supermux.android.session.SessionLauncherScreen
import dev.supermux.android.session.SessionListScreen
import dev.supermux.android.workspace.SessionsRail
import dev.supermux.android.workspace.SidebarDivider
import dev.supermux.android.workspace.WorkspaceLayout
import dev.supermux.android.workspace.isWorkspaceWidth
import dev.supermux.android.workspace.workspaceShortcuts
import dev.supermux.android.display.DisplaysScreen
import dev.supermux.android.settings.AppearanceSettingsPage
import dev.supermux.android.settings.ArchivedScreen
import dev.supermux.android.settings.DevicesScreen
import dev.supermux.android.settings.ProxyScreen
import dev.supermux.android.settings.SettingsScreen
import dev.supermux.android.update.AppUpdateBanner
import dev.supermux.android.settings.UsageScreen
import dev.supermux.android.theme.AppearanceMode
import dev.supermux.android.theme.SupermuxTheme
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
            var dynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamicColor", ThemeDefaults.DYNAMIC_COLOR_ENABLED)) }
            var textScale by remember { mutableStateOf(prefs.getFloat("textScale", 1f)) }
            SupermuxTheme(appearance = appearance, dynamicEnabled = dynamicColor, textScale = textScale) {
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
                    return@SupermuxTheme
                }

                // Multi-host (spec §5): the VM owns N per-host connections from the PairedHostStore,
                // re-running the idempotent single-host→PairedHost[0] migration on init so existing
                // users — and the session where onboarding just paired — always have a host to drive.
                val vm: AppViewModel = viewModel(factory = AppViewModel.factory(application))
                val sessions by vm.sessions.collectAsStateWithLifecycle()
                val archivedSessions by vm.archivedSessions.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val activity by vm.activity.collectAsStateWithLifecycle()
                val agentState by vm.agentState.collectAsStateWithLifecycle()
                val pendingSend by vm.pendingSend.collectAsStateWithLifecycle()
                val commands by vm.commands.collectAsStateWithLifecycle()
                val commandsResolved by vm.commandsResolved.collectAsStateWithLifecycle()
                // Merged-fleet state: the paired hosts (identity + reachability), the sessionId→host
                // owner index (per-row badges), and the persisted host-filter chip selection.
                val hostViews by vm.hostViews.collectAsStateWithLifecycle()
                val sessionHost by vm.sessionHost.collectAsStateWithLifecycle()
                val activeHost by vm.activeHost.collectAsStateWithLifecycle()
                val activeHostSessions = remember(sessions, sessionHost, hostViews, activeHost) {
                    if (hostViews.size >= 2 && activeHost != null) {
                        sessions.filter { sessionHost[it.id] == activeHost }
                    } else {
                        sessions
                    }
                }
                var hostFilter by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { hostFilter = vm.loadHostFilter() }
                val setHostFilter: (String?) -> Unit = { hostFilter = it; vm.saveHostFilter(it) }
                val loadHostAgents: suspend () -> List<String> = { vm.agentStatuses().filter { it.installed }.map { it.kind } }
                val lastBySession = messages.mapValues { it.value.lastOrNull() }
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                val liveSessionIds = remember(sessions) { sessions.map { it.id }.toSet() }
                val (visitedSessions, removeVisited) = rememberVisitedSessions(selected, liveSessionIds)
                // Shared multi-pane layout for wide screens — one instance across all sessions,
                // saved across config-change/process-death, pruned when the broker drops a session.
                val workspaceLayout = rememberSaveable(saver = WorkspaceLayout.Saver) { WorkspaceLayout() }
                LaunchedEffect(liveSessionIds) { workspaceLayout.prune(liveSessionIds) }
                // A session resumed from archive arrives via `session_added` (no history), so its
                // transcript would be empty until the next snapshot/restart. Seed it whenever a chat
                // is opened — a no-op for sessions the snapshot already populated. (iOS parity:
                // ChatPane.loadPane → BrokerSession.ensureMessagesLoaded.)
                LaunchedEffect(selected) {
                    selected?.let {
                        sessionHost[it]?.let(vm::setActiveHost)
                        vm.ensureMessagesLoaded(it)
                        // Opening a chat clears its (grouped) notifications — parity with iOS.
                        SupermuxMessagingService.cancelForSession(applicationContext, it)
                    }
                }
                // A tapped push carries the chat id — open that chat (parity with iOS PushRouter);
                // the clear-on-open effect above then wipes its notifications. Keyed on the intent so
                // a fresh tap while foregrounded (onNewIntent swaps intentState) re-opens it.
                LaunchedEffect(currentIntent) {
                    currentIntent?.getStringExtra(SupermuxMessagingService.EXTRA_SESSION_ID)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { selected = it }
                }
                // Report which chat is foreground so the broker suppresses a push for the chat
                // you're looking at (parity with iOS/web). Visible = the activity is ≥ STARTED.
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
                LaunchedEffect(selected, appVisible) { vm.updateViewing(selected, appVisible) }

                // Wide = available width ≥600dp (the shared isWorkspaceWidth predicate /
                // WORKSPACE_MIN_WIDTH_DP). ">=600" (not only Expanded ≥840) means the unfolded
                // Galaxy Z Fold 7 qualifies; narrower (phones / folded cover) keeps single-pane chat.
                val wide = isWorkspaceWidth(LocalConfiguration.current.screenWidthDp)
                val cs = MaterialTheme.colorScheme

                val navController = rememberNavController()
                // Maps the screens' legacy string-route callbacks to type-safe NavHost destinations.
                val navTo: (String) -> Unit = { dest ->
                    when (dest) {
                        "new" -> navController.navigate(NewSession())
                        "settings" -> navController.navigate(Settings)
                        "usage" -> navController.navigate(Usage)
                        "devices" -> navController.navigate(Devices)
                        "archived" -> navController.navigate(Archived)
                        "proxies" -> navController.navigate(Proxies)
                        "appearance" -> navController.navigate(Appearance)
                        "addhost" -> navController.navigate(AddHost)
                        // "displays"/"theme"/"list" → no destinations (stubs)
                    }
                }

                Column(Modifier.fillMaxSize()) {
                // App self-update strip (versions.json). One-tap install for sideloaded APKs.
                AppUpdateBanner(
                    onOpenPage = { navController.navigate(Settings) },
                )
                NavHost(
                    navController = navController,
                    startDestination = Home,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTagsAsResourceId = true },
                ) {
                    // ── Home: list ↔ chat (keep-alive). Bodies are the old `else`-branch, verbatim,
                    //    with `route = …` swapped for nav. The keep-alive / shared-element / predictive-back
                    //    code lives inside the hosts below and is unchanged. ──
                    composable<Home> {
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
                            val collapsed = workspaceLayout.sidebarCollapsed
                            val sidebarWidth by animateDpAsState(
                                targetValue = if (collapsed) 64.dp else workspaceLayout.sidebarWidth,
                                animationSpec = if (resizing) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                                label = "sidebarWidth",
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .focusRequester(focusRequester)
                                    .workspaceShortcuts(
                                        layout = workspaceLayout,
                                        selectedId = selected,
                                        onNewSession = { navController.navigate(NewSession()) },
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
                                            onExpand = { workspaceLayout.sidebarCollapsed = false },
                                            onNewSession = { navController.navigate(NewSession()) },
                                        )
                                    } else {
                                        // requiredWidth keeps the list at its full width while the
                                        // narrower animating parent clips it during the reveal.
                                        Box(Modifier.requiredWidth(workspaceLayout.sidebarWidth).fillMaxHeight()) {
                                            SessionListScreen(
                                                sessions = sessions,
                                                home = DevConfig.HOME,
                                                activeId = selected,
                                                onOpen = { selected = it },
                                                lastBySession = lastBySession,
                                                agentState = agentState,
                                                onNewSession = { navController.navigate(NewSession()) },
                                                loadProjects = { vm.listProjects() },
                                                validatePath = { vm.validatePath(it) },
                                                onNavigate = navTo,
                                                onRename = { id, name -> vm.rename(id, name) },
                                                onKill = { id -> vm.kill(id) },
                                                onMute = { id, m -> vm.setMute(id, m) },
                                                archived = archivedSessions,
                                                onResume = { id -> vm.resume(id) },
                                                onOpenDraft = { id -> navController.navigate(NewSession(draftId = id)) },
                                                onReorder = { ids -> vm.reorderSessions(ids) },
                                                hosts = hostViews,
                                                sessionHost = sessionHost,
                                                hostFilter = hostFilter,
                                                onHostFilter = setHostFilter,
                                                onAddHost = { navController.navigate(AddHost) },
                                                onRenameHost = { id, name -> vm.renameHost(id, name) },
                                                onForgetHost = { id -> vm.forgetHost(id) },
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
                                        layout = workspaceLayout,
                                        onNavigate = navTo,
                                        onOpenDisplays = { navController.navigate(Displays) },
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
                                  SidebarDivider(
                                      modifier = Modifier.offset(x = sidebarWidth - 7.dp),
                                      onDragDelta = { d ->
                                          workspaceLayout.setSidebarWidth(workspaceLayout.sidebarWidth + d)
                                      },
                                      onCollapse = { workspaceLayout.sidebarCollapsed = true },
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
                                archived = archivedSessions,
                                vm = vm,
                                onNavigate = navTo,
                                onOpenDraft = { id -> navController.navigate(NewSession(draftId = id)) },
                                onOpenDisplays = { navController.navigate(Displays) },
                                hosts = hostViews,
                                sessionHost = sessionHost,
                                hostFilter = hostFilter,
                                onHostFilter = setHostFilter,
                                onAddHost = { navController.navigate(AddHost) },
                            )
                        }
                    }
                    // ── New-session launcher (old "new" branch, verbatim, route→nav) ──
                    composable<NewSession> { entry ->
                        val ns = entry.toRoute<NewSession>()
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
                                        agentState = agentState,
                                        onNewSession = { },
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        onNavigate = navTo,
                                        onRename = { id, name -> vm.rename(id, name) },
                                        onKill = { id -> vm.kill(id) },
                                        onMute = { id, m -> vm.setMute(id, m) },
                                        archived = archivedSessions,
                                        onResume = { id -> vm.resume(id) },
                                        onOpenDraft = { id -> navController.navigate(NewSession(draftId = id)) },
                                        onReorder = { ids -> vm.reorderSessions(ids) },
                                        hosts = hostViews,
                                        sessionHost = sessionHost,
                                        hostFilter = hostFilter,
                                        onHostFilter = setHostFilter,
                                        onAddHost = { navController.navigate(AddHost) },
                                        onRenameHost = { id, name -> vm.renameHost(id, name) },
                                        onForgetHost = { id -> vm.forgetHost(id) },
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
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        loadModels = { vm.launcherModels(it) },
                                        loadReasoningLevels = { ag, md -> vm.launcherReasoning(ag, md) },
                                        loadRepoInfo = { wd, fetch -> vm.launcherRepoInfo(wd, fetch) },
                                        loadCommands = { ag, wd -> vm.launcherCommands(ag, wd) },
                                        loadForges = { vm.listForges() },
                                        searchForge = { vm.searchForge(it) },
                                        cloneForge = { cid, owner, name -> vm.cloneForge(cid, owner, name) },
                                        createLocalRepo = { vm.createLocalRepo(it) },
                                        createForge = { cid, name -> vm.createForge(cid, name) },
                                        loadGlossary = { vm.fetchGlossary() },
                                        transcribeDraft = { draft -> vm.transcribeDraft(null, draft) },
                                        transcribeAudio = { bytes, name -> vm.transcribeAudio(null, bytes, name) },
                                        loadLauncherPrefs = { vm.loadLauncherPrefs() },
                                        onLauncherPrefsChange = { vm.saveLauncherPrefs(it) },
                                        loadLauncherDraft = { vm.loadLauncherDraft() },
                                        onLauncherDraftChange = { vm.saveLauncherDraft(it) },
                                        onSubmit = { wd, ag, md, rl, msg, wt, base, staged, replaceDraftId ->
                                            vm.createSessionWithFirstMessage(wd, ag, md, msg, staged, worktree = wt, baseBranch = base, reasoningLevel = rl, replaceDraftId = replaceDraftId)
                                        },
                                        onSaveDraft = { wd, ag, md, rl, msg, replaceDraftId ->
                                            vm.createDraftSession(wd, ag, md, msg, reasoningLevel = rl, replaceDraftId = replaceDraftId)
                                        },
                                        initialDraftId = draftId,
                                        initialDraft = draftSession,
                                        onOpenSession = { selected = it; navController.popBackStack() },
                                        hosts = hostViews,
                                        selectedHostId = activeHost,
                                        onSelectHost = { vm.setActiveHost(it) },
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
                                loadProjects = { vm.listProjects() },
                                validatePath = { vm.validatePath(it) },
                                loadModels = { vm.launcherModels(it) },
                                loadReasoningLevels = { ag, md -> vm.launcherReasoning(ag, md) },
                                loadRepoInfo = { wd, fetch -> vm.launcherRepoInfo(wd, fetch) },
                                loadCommands = { ag, wd -> vm.launcherCommands(ag, wd) },
                                loadForges = { vm.listForges() },
                                searchForge = { vm.searchForge(it) },
                                cloneForge = { cid, owner, name -> vm.cloneForge(cid, owner, name) },
                                createLocalRepo = { vm.createLocalRepo(it) },
                                createForge = { cid, name -> vm.createForge(cid, name) },
                                loadGlossary = { vm.fetchGlossary() },
                                transcribeDraft = { draft -> vm.transcribeDraft(null, draft) },
                                transcribeAudio = { bytes, name -> vm.transcribeAudio(null, bytes, name) },
                                loadLauncherPrefs = { vm.loadLauncherPrefs() },
                                onLauncherPrefsChange = { vm.saveLauncherPrefs(it) },
                                loadLauncherDraft = { vm.loadLauncherDraft() },
                                onLauncherDraftChange = { vm.saveLauncherDraft(it) },
                                onSubmit = { wd, ag, md, rl, msg, wt, base, staged, replaceDraftId ->
                                            vm.createSessionWithFirstMessage(wd, ag, md, msg, staged, worktree = wt, baseBranch = base, reasoningLevel = rl, replaceDraftId = replaceDraftId)
                                        },
                                onSaveDraft = { wd, ag, md, rl, msg, replaceDraftId ->
                                            vm.createDraftSession(wd, ag, md, msg, reasoningLevel = rl, replaceDraftId = replaceDraftId)
                                        },
                                        initialDraftId = draftId,
                                        initialDraft = draftSession,
                                onOpenSession = { selected = it; navController.popBackStack() },
                                hosts = hostViews,
                                selectedHostId = activeHost,
                                onSelectHost = { vm.setActiveHost(it) },
                                loadAgents = loadHostAgents,
                            )
                        }
                    }
                    composable<AddHost> {
                        AddHostScreen(
                            onBack = { navController.popBackStack() },
                            defaultDeviceName = android.os.Build.MODEL?.ifBlank { "Android phone" } ?: "Android phone",
                            onClaim = { payload, name -> vm.addHost(payload, name) },
                            onClaimLegacy = { pair -> vm.addLegacyHost(pair) },
                            onClaimByUrl = { url, name, allowInsecure -> vm.addHostByUrl(url, name, allowInsecure) },
                            onAdded = {
                                // New host needs its own relay bootstrap → broker /push/device row.
                                SupermuxMessagingService.registerIfPaired(applicationContext)
                                navController.popBackStack()
                            },
                            needsInsecureOptIn = { vm.urlNeedsInsecureOptIn(it) },
                        )
                    }
                    composable<Settings> {
                        HostScopedPage(hostViews, activeHost, vm::setActiveHost) { key(activeHost) { SettingsScreen(
                            onBack = { navController.popBackStack() },
                            // Personal assistants
                            paLoad = { vm.personalAssistants() },
                            paCreate = { name, agent, focus -> vm.createPersonalAssistant(name, agent, focus) },
                            paKill = { vm.killPersonalAssistant(it) },
                            // Assistant
                            assistantLoad = { vm.assistantLoad() },
                            assistantSave = { paName, soul -> vm.assistantSave(paName, soul) },
                            // Agents
                            agentStatuses = { vm.agentStatuses() },
                            agentStartLogin = { vm.agentStartLogin(it) },
                            agentPollLogin = { vm.agentPollLogin(it) },
                            agentSendCode = { kind, code -> vm.agentSendCode(kind, code) },
                            agentCancelLogin = { vm.agentCancelLogin(it) },
                            agentSaveSecret = { kind, value -> vm.agentSaveSecret(kind, value) },
                            openCodeProviders = { vm.openCodeProviders() },
                            openCodeSetKey = { id, key -> vm.openCodeSetKey(id, key) },
                            openCodeStartOAuth = { id, method -> vm.openCodeStartOAuth(id, method) },
                            openCodeFinishOAuth = { id, method, code -> vm.openCodeFinishOAuth(id, method, code) },
                            // Curator
                            curatorLoad = { vm.curatorSettings() },
                            curatorSave = { e, h, m -> vm.saveCurator(e, h, m) },
                            curatorRunNow = { vm.runCuratorNow() },
                            // Voice
                            voiceLoadModels = { family -> vm.launcherModels(family) },
                            voiceLoadConfig = { vm.config() },
                            voiceSaveVoiceStt = { engine -> vm.saveVoiceStt(engine) },
                            voiceSaveVoiceCleanup = { engine, model -> vm.saveVoiceCleanup(engine, model) },
                            glossaryLoad = { vm.fetchGlossary() },
                            glossarySave = { vm.updateGlossary(it) },
                            // Editor / LSP
                            lspLoad = { vm.lspLoad() },
                            lspToggle = { id, enabled -> vm.lspToggle(id, enabled) },
                            lspInstall = { vm.lspInstall(it) },
                            lspInstallLog = vm.lspInstallLog,
                            lspInstallDone = vm.lspInstallDone,
                            lspAddCustom = { vm.lspAddCustom(it) },
                            lspRemoveCustom = { vm.lspRemoveCustom(it) },
                            // Git hosting
                            forgesLoad = { vm.forgesLoad() },
                            forgeAdd = { kind, token, host, transport -> vm.forgeAdd(kind, token, host, transport) },
                            forgeImport = { kind, transport -> vm.forgeImport(kind, transport) },
                            forgeRemove = { vm.forgeRemove(it) },
                            // System
                            updateStatus = { vm.updateStatus() },
                            runUpdate = { vm.runUpdate() },
                            restartBroker = { vm.restartBroker() },
                        ) } }
                    }
                    composable<Usage> {
                        HostScopedPage(hostViews, activeHost, vm::setActiveHost) { key(activeHost) { UsageScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.usage() },
                            onRedeem = { vm.redeemCodexReset() },
                        ) } }
                    }
                    composable<Devices> {
                        HostScopedPage(hostViews, activeHost, vm::setActiveHost) { key(activeHost) { DevicesScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.devices() },
                            onAdd = { vm.addDevice(it) },
                            onRevoke = { vm.revoke(it) },
                        ) } }
                    }
                    composable<Archived> {
                        HostScopedPage(hostViews, activeHost, vm::setActiveHost) { key(activeHost) { ArchivedScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.archived() },
                            onResume = { vm.resume(it) },
                            home = DevConfig.HOME,
                            loadLogs = { vm.archivedLogs(it) },
                        ) } }
                    }
                    composable<Proxies> {
                        HostScopedPage(hostViews, activeHost, vm::setActiveHost) { key(activeHost) { ProxyScreen(
                            onLoad = { vm.proxies() },
                            sessions = activeHostSessions,
                            onCreate = { s, p, d -> vm.createProxy(s, p, d) },
                            onTogglePublic = { d, pub -> vm.setProxyPublic(d, pub) },
                            onRemove = { vm.removeProxy(it) },
                            onBack = { navController.popBackStack() },
                        ) } }
                    }
                    composable<Displays> {
                        HostScopedPage(hostViews, activeHost, vm::setActiveHost) { key(activeHost) {
                            LaunchedEffect(activeHost) { vm.listDisplays() }
                            DisplaysScreen(
                                onBack = { navController.popBackStack() },
                                displays = vm.displays,
                                onStart = { sessionName -> vm.startDisplay(sessionName) },
                                onStop = { id -> vm.stopDisplay(id) },
                                connectVnc = { vm.connectVnc(it) },
                                connectScrcpy = { vm.connectScrcpy(it) },
                            )
                        } }
                    }
                    composable<Appearance> {
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
    archived: List<ArchivedDto> = emptyList(),
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
    onOpenDraft: (String) -> Unit = {},
    onOpenDisplays: () -> Unit,
    hosts: List<dev.supermux.android.host.HostView> = emptyList(),
    sessionHost: Map<String, String> = emptyMap(),
    hostFilter: String? = null,
    onHostFilter: (String?) -> Unit = {},
    onAddHost: () -> Unit = {},
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
    )
}
