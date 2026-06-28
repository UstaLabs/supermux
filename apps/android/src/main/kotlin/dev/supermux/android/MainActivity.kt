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
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import dev.supermux.android.display.DisplaysScreen
import dev.supermux.android.settings.AppearanceSettingsPage
import dev.supermux.android.settings.ArchivedScreen
import dev.supermux.android.settings.DevicesScreen
import dev.supermux.android.settings.ProxyScreen
import dev.supermux.android.settings.SettingsScreen
import dev.supermux.android.settings.UsageScreen
import dev.supermux.android.theme.AppearanceMode
import dev.supermux.android.theme.SupermuxTheme
import dev.supermux.android.DevConfig
import dev.supermux.android.pairing.OnboardingScreen
import dev.supermux.android.push.PushPermission
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.auth.SecureTokenStore
import dev.supermux.auth.SecureTokenStoreContext
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

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
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
            var dynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamicColor", true)) }
            SupermuxTheme(appearance = appearance, dynamicEnabled = dynamicColor) {
                val store = remember { SecureTokenStore() }
                // Debug-only: seed token+baseUrl on debuggable builds so the already-paired
                // emulator boots past the gate (no-op on release / when DEBUG_TOKEN is empty).
                remember { DevConfig.seedDebugPairingIfEmpty(applicationContext); Unit }
                // Paired ⇔ the encrypted store holds BOTH a token and a broker base URL.
                var paired by rememberSaveable {
                    mutableStateOf(
                        store.load()?.isNotBlank() == true && store.loadBaseUrl()?.isNotBlank() == true,
                    )
                }
                // Deep-link intake: parse supermux://pair (or a pasted https pair URL) from the
                // current intent. Recomputed when onNewIntent swaps the intent in while foregrounded.
                val currentIntent by intentState
                val deepLink: PairUrl? = remember(currentIntent) {
                    currentIntent?.data?.toString()?.let { PairUrl.parse(it, store.loadBaseUrl()) }
                }

                if (!paired) {
                    OnboardingScreen(
                        onPaired = { paired = true },
                        initialDeepLink = deepLink,
                    )
                    return@SupermuxTheme
                }

                val brokerUrl = remember { store.loadBaseUrl()!! }   // gate guarantees both are present
                val token = remember { store.load()!! }
                val vm: AppViewModel = viewModel(factory = AppViewModel.factory(application, brokerUrl, token))
                val sessions by vm.sessions.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val activity by vm.activity.collectAsStateWithLifecycle()
                val agentState by vm.agentState.collectAsStateWithLifecycle()
                val commands by vm.commands.collectAsStateWithLifecycle()
                val commandsResolved by vm.commandsResolved.collectAsStateWithLifecycle()
                val lastBySession = messages.mapValues { it.value.lastOrNull() }
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                val liveSessionIds = remember(sessions) { sessions.map { it.id }.toSet() }
                val (visitedSessions, removeVisited) = rememberVisitedSessions(selected, liveSessionIds)
                // A session resumed from archive arrives via `session_added` (no history), so its
                // transcript would be empty until the next snapshot/restart. Seed it whenever a chat
                // is opened — a no-op for sessions the snapshot already populated. (iOS parity:
                // ChatPane.loadPane → BrokerSession.ensureMessagesLoaded.)
                LaunchedEffect(selected) { selected?.let { vm.ensureMessagesLoaded(it) } }

                val windowSizeClass = calculateWindowSizeClass(this)
                val expanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
                val cs = MaterialTheme.colorScheme

                val navController = rememberNavController()
                // Maps the screens' legacy string-route callbacks to type-safe NavHost destinations.
                val navTo: (String) -> Unit = { dest ->
                    when (dest) {
                        "new" -> navController.navigate(NewSession)
                        "settings" -> navController.navigate(Settings)
                        "usage" -> navController.navigate(Usage)
                        "devices" -> navController.navigate(Devices)
                        "archived" -> navController.navigate(Archived)
                        "proxies" -> navController.navigate(Proxies)
                        "appearance" -> navController.navigate(Appearance)
                        // "displays"/"theme"/"list" → no destinations (stubs)
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Home,
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                ) {
                    // ── Home: list ↔ chat (keep-alive). Bodies are the old `else`-branch, verbatim,
                    //    with `route = …` swapped for nav. The keep-alive / shared-element / predictive-back
                    //    code lives inside the hosts below and is unchanged. ──
                    composable<Home> {
                        if (expanded) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(320.dp)) {
                                    SessionListScreen(
                                        sessions = sessions,
                                        home = DevConfig.HOME,
                                        activeId = selected,
                                        onOpen = { selected = it },
                                        lastBySession = lastBySession,
                                        agentState = agentState,
                                        onNewSession = { navController.navigate(NewSession) },
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        onNavigate = navTo,
                                    )
                                }
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(cs.outlineVariant)
                                )
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
                                        commands = commands,
                                        commandsResolved = commandsResolved,
                                        vm = vm,
                                        onOpenDisplays = { navController.navigate(Displays) },
                                        modifier = Modifier.fillMaxSize(),
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
                                commands = commands,
                                commandsResolved = commandsResolved,
                                lastBySession = lastBySession,
                                vm = vm,
                                onNavigate = navTo,
                                onOpenDisplays = { navController.navigate(Displays) },
                            )
                        }
                    }
                    // ── New-session launcher (old "new" branch, verbatim, route→nav) ──
                    composable<NewSession> {
                        if (expanded) {
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
                                        sessions = sessions,
                                        home = DevConfig.HOME,
                                        onBack = { navController.popBackStack() },
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        loadModels = { vm.launcherModels(it) },
                                        loadRepoInfo = { vm.launcherRepoInfo(it) },
                                        loadForges = { vm.listForges() },
                                        searchForge = { vm.searchForge(it) },
                                        cloneForge = { cid, owner, name -> vm.cloneForge(cid, owner, name) },
                                        createLocalRepo = { vm.createLocalRepo(it) },
                                        createForge = { cid, name -> vm.createForge(cid, name) },
                                        onSubmit = { wd, ag, md, msg, wt, base ->
                                            vm.createSessionWithFirstMessage(wd, ag, md, msg, worktree = wt, baseBranch = base)
                                        },
                                        onOpenSession = { selected = it; navController.popBackStack() },
                                    )
                                }
                            }
                        } else {
                            SessionLauncherScreen(
                                sessions = sessions,
                                home = DevConfig.HOME,
                                onBack = { navController.popBackStack() },
                                loadProjects = { vm.listProjects() },
                                validatePath = { vm.validatePath(it) },
                                loadModels = { vm.launcherModels(it) },
                                loadRepoInfo = { vm.launcherRepoInfo(it) },
                                loadForges = { vm.listForges() },
                                searchForge = { vm.searchForge(it) },
                                cloneForge = { cid, owner, name -> vm.cloneForge(cid, owner, name) },
                                createLocalRepo = { vm.createLocalRepo(it) },
                                createForge = { cid, name -> vm.createForge(cid, name) },
                                onSubmit = { wd, ag, md, msg, wt, base ->
                                    vm.createSessionWithFirstMessage(wd, ag, md, msg, worktree = wt, baseBranch = base)
                                },
                                onOpenSession = { selected = it; navController.popBackStack() },
                            )
                        }
                    }
                    composable<Settings> {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
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
                            restartBroker = { vm.restartBroker() },
                        )
                    }
                    composable<Usage> {
                        UsageScreen(onBack = { navController.popBackStack() }, onLoad = { vm.usage() })
                    }
                    composable<Devices> {
                        DevicesScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.devices() },
                            onRevoke = { vm.revoke(it) },
                        )
                    }
                    composable<Archived> {
                        ArchivedScreen(
                            onBack = { navController.popBackStack() },
                            onLoad = { vm.archived() },
                            onResume = { vm.resume(it) },
                            home = DevConfig.HOME,
                            loadLogs = { vm.archivedLogs(it) },
                        )
                    }
                    composable<Proxies> {
                        ProxyScreen(
                            onLoad = { vm.proxies() },
                            sessions = sessions,
                            onCreate = { s, p, d -> vm.createProxy(s, p, d) },
                            onTogglePublic = { d, pub -> vm.setProxyPublic(d, pub) },
                            onRemove = { vm.removeProxy(it) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable<Displays> {
                        // Seed the live list once (the reducer otherwise fills only from frames);
                        // mirrors iOS DisplaysView's `.task { refreshDisplays() }`.
                        LaunchedEffect(Unit) { vm.listDisplays() }
                        DisplaysScreen(
                            onBack = { navController.popBackStack() },
                            displays = vm.displays,
                            onStart = { sessionName -> vm.startDisplay(sessionName) },
                            onStop = { id -> vm.stopDisplay(id) },
                            connectVnc = { vm.connectVnc(it) },
                            connectScrcpy = { vm.connectScrcpy(it) },
                        )
                    }
                    composable<Appearance> {
                        AppearanceSettingsPage(
                            appearance = appearance,
                            dynamicColor = dynamicColor,
                            onAppearanceChange = {
                                appearance = it
                                prefs.edit().putString("appearance", it.name).apply()
                            },
                            onDynamicChange = {
                                dynamicColor = it
                                prefs.edit().putBoolean("dynamicColor", it).apply()
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
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
    commands: Map<String, List<SlashCommand>>,
    commandsResolved: Map<String, Boolean>,
    lastBySession: Map<String, LogEntry?>,
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
    onOpenDisplays: () -> Unit,
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
        commands = commands,
        commandsResolved = commandsResolved,
        lastBySession = lastBySession,
        vm = vm,
        onNavigate = onNavigate,
        onOpenDisplays = onOpenDisplays,
    )
}
