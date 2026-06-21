package dev.supermux.android

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermux.android.session.SessionKeepAlivePhoneHost
import dev.supermux.android.session.SessionKeepAliveTabletHost
import dev.supermux.android.session.rememberVisitedSessions
import dev.supermux.android.session.SessionLauncherScreen
import dev.supermux.android.session.SessionListScreen
import dev.supermux.android.settings.AppearanceSettingsPage
import dev.supermux.android.settings.ArchivedScreen
import dev.supermux.android.settings.DevicesScreen
import dev.supermux.android.settings.ProxyScreen
import dev.supermux.android.settings.SettingsScreen
import dev.supermux.android.settings.UsageScreen
import dev.supermux.android.theme.AppearanceMode
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.SupermuxTheme
import dev.supermux.android.DevConfig
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureTokenStoreContext.init(applicationContext)
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
                val vm = remember {
                    AppViewModel(DevConfig.brokerUrl(), DevConfig.resolveToken(applicationContext))
                }
                val sessions by vm.sessions.collectAsStateWithLifecycle()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val activity by vm.activity.collectAsStateWithLifecycle()
                val agentState by vm.agentState.collectAsStateWithLifecycle()
                val commands by vm.commands.collectAsStateWithLifecycle()
                val lastBySession = messages.mapValues { it.value.lastOrNull() }
                var selected by remember { mutableStateOf<String?>(null) }
                val liveSessionIds = remember(sessions) { sessions.map { it.id }.toSet() }
                val (visitedSessions, removeVisited) = rememberVisitedSessions(selected, liveSessionIds)

                val windowSizeClass = calculateWindowSizeClass(this)
                val expanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
                val c = LocalPanes.current

                var route by remember { mutableStateOf("list") }

                when (route) {
                    "settings" -> {
                        BackHandler { route = "list" }
                        SettingsScreen(
                            onBack = { route = "list" },
                            curatorLoad = { vm.curatorSettings() },
                            curatorSave = { e, h, m -> vm.saveCurator(e, h, m) },
                            curatorRunNow = { vm.runCuratorNow() },
                        )
                    }
                    "usage" -> {
                        BackHandler { route = "list" }
                        UsageScreen(
                            onBack = { route = "list" },
                            onLoad = { vm.usage() },
                        )
                    }
                    "devices" -> {
                        BackHandler { route = "list" }
                        DevicesScreen(
                            onBack = { route = "list" },
                            onLoad = { vm.devices() },
                            onRevoke = { vm.revoke(it) },
                        )
                    }
                    "archived" -> {
                        BackHandler { route = "list" }
                        ArchivedScreen(
                            onBack = { route = "list" },
                            onLoad = { vm.archived() },
                            onResume = { vm.resume(it) },
                            loadLogs = { vm.archivedLogs(it) },
                        )
                    }
                    "proxies" -> {
                        BackHandler { route = "list" }
                        ProxyScreen(
                            onLoad = { vm.proxies() },
                            sessions = sessions,
                            onCreate = { s, p, d -> vm.createProxy(s, p, d) },
                            onTogglePublic = { d, pub -> vm.setProxyPublic(d, pub) },
                            onRemove = { vm.removeProxy(it) },
                            onBack = { route = "list" },
                        )
                    }
                    "appearance" -> {
                        BackHandler { route = "list" }
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
                            onBack = { route = "list" },
                        )
                    }
                    "new" -> {
                        BackHandler { route = "list" }
                        if (expanded) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(320.dp)) {
                                    SessionListScreen(
                                        sessions = sessions,
                                        home = DevConfig.HOME,
                                        activeId = selected,
                                        onOpen = { selected = it; route = "list" },
                                        lastBySession = lastBySession,
                                        onNewSession = { route = "new" },
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        onNavigate = { route = it },
                                    )
                                }
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color(c.border)),
                                )
                                Box(Modifier.weight(1f)) {
                                    SessionLauncherScreen(
                                        sessions = sessions,
                                        home = DevConfig.HOME,
                                        onBack = { route = "list" },
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        onSubmit = { wd, ag, md, msg ->
                                            vm.createSessionWithFirstMessage(wd, ag, md, msg)
                                        },
                                        onOpenSession = { selected = it; route = "list" },
                                    )
                                }
                            }
                        } else {
                            SessionLauncherScreen(
                                sessions = sessions,
                                home = DevConfig.HOME,
                                onBack = { route = "list" },
                                loadProjects = { vm.listProjects() },
                                validatePath = { vm.validatePath(it) },
                                onSubmit = { wd, ag, md, msg ->
                                    vm.createSessionWithFirstMessage(wd, ag, md, msg)
                                },
                                onOpenSession = { selected = it; route = "list" },
                            )
                        }
                    }
                    else -> {
                        // "displays" / "theme" → toast then fall back to list
                        if (route != "list") {
                            val label = when (route) {
                                "displays" -> "Displays"
                                else       -> route
                            }
                            Toast.makeText(this, "$label — coming soon", Toast.LENGTH_SHORT).show()
                            route = "list"
                        }

                        // ── Session list / chat ────────────────────────────────────
                        if (expanded) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(320.dp)) {
                                    SessionListScreen(
                                        sessions = sessions,
                                        home = DevConfig.HOME,
                                        activeId = selected,
                                        onOpen = { selected = it },
                                        lastBySession = lastBySession,
                                        onNewSession = { route = "new" },
                                        loadProjects = { vm.listProjects() },
                                        validatePath = { vm.validatePath(it) },
                                        onNavigate = { route = it },
                                    )
                                }
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color(c.border))
                                )
                                Box(Modifier.weight(1f)) {
                                    if (selected == null) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(Color(c.chat)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("Select a session", color = Color(c.mutedForeground))
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
                                        vm = vm,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        } else {
                            // ── Phone: animated list ↔ chat with spring + shared-element ──
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
                                lastBySession = lastBySession,
                                vm = vm,
                                onNavigate = { route = it },
                            )
                        }
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
    lastBySession: Map<String, LogEntry?>,
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
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
        lastBySession = lastBySession,
        vm = vm,
        onNavigate = onNavigate,
    )
}
