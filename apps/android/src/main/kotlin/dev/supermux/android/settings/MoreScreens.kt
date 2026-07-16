package dev.supermux.android.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.chat.TimelineItemRow
import dev.supermux.android.chat.mergeTimeline
import dev.supermux.android.theme.AppearanceMode
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.TEXT_SCALE_MAX
import dev.supermux.android.theme.TEXT_SCALE_MIN
import kotlin.math.roundToInt
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.ArchivedDto
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.DeviceDto
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.net.PADto
import dev.supermux.net.ProxyDto
import dev.supermux.net.UpdateStatus
import dev.supermux.android.session.relTime
import dev.supermux.session.archivedProjects
import dev.supermux.session.filterArchivedByProject
import dev.supermux.session.formatWorkdir
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.json.JSONObject

// ─── SettingsScreen ───────────────────────────────────────────────────────────
//
// The full iOS-parity hub: an INDEX of 7 rows that navigate to sub-pages. Internal
// nav via `opened` (null = index); each sub-page is self-contained with its own
// Scaffold/TopAppBar/BackHandler and crosses the VM boundary as suspend lambdas /
// plain callbacks (the established style). Curator + Voice are server-backed; Editor
// hosts local prefs (SharedPreferences) + the broker-backed Language-servers section.
//
// The signature is the union of this Settings suite + the Voice track (canonical order
// in the 2026-06-21-android-settings-changelist §3a). Both must match MainActivity's call.

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    // Personal assistants
    paLoad: suspend () -> List<PADto>,
    paCreate: suspend (name: String, agent: String, focus: String?) -> Boolean,
    paKill: suspend (id: String) -> Unit,
    // Assistant
    assistantLoad: suspend () -> Pair<String, String>?,
    assistantSave: suspend (paName: String, soul: String) -> Boolean,
    // Agents
    agentStatuses: suspend () -> List<AgentInstallStatus>,
    agentStartLogin: suspend (kind: String) -> AgentLoginState?,
    agentPollLogin: suspend (kind: String) -> AgentLoginState?,
    agentSendCode: (kind: String, code: String) -> Unit,
    agentCancelLogin: (kind: String) -> Unit,
    agentSaveSecret: (kind: String, value: String) -> Unit,
    openCodeProviders: suspend () -> List<OpenCodeProvider>,
    openCodeSetKey: (providerId: String, key: String) -> Unit,
    openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    openCodeFinishOAuth: (providerId: String, method: Int, code: String) -> Unit,
    // Curator
    curatorLoad: suspend () -> CuratorSettingsResponse?,
    curatorSave: suspend (Boolean, Int, Int) -> CuratorSettingsResponse?,
    curatorRunNow: suspend () -> Unit,
    // Voice (Voice track)
    voiceLoadModels: suspend (family: String) -> List<dev.supermux.net.ModelInfo>,
    voiceLoadConfig: suspend () -> dev.supermux.net.AppConfigDto?,
    voiceSaveVoiceCleanup: (engine: String?, model: String?) -> Unit,
    glossaryLoad: suspend () -> List<String>,
    glossarySave: suspend (List<String>) -> List<String>?,
    // Editor / LSP
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    // Git hosting
    forgesLoad: suspend () -> ForgeConnectionsResponse?,
    forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    forgeImport: suspend (kind: String, transport: String) -> Boolean,
    forgeRemove: (id: String) -> Unit,
    // System
    updateStatus: suspend () -> UpdateStatus?,
    restartBroker: () -> Unit,
) {
    var opened by remember { mutableStateOf<String?>(null) }

    when (opened) {
        "personal-assistants" -> PersonalAssistantsSettingsPage(
            onBack = { opened = null },
            load = paLoad,
            create = paCreate,
            kill = paKill,
        )
        "assistant" -> AssistantSettingsPage(
            onBack = { opened = null },
            load = assistantLoad,
            save = assistantSave,
        )
        "agents" -> AgentSettingsPage(
            onBack = { opened = null },
            agentStatuses = agentStatuses,
            agentStartLogin = agentStartLogin,
            agentPollLogin = agentPollLogin,
            agentSendCode = agentSendCode,
            agentCancelLogin = agentCancelLogin,
            agentSaveSecret = agentSaveSecret,
            openCodeProviders = openCodeProviders,
            openCodeSetKey = openCodeSetKey,
            openCodeStartOAuth = openCodeStartOAuth,
            openCodeFinishOAuth = openCodeFinishOAuth,
        )
        "curator" -> CuratorSettingsPage(
            onBack = { opened = null },
            curatorLoad = curatorLoad,
            curatorSave = curatorSave,
            curatorRunNow = curatorRunNow,
        )
        "voice" -> VoiceSettingsPage(
            onBack = { opened = null },
            loadModels = voiceLoadModels,
            loadConfig = voiceLoadConfig,
            saveVoiceCleanup = voiceSaveVoiceCleanup,
            onOpenGlossary = { opened = "glossary" },
        )
        "glossary" -> VoiceGlossaryPage(
            onBack = { opened = "voice" },
            load = glossaryLoad,
            save = glossarySave,
        )
        "editor" -> EditorSettingsPage(
            onBack = { opened = null },
            lspLoad = lspLoad,
            lspToggle = lspToggle,
            lspInstall = lspInstall,
            lspInstallLog = lspInstallLog,
            lspInstallDone = lspInstallDone,
            lspAddCustom = lspAddCustom,
            lspRemoveCustom = lspRemoveCustom,
        )
        "git" -> GitHostingPage(
            onBack = { opened = null },
            forgesLoad = forgesLoad,
            forgeAdd = forgeAdd,
            forgeImport = forgeImport,
            forgeRemove = forgeRemove,
        )
        "system" -> SystemSettingsPage(
            onBack = { opened = null },
            updateStatus = updateStatus,
            restartBroker = restartBroker,
        )
        else -> SettingsIndexPage(
            onBack = onBack,
            onOpen = { opened = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalAssistantsSettingsPage(
    onBack: () -> Unit,
    load: suspend () -> List<PADto>,
    create: suspend (name: String, agent: String, focus: String?) -> Boolean,
    kill: suspend (id: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<PADto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var killTarget by remember { mutableStateOf<PADto?>(null) }

    suspend fun refresh() {
        loading = true
        items = load()
        loading = false
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal assistants", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create personal assistant")
            }
        },
        containerColor = cs.background,
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No personal assistants", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Personal assistants are optional. Tap + to create a persistent orchestrator.",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(items, key = { it.id }) { pa ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(pa.name, fontWeight = FontWeight.Medium)
                                if (pa.isDefault) Text("default", color = cs.primary, fontSize = 11.sp)
                            }
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(pa.agent, pa.model).joinToString(" · ").ifBlank { pa.workdir },
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Box(
                                Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (pa.connected) cs.primary else cs.outline),
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { killTarget = pa }) {
                                Icon(Icons.Default.Delete, contentDescription = "Kill ${pa.name}")
                            }
                        },
                    )
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }

    if (showCreate) {
        PersonalAssistantCreateDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, agent, focus ->
                scope.launch {
                    if (create(name, agent, focus)) {
                        showCreate = false
                        refresh()
                    }
                }
            },
        )
    }

    killTarget?.let { pa ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Kill ${pa.name}?") },
            text = { Text("Its session will be archived. You can create another personal assistant later.") },
            confirmButton = {
                TextButton(onClick = {
                    killTarget = null
                    scope.launch { kill(pa.id); refresh() }
                }) { Text("Kill") }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PersonalAssistantCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, agent: String, focus: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("claude") }
    var focus by remember { mutableStateOf("") }
    val agents = listOf("claude", "codex", "cursor", "opencode", "grok")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create personal assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Agent", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                agents.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { value ->
                            FilterChip(
                                selected = agent == value,
                                onClick = { agent = value },
                                label = { Text(value) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = focus,
                    onValueChange = { focus = it },
                    label = { Text("Focus (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), agent, focus.trim().takeIf { it.isNotEmpty() }) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsIndexPage(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsNavRow(R.drawable.ic_smartphone, "Personal assistants", "Optional persistent orchestrators") { onOpen("personal-assistants") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_smartphone, "PA identity", "Shared soul.md for personal assistants") { onOpen("assistant") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_settings, "Agents", "CLI authorization and API-key fallback") { onOpen("agents") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_sparkle, "Curator", "Nightly knowledge curation schedule") { onOpen("curator") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_mic, "Voice", "Dictation cleanup model & glossary") { onOpen("voice") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_file, "Editor", "Font, wrap, and language servers") { onOpen("editor") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_network, "Git hosting", "GitHub & GitLab connections") { onOpen("git") }
            HorizontalDivider(color = cs.outlineVariant)
            SettingsNavRow(R.drawable.ic_monitor, "System", "Broker restart and status") { onOpen("system") }
        }
    }
}

/** A 36dp rounded icon box used by index rows and Curator rows. */
@Composable
private fun SettingsIconBox(iconRes: Int) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Tappable index row: icon box + label/desc + trailing chevron. */
@Composable
private fun SettingsNavRow(
    iconRes: Int,
    label: String,
    desc: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconBox(iconRes)
        Column(Modifier.weight(1f)) {
            Text(label, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Curator page ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CuratorSettingsPage(
    onBack: () -> Unit,
    curatorLoad: suspend () -> CuratorSettingsResponse?,
    curatorSave: suspend (Boolean, Int, Int) -> CuratorSettingsResponse?,
    curatorRunNow: suspend () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(1) }
    var minute by remember { mutableStateOf(0) }
    var nextRun by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = curatorLoad()
        if (r != null) {
            enabled = r.config.enabled
            hour = r.config.hour
            minute = r.config.minute
            nextRun = r.nextRun
        }
        loaded = true
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Curator", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        if (!loaded) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = cs.primary)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                // 1. Nightly curator toggle
                CuratorRow(
                    iconRes = R.drawable.ic_sparkle,
                    label = "Nightly curator",
                    desc = "Curate ~/.mux daily, commit + push, and post a digest.",
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = cs.onPrimary,
                            checkedTrackColor = cs.primary,
                        ),
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                // 2. Run at — opens the M3 TimePicker dialog
                CuratorRow(
                    label = "Run at",
                    desc = "Daily, host local time.",
                ) {
                    Box(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(cs.surfaceContainer)
                            .border(1.dp, cs.outline, RoundedCornerShape(6.dp))
                            .clickable { showTimePicker = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            String.format(Locale.US, "%02d:%02d", hour, minute),
                            color = cs.onSurface,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)

                // 3. Next run (read-only)
                CuratorRow(
                    label = "Next run",
                    desc = "The digest notifies all your devices.",
                ) {
                    Text(
                        curatorNextRunLabel(enabled, nextRun),
                        color = cs.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                // Footer actions
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                saving = true
                                val r = curatorSave(enabled, hour, minute)
                                if (r != null) nextRun = r.nextRun
                                saving = false
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    ) {
                        Text(if (saving) "Saving…" else "Save", color = cs.onPrimary)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                running = true
                                curatorRunNow()
                                running = false
                            }
                        },
                        enabled = !running,
                        border = BorderStroke(1.dp, cs.outline),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            tint = cs.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Starting…" else "Run now", color = cs.onSurface)
                    }
                }
            }
        }
    }

    // M3 time picker, hosted in an AlertDialog (24h). Confirm writes hour/minute
    // back into the hoisted state exactly as the framework dialog's callback did.
    if (showTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    hour = tpState.hour
                    minute = tpState.minute
                    showTimePicker = false
                }) { Text("OK", color = cs.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = tpState)
                }
            },
        )
    }
}

/** Curator list row: optional icon box + label/desc + trailing control slot. */
@Composable
private fun CuratorRow(
    label: String,
    desc: String,
    iconRes: Int? = null,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (iconRes != null) SettingsIconBox(iconRes)
        Column(Modifier.weight(1f)) {
            Text(label, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        trailing()
    }
}

/** Mirrors the web's nextRunLabel: disabled / formatted local datetime / raw / —. */
private fun curatorNextRunLabel(enabled: Boolean, nextRun: String?): String {
    if (!enabled) return "Disabled"
    val raw = nextRun ?: return "—"
    return runCatching {
        val dt = LocalDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault())
        dt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrNull() ?: raw
}

// ─── Editor page (local appearance prefs + broker Language-servers section) ───────
//
// Mirrors iOS EditorSettingsScreen: ONE "Editor" screen with two sections — the
// device-local appearance prefs (line-wrap + font-size, SharedPreferences) and the
// broker-backed Language servers (EditorLspSection, EditorLspScreen.kt). The whole page
// scrolls because the LSP list + add-form can be tall.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSettingsPage(
    onBack: () -> Unit,
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
) {
    val cs = MaterialTheme.colorScheme
    val prefs = LocalContext.current
        .getSharedPreferences("cmux-editor-settings", Context.MODE_PRIVATE)

    var lineWrap by remember { mutableStateOf(prefs.getBoolean("lineWrap", true)) }
    var fontSize by remember { mutableStateOf(prefs.getInt("fontSize", 13)) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 1. Wrap long lines
            CuratorRow(
                label = "Wrap long lines",
                desc = "Wrap instead of horizontal scroll.",
            ) {
                Switch(
                    checked = lineWrap,
                    onCheckedChange = {
                        lineWrap = it
                        prefs.edit().putBoolean("lineWrap", it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = cs.onPrimary,
                        checkedTrackColor = cs.primary,
                    ),
                )
            }
            HorizontalDivider(color = cs.outlineVariant)

            // 2. Font size stepper (clamp 10..24)
            CuratorRow(
                label = "Font size",
                desc = "Code editor text size.",
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StepperButton(text = "−", enabled = fontSize > 10) {
                        val v = (fontSize - 1).coerceIn(10, 24)
                        fontSize = v
                        prefs.edit().putInt("fontSize", v).apply()
                    }
                    Text(
                        fontSize.toString(),
                        color = cs.onSurface,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    StepperButton(text = "+", enabled = fontSize < 24) {
                        val v = (fontSize + 1).coerceIn(10, 24)
                        fontSize = v
                        prefs.edit().putInt("fontSize", v).apply()
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)

            // 3. Language servers (broker-backed)
            Spacer(Modifier.height(8.dp))
            EditorLspSection(
                lspLoad = lspLoad,
                lspToggle = lspToggle,
                lspInstall = lspInstall,
                lspInstallLog = lspInstallLog,
                lspInstallDone = lspInstallDone,
                lspAddCustom = lspAddCustom,
                lspRemoveCustom = lspRemoveCustom,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Small bordered −/+ button for the font-size stepper. */
@Composable
private fun StepperButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.4f
    // Keep the 32dp visual but expand the tap target to ≥48dp (a11y, §5).
    Box(
        Modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline.copy(alpha = alpha), RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = cs.onSurface.copy(alpha = alpha), fontSize = 18.sp)
        }
    }
}

// ─── Appearance page (light/dark + Material You) ──────────────────────────────
//
// Born native: reads MaterialTheme.colorScheme (not LocalPanes) so it reflects
// dynamic colour, and uses the M3 SingleChoiceSegmentedButtonRow.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsPage(
    appearance: AppearanceMode,
    dynamicColor: Boolean,
    textScale: Float,
    onAppearanceChange: (AppearanceMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = AppearanceMode.entries
                    modes.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = appearance == mode,
                            onClick = { onAppearanceChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) {
                            Text(
                                when (mode) {
                                    AppearanceMode.SYSTEM -> "System"
                                    AppearanceMode.LIGHT -> "Light"
                                    AppearanceMode.DARK -> "Dark"
                                }
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Material You", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                    Text(
                        "Use colours from your wallpaper (Android 12+).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = dynamicColor, onCheckedChange = onDynamicChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Text size", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                        Text(
                            "Scales all text in the app — the whole screen previews it live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${(textScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (textScale == 1f) cs.onSurfaceVariant else cs.primary,
                    )
                }
                Slider(
                    value = textScale,
                    onValueChange = { raw ->
                        // Snap to 5% increments so the stored value stays clean.
                        val snapped = (raw * 20).roundToInt() / 20f
                        if (snapped != textScale) onTextScaleChange(snapped)
                    },
                    valueRange = TEXT_SCALE_MIN..TEXT_SCALE_MAX,
                    steps = 7,
                )
            }
        }
    }
}

// ─── UsageScreen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    onLoad: suspend () -> String?,
    onRedeem: suspend () -> CodexResetResult?,
) {
    val cs = MaterialTheme.colorScheme
    var usage by remember { mutableStateOf<UsageData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        loadFailed = false
        val raw = onLoad()
        if (raw == null) {
            loadFailed = true
        } else {
            usage = parseUsage(raw)
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { if (!loading) reloadKey++ }, enabled = !loading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = if (loading) cs.onSurfaceVariant else cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && usage == null -> {
                    CircularProgressIndicator(
                        color = cs.primary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                loadFailed && usage == null -> {
                    Text(
                        "Unable to load usage data.",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    val u = usage
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ClaudeUsageCard(u?.claude, u?.errors?.get("claude"))
                        CodexUsageCard(u?.codex, u?.errors?.get("codex"), onRedeem = onRedeem, onRefresh = { reloadKey++ })
                        CursorUsageCard(u?.cursor, u?.errors?.get("cursor"))
                    }
                }
            }
        }
    }
}

// ─── Usage data model + parsing (defensive: missing sections → null) ───────────

/** resetsAt raw value: ISO string (claude/cursor) or unix-seconds number (codex). */
private data class UsageWindowData(
    val used: Double,
    val resetsAt: String?,
    val label: String? = null,
)
private data class ClaudeExtraUsageData(val enabled: Boolean, val monthlyLimit: Double, val usedCredits: Double, val currency: String)
private data class ClaudeUsageData(
    val fiveHour: UsageWindowData?,
    val sevenDay: UsageWindowData?,
    val sevenDaySonnet: UsageWindowData?,
    val sevenDayFable: UsageWindowData?,
    val extraUsage: ClaudeExtraUsageData?,
)
private data class CodexCreditsData(val hasCredits: Boolean, val balance: String)
private data class CodexUsageData(
    val plan: String?,
    val windows: List<UsageWindowData>,
    val credits: CodexCreditsData?,
    val limitReached: Boolean,
    val resetCredits: Int,
)
private data class CursorUsageData(
    val totalPercentUsed: Double,
    val totalSpendCents: Double,
    val includedCents: Double,
    val limitCents: Double,
    val spendAvailable: Boolean,
    val billingCycleStart: String?,
    val billingCycleEnd: String?,
)
private data class UsageData(
    val claude: ClaudeUsageData?,
    val codex: CodexUsageData?,
    val cursor: CursorUsageData?,
    val errors: Map<String, String>,
)

// Defensive accessors over org.json (Android SDK, no extra dep). Any missing /
// wrong-typed field collapses to null/0 so a partial payload still renders.
private fun JSONObject.objOrNull(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)
private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key, "").ifEmpty { null }
private fun JSONObject.numOr(key: String, def: Double = 0.0): Double = optDouble(key, def)

private fun parseWindow(o: JSONObject?): UsageWindowData? {
    if (o == null) return null
    // resetsAt may be a string (ISO) or a number (unix secs) — keep it as text.
    val reset = if (o.isNull("resetsAt") || !o.has("resetsAt")) null else o.get("resetsAt").toString()
    return UsageWindowData(
        used = o.numOr("used"),
        resetsAt = reset,
        label = o.strOrNull("label"),
    )
}

private fun parseUsage(raw: String): UsageData {
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return UsageData(null, null, null, emptyMap())

    val claude = root.objOrNull("claude")?.let { o ->
        ClaudeUsageData(
            fiveHour = parseWindow(o.objOrNull("fiveHour")),
            sevenDay = parseWindow(o.objOrNull("sevenDay")),
            sevenDaySonnet = parseWindow(o.objOrNull("sevenDaySonnet")),
            sevenDayFable = parseWindow(o.objOrNull("sevenDayFable")),
            extraUsage = o.objOrNull("extraUsage")?.let { e ->
                ClaudeExtraUsageData(
                    enabled = e.optBoolean("enabled", false),
                    monthlyLimit = e.numOr("monthlyLimit"),
                    usedCredits = e.numOr("usedCredits"),
                    currency = e.strOrNull("currency") ?: "USD",
                )
            },
        )
    }

    val codex = root.objOrNull("codex")?.let { o ->
        CodexUsageData(
            plan = o.strOrNull("plan"),
            windows = o.optJSONArray("windows")?.let { windows ->
                buildList {
                    for (i in 0 until windows.length()) {
                        parseWindow(windows.optJSONObject(i))?.let(::add)
                    }
                }
            } ?: emptyList(),
            credits = o.objOrNull("credits")?.let { cr ->
                CodexCreditsData(
                    hasCredits = cr.optBoolean("hasCredits", false),
                    balance = cr.strOrNull("balance") ?: "0",
                )
            },
            limitReached = o.optBoolean("limitReached", false),
            resetCredits = o.optInt("resetCredits", 0),
        )
    }

    val cursor = root.objOrNull("cursor")?.let { o ->
        CursorUsageData(
            totalPercentUsed = o.numOr("totalPercentUsed"),
            totalSpendCents = o.numOr("totalSpendCents"),
            includedCents = o.numOr("includedCents"),
            limitCents = o.numOr("limitCents"),
            spendAvailable = o.optBoolean("spendAvailable", false),
            billingCycleStart = o.strOrNull("billingCycleStart"),
            billingCycleEnd = o.strOrNull("billingCycleEnd"),
        )
    }

    val errors = root.objOrNull("errors")?.let { e ->
        buildMap {
            for (key in e.keys()) {
                e.strOrNull(key)?.let { put(key, it) }
            }
        }
    } ?: emptyMap()

    return UsageData(claude, codex, cursor, errors)
}

// ─── Usage rendering helpers ───────────────────────────────────────────────────

private enum class ResetKind { CLAUDE, CODEX, CURSOR }

private fun clampPct(v: Double): Double = v.coerceIn(0.0, 100.0)

/** Bar colour by percentage: >=85 red, >=60 amber, else primary. */
@Composable
private fun barColor(pct: Double): Color {
    val cs = MaterialTheme.colorScheme
    val panes = LocalPanes.current
    return when {
        pct >= 85 -> cs.error
        pct >= 60 -> Color(panes.warning)
        else -> cs.primary
    }
}

/**
 * Reset formatting.
 *  - CLAUDE: resetsAt is an ISO-8601 string.
 *  - CODEX: resetsAt is unix SECONDS.
 *  - CURSOR: resetsAt is an ISO-8601 string (billing cycle end).
 * Shows "resets in Xh Ym" when <24h, else "resets <Mon D>".
 */
private fun formatReset(resetsAt: String?, kind: ResetKind): String {
    val s = resetsAt?.takeIf { it.isNotBlank() } ?: return ""
    val ms: Long = when (kind) {
        ResetKind.CODEX -> {
            val secs = s.toDoubleOrNull() ?: return ""
            (secs * 1000.0).toLong()
        }
        ResetKind.CLAUDE, ResetKind.CURSOR -> {
            // Try epoch-millis numeric first, else parse ISO-8601.
            s.toLongOrNull() ?: runCatching {
                Instant.parse(s).toEpochMilli()
            }.getOrElse { return "" }
        }
    }
    val diff = ms - System.currentTimeMillis()
    if (diff <= 0) return "resets soon"
    if (diff < 24L * 3600_000L) {
        val h = (diff / 3600_000L).toInt()
        val m = ((diff % 3600_000L) / 60_000L).toInt()
        return if (h > 0) "resets in ${h}h ${m}m" else "resets in ${m}m"
    }
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.US)
    return "resets $month ${date.dayOfMonth}"
}

private fun money(cents: Double): String = "$" + "%.2f".format(Locale.US, cents / 100.0)
private fun dollars(v: Double): String = "$" + "%.2f".format(Locale.US, v)

/** Outer usage card: rounded 12dp, border, title + plan subtitle, content slot. */
@Composable
private fun UsageCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainer.copy(alpha = alpha))
            .border(1.dp, cs.outline.copy(alpha = alpha), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            badge?.invoke()
        }
        content()
    }
}

/** A labelled usage window: label + "{pct}% used" + progress bar + reset line. */
@Composable
private fun UsageWindowRow(label: String, used: Double, resetsAt: String?, kind: ResetKind) {
    val cs = MaterialTheme.colorScheme
    val pct = clampPct(used)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${used.roundToInt()}% used", color = cs.onSurface, fontSize = 12.sp)
        }
        // Progress bar (M3 LinearProgressIndicator; lambda-progress form, material3 1.4)
        LinearProgressIndicator(
            progress = { (pct / 100.0).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor(used),
            trackColor = cs.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        val reset = formatReset(resetsAt, kind)
        if (reset.isNotEmpty()) {
            Text(reset, color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A footer row separated by a top border (extra usage / credits / spend). */
@Composable
private fun UsageFooterRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = cs.onSurface, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ClaudeUsageCard(claude: ClaudeUsageData?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(title = "Claude", subtitle = "Pro plan", enabled = claude != null) {
        if (claude == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            claude.fiveHour?.let { UsageWindowRow("5-hour window", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.sevenDay?.let { UsageWindowRow("7-day window", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.sevenDaySonnet?.let { UsageWindowRow("7-day Sonnet", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.sevenDayFable?.let { UsageWindowRow("7-day Fable", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.extraUsage?.takeIf { it.enabled }?.let { e ->
                UsageFooterRow("Extra usage", "${dollars(e.usedCredits)} / ${dollars(e.monthlyLimit)}")
            }
        }
    }
}

@Composable
private fun CodexUsageCard(
    codex: CodexUsageData?,
    error: String?,
    onRedeem: (suspend () -> CodexResetResult?)? = null,
    onRefresh: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var redeeming by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    UsageCard(
        title = "Codex",
        subtitle = codex?.plan ?: "unknown",
        enabled = codex != null,
        badge = if (codex?.limitReached == true) {
            {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(cs.error.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("limit reached", color = cs.error, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else null,
    ) {
        if (codex == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            codex.windows.forEach { window ->
                UsageWindowRow(window.label ?: "Usage window", window.used, window.resetsAt, ResetKind.CODEX)
            }
            codex.credits?.takeIf { it.hasCredits }?.let { cr ->
                UsageFooterRow("Credits balance", "${cr.balance} credits")
            }
            UsageFooterRow("🎟️ Resets banked", "${codex.resetCredits}")
            if (codex.resetCredits > 0 && onRedeem != null) {
                OutlinedButton(
                    onClick = { showDialog = true },
                    enabled = !redeeming,
                    modifier = Modifier.padding(top = 8.dp),
                    border = BorderStroke(1.dp, cs.outline),
                ) {
                    Text(if (redeeming) "Redeeming…" else "Use a reset", color = cs.onSurface, fontSize = 13.sp)
                }
            }
            if (note != null) {
                Text(
                    note!!,
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (showDialog && codex != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Use a banked reset?") },
            text = { Text("Spends 1 of ${codex.resetCredits} to clear your rate-limit windows now.") },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch {
                        redeeming = true
                        val r = onRedeem?.invoke()
                        note = codexResetNote(r)
                        onRefresh()
                        redeeming = false
                    }
                }) { Text("Use reset", color = cs.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun codexResetNote(r: CodexResetResult?): String {
    if (r == null) return "Reset failed"
    return when (r.code) {
        "reset" -> "✓ Reset — cleared ${r.windowsReset} window${if (r.windowsReset == 1) "" else "s"}"
        "nothing_to_reset" -> "Nothing to reset right now"
        "no_credit" -> "No banked resets left"
        "already_redeemed" -> "That reset was already redeemed"
        else -> "Reset request completed"
    }
}

@Composable
private fun CursorUsageCard(cursor: CursorUsageData?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(title = "Cursor", subtitle = "Billing cycle", enabled = cursor != null) {
        if (cursor == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            // Cursor uses cents + ISO billing cycle end; reset line tracks billingCycleEnd.
            UsageWindowRow("Usage", cursor.totalPercentUsed, cursor.billingCycleEnd, ResetKind.CURSOR)
            if (cursor.spendAvailable) {
                UsageFooterRow("Spend", "${money(cursor.totalSpendCents)} / ${money(cursor.includedCents)} included")
            }
        }
    }
}

// ─── DevicesScreen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onLoad: suspend () -> List<DeviceDto>,
    onAdd: suspend (String) -> AddDeviceResponse?,
    onRevoke: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var revokeTarget by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        devices = onLoad()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add device")
            }
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                devices.isEmpty() -> Text(
                    "No devices registered.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(devices, key = { it.name }) { device ->
                        DeviceRow(
                            device = device,
                            onRevoke = { revokeTarget = device.name },
                        )
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }

    // Confirm revoke dialog
    revokeTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke device?") },
            text = { Text("Remove \"$name\" from authorized devices?") },
            confirmButton = {
                TextButton(onClick = {
                    onRevoke(name)
                    devices = devices.filterNot { it.name == name }
                    revokeTarget = null
                }) { Text("Revoke", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Add-device dialog: name → one-time pairing link with QR + copy.
    if (showAdd) {
        AddDeviceDialog(
            onAdd = onAdd,
            onDismiss = { minted ->
                showAdd = false
                if (minted) reloadKey++
            },
        )
    }
}

@Composable
private fun AddDeviceDialog(
    onAdd: suspend (String) -> AddDeviceResponse?,
    onDismiss: (minted: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AddDeviceResponse?>(null) }
    var copied by remember { mutableStateOf(false) }
    val minted = result != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss(minted) },
        title = { Text(if (minted) "Pairing link" else "Add device") },
        text = {
            if (result == null) {
                Column {
                    Text(
                        "Give the new device a name. You'll get a one-time link to open on it.",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        singleLine = true,
                        placeholder = { Text("e.g. Work laptop") },
                        isError = error != null,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = cs.error, fontSize = 12.sp)
                    }
                }
            } else {
                val url = result!!.url
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Open this link on the new device, or scan it:",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    val qr = remember(url) { qrBitmap(url) }
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "Pairing QR code",
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        url,
                        color = cs.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(cs.surfaceContainerHigh)
                            .padding(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Treat this link like a password — anyone who opens it gets access until you revoke the device.",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && name.isNotBlank(),
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty()) return@TextButton
                        busy = true
                        error = null
                        scope.launch {
                            val r = onAdd(trimmed)
                            busy = false
                            if (r == null) error = "Couldn't create the device. Try again."
                            else result = r
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = cs.primary, strokeWidth = 2.dp)
                    else Text("Create")
                }
            } else {
                TextButton(onClick = {
                    copyToClipboard(context, "pairing link", result!!.url)
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy link") }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { onDismiss(minted) }) {
                Text(if (minted) "Done" else "Cancel")
            }
        },
    )
}

/** Render a URL as a black-on-white QR bitmap for Compose; null if encoding fails. */
private fun qrBitmap(content: String): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        com.journeyapps.barcodescanner.BarcodeEncoder()
            .encodeBitmap(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            .asImageBitmap()
    }.getOrNull()

@Composable
private fun DeviceRow(device: DeviceDto, onRevoke: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            val lastSeen = relTime(device.last_seen_at)
            if (lastSeen.isNotEmpty()) {
                Text("Last seen $lastSeen", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        TextButton(onClick = onRevoke) {
            Text("Revoke", color = cs.error, fontSize = 13.sp)
        }
    }
}

// ─── ArchivedScreen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(
    onBack: () -> Unit,
    onLoad: suspend () -> List<ArchivedDto>,
    onResume: (String) -> Unit,
    home: String,
    loadLogs: suspend (String) -> List<LogEntry> = { emptyList() },
) {
    val cs = MaterialTheme.colorScheme
    var sessions by remember { mutableStateOf<List<ArchivedDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var resumedIds by remember { mutableStateOf(setOf<String>()) }
    // Internal nav: tapping a row opens a read-only chat view of that session.
    var openedId by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val projects = remember(sessions, home) { archivedProjects(sessions, home) }
    // Clear the filter if the selected project no longer has any archived sessions.
    LaunchedEffect(projects) {
        if (selectedProject != null && projects.none { it.key == selectedProject }) {
            selectedProject = null
        }
    }

    LaunchedEffect(Unit) {
        sessions = onLoad()
        loading = false
    }

    val opened = openedId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (opened != null) {
        ArchivedChatScreen(
            sessionId = opened.id,
            name = opened.name,
            resumed = opened.id in resumedIds,
            onBack = { openedId = null },
            onResume = {
                onResume(opened.id)
                resumedIds = resumedIds + opened.id
            },
            loadLogs = loadLogs,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    if (sessions.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { filterOpen = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter by project",
                                    tint = if (selectedProject != null) cs.primary else cs.onSurface,
                                )
                            }
                            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("All projects") },
                                    onClick = { selectedProject = null; filterOpen = false },
                                    trailingIcon = if (selectedProject == null) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
                                projects.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text("${p.label}  (${p.count})") },
                                        onClick = { selectedProject = p.key; filterOpen = false },
                                        trailingIcon = if (selectedProject == p.key) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                sessions.isEmpty() -> Text(
                    "No archived sessions.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val visible = remember(sessions, selectedProject) { filterArchivedByProject(sessions, selectedProject) }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        items(visible, key = { it.id }) { session ->
                            ArchivedRow(
                                session = session,
                                home = home,
                                resumed = session.id in resumedIds,
                                onOpen = { openedId = session.id },
                                onResume = {
                                    onResume(session.id)
                                    resumedIds = resumedIds + session.id
                                },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedRow(session: ArchivedDto, home: String, resumed: Boolean, onOpen: () -> Unit, onResume: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                formatWorkdir(session.repo_root ?: session.workdir, home),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            val killed = relTime(session.killed_at)
            if (killed.isNotEmpty()) {
                Text("Ended $killed", color = cs.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        TextButton(
            onClick = onResume,
            enabled = !resumed,
        ) {
            Text(
                if (resumed) "Resumed" else "Resume",
                color = if (resumed) cs.onSurfaceVariant else cs.primary,
                fontSize = 13.sp,
            )
        }
    }
}

// ─── ArchivedChatScreen (read-only timeline of an archived session) ────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedChatScreen(
    sessionId: String,
    name: String,
    resumed: Boolean,
    onBack: () -> Unit,
    onResume: () -> Unit,
    loadLogs: suspend (String) -> List<LogEntry>,
) {
    val cs = MaterialTheme.colorScheme
    var messages by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        messages = loadLogs(sessionId)
        loading = false
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, color = cs.onSurface, fontSize = 16.sp, maxLines = 1)
                        Text("archived", color = cs.onSurfaceVariant, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onResume, enabled = !resumed) {
                        Text(
                            if (resumed) "Resumed" else "Resume",
                            color = if (resumed) cs.onSurfaceVariant else cs.primary,
                            fontSize = 13.sp,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                messages.isEmpty() -> Text(
                    "No messages.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    // Read-only: reuse chat timeline composables; no composer.
                    val timelineItems = remember(messages) { mergeTimeline(messages, emptyList()) }
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(timelineItems) { item ->
                            TimelineItemRow(item)
                        }
                    }
                }
            }
        }
    }
}

// ─── ProxyScreen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(
    onLoad: suspend () -> List<ProxyDto>,
    sessions: List<SessionInfo>,
    onCreate: (sessionName: String, port: Int, domain: String?) -> Unit,
    onTogglePublic: (domain: String, isPublic: Boolean) -> Unit,
    onRemove: (domain: String) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val proxies = remember { mutableStateListOf<ProxyDto>() }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        val loaded = onLoad()
        proxies.clear()
        proxies.addAll(loaded)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proxies", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Expose port", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                proxies.isEmpty() -> Text(
                    "No proxies configured.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(proxies, key = { it.domain }) { proxy ->
                        ProxyRow(
                            proxy = proxy,
                            onTogglePublic = { isPublic ->
                                onTogglePublic(proxy.domain, isPublic)
                                val idx = proxies.indexOfFirst { it.domain == proxy.domain }
                                if (idx >= 0) proxies[idx] = proxy.copy(isPublic = isPublic)
                            },
                            onRemove = { removeTarget = proxy.domain },
                        )
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }

    // ── Expose port dialog ────────────────────────────────────────────────────
    if (showCreateDialog) {
        ExposePortDialog(
            sessions = sessions,
            onDismiss = { showCreateDialog = false },
            onCreate = { sessionName, port, domain ->
                onCreate(sessionName, port, domain)
                showCreateDialog = false
                reloadKey++
            },
        )
    }

    // ── Confirm remove dialog ─────────────────────────────────────────────────
    removeTarget?.let { domain ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove proxy?") },
            text = { Text("Remove proxy for \"$domain\"?") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(domain)
                    proxies.removeAll { it.domain == domain }
                    removeTarget = null
                }) { Text("Remove", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProxyRow(
    proxy: ProxyDto,
    onTogglePublic: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                proxy.domain,
                color = cs.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            if (proxy.sessionName.isNotEmpty() || proxy.port != 0) {
                Text(
                    "→ ${proxy.sessionName}:${proxy.port}",
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            if (proxy.isPublic) "public" else "private",
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 4.dp),
        )
        Switch(
            checked = proxy.isPublic,
            onCheckedChange = onTogglePublic,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
            ),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove",
                tint = cs.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposePortDialog(
    sessions: List<SessionInfo>,
    onDismiss: () -> Unit,
    onCreate: (sessionName: String, port: Int, domain: String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var selectedSession by remember { mutableStateOf(sessions.firstOrNull()?.name ?: "") }
    var portText by remember { mutableStateOf("") }
    var domainText by remember { mutableStateOf("") }
    var sessionDropdownExpanded by remember { mutableStateOf(false) }

    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val canCreate = selectedSession.isNotBlank() && portValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expose port") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Session picker
                Box {
                    OutlinedTextField(
                        value = selectedSession,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Session") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { sessionDropdownExpanded = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = cs.onSurface,
                            unfocusedTextColor = cs.onSurface,
                            focusedBorderColor = cs.primary,
                            unfocusedBorderColor = cs.outline,
                            focusedLabelColor = cs.primary,
                            unfocusedLabelColor = cs.onSurfaceVariant,
                        ),
                    )
                    DropdownMenu(
                        expanded = sessionDropdownExpanded,
                        onDismissRequest = { sessionDropdownExpanded = false },
                    ) {
                        sessions.forEach { session ->
                            DropdownMenuItem(
                                text = { Text(session.name) },
                                onClick = {
                                    selectedSession = session.name
                                    sessionDropdownExpanded = false
                                },
                            )
                        }
                        if (sessions.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No sessions", color = cs.onSurfaceVariant) },
                                onClick = { sessionDropdownExpanded = false },
                            )
                        }
                    }
                }

                // Port field
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portText.isNotBlank() && !portValid,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = cs.onSurface,
                        unfocusedTextColor = cs.onSurface,
                        focusedBorderColor = cs.primary,
                        unfocusedBorderColor = cs.outline,
                        focusedLabelColor = cs.primary,
                        unfocusedLabelColor = cs.onSurfaceVariant,
                        cursorColor = cs.primary,
                    ),
                )

                // Optional domain field
                OutlinedTextField(
                    value = domainText,
                    onValueChange = { domainText = it },
                    label = { Text("Domain (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = cs.onSurface,
                        unfocusedTextColor = cs.onSurface,
                        focusedBorderColor = cs.primary,
                        unfocusedBorderColor = cs.outline,
                        focusedLabelColor = cs.primary,
                        unfocusedLabelColor = cs.onSurfaceVariant,
                        cursorColor = cs.primary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: return@TextButton
                    val domain = domainText.trim().ifBlank { null }
                    onCreate(selectedSession, port, domain)
                },
                enabled = canCreate,
            ) {
                Text("Create", color = if (canCreate) cs.primary else cs.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
