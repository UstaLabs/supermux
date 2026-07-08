package dev.supermux.android.chat

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import java.io.File
import dev.supermux.android.R
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.util.formatDuration
import dev.supermux.util.proxyDisplayUrl
import dev.supermux.util.proxyUrl
import dev.supermux.android.display.DisplayPanel
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.android.editor.EditorPanel
import dev.supermux.android.editor.PendingEditorOpen
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.toWorkdirRelativePath
import dev.supermux.android.terminal.TerminalPanel
import dev.supermux.android.session.SessionAvatar
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.gitBadge

enum class SessionPanel { Chat, Native, Editor, Terminal, Display }

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    bgTasks: List<ServerFrame.BgTask> = emptyList(),
    sending: Boolean = false,
    onBack: () -> Unit,
    onSendWith: (text: String, attachments: List<String>) -> Unit,
    onUpload: suspend (source: ChunkSource, name: String, mime: String, kind: String?, onProgress: (Long, Long) -> Unit) -> String?,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    transcribeDraft: suspend (draft: String) -> String? = { null },
    loadGlossary: suspend () -> List<String> = { emptyList() },
    onRename: (String) -> Unit = {},
    onMute: (Boolean) -> Unit = {},
    onKill: () -> Unit = {},
    sessionLinks: List<dev.supermux.net.ProxyDto> = emptyList(),
    vmModels: suspend (String) -> ModelsResponse? = { null },
    vmReasoning: suspend (String) -> ReasoningResponse? = { null },
    onPickModel: (String) -> Unit = {},
    onPickEffort: (String) -> Unit = {},
    commands: List<SlashCommand> = emptyList(),
    commandsResolved: Boolean = false,
    // Interrupt the running agent (transcript Stop capsule + /stop slash control). §8/§1.
    onInterrupt: () -> Unit = {},
    // Per-session draft persistence (DataStore, process-death-durable). §3.
    loadDraft: suspend (String) -> String = { "" },
    saveDraft: (String, String) -> Unit = { _, _ -> },
    loadBytes: suspend (String) -> ByteArray? = { null },
    fsList: suspend (String) -> List<dev.supermux.net.FsEntry> = { emptyList() },
    fsRead: suspend (String) -> Result<String> = { Result.success("") },
    fsWrite: suspend (String, String) -> Boolean = { _, _ -> false },
    fsSearch: suspend (String) -> List<dev.supermux.net.FsSearchResult> = { emptyList() },
    // Editor diff + inline code-review (bound to session.id in SessionKeepAlive).
    fsDiff: suspend () -> dev.supermux.net.FsDiffResult? = { null },
    reviewAddComment: suspend (dev.supermux.net.AddCommentBody) -> dev.supermux.net.ReviewComment? = { null },
    reviewResolve: suspend (String) -> Boolean = { false },
    reviewSubmit: suspend () -> dev.supermux.net.ReviewSubmitResult? = { null },
    // Editor LSP + live file-watch — app-wide flows + session-bound senders.
    fsChanges: kotlinx.coroutines.flow.SharedFlow<dev.supermux.proto.ServerFrame.FsChanged> =
        kotlinx.coroutines.flow.MutableSharedFlow(),
    lspStatus: kotlinx.coroutines.flow.StateFlow<Map<String, dev.supermux.proto.ServerFrame.LspStatus>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap()),
    lspRpc: kotlinx.coroutines.flow.SharedFlow<dev.supermux.proto.ServerFrame.LspRpcIn> =
        kotlinx.coroutines.flow.MutableSharedFlow(),
    editorOpen: (String) -> Unit = {},
    editorClose: (String) -> Unit = {},
    lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    lspOpen: (String, String) -> Unit = { _, _ -> },
    lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
    lspClose: (String, String) -> Unit = { _, _ -> },
    connectTerminal: (() -> dev.supermux.net.TerminalClient)? = null,
    // Native tab — terminal bound to the agent PTY with kind="agent"; iOS parity, claude-only.
    connectAgentTerminal: (() -> dev.supermux.net.TerminalClient)? = null,
    listDisplays: (suspend () -> List<dev.supermux.net.DisplayStream>)? = null,
    connectScrcpy: ((String) -> dev.supermux.net.ScrcpyClient)? = null,
    connectVnc: ((String) -> dev.supermux.net.VncClient)? = null,
    displays: kotlinx.coroutines.flow.StateFlow<List<dev.supermux.net.DisplayStream>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
    onStartDisplay: suspend () -> Unit = {},
    onOpenDisplays: () -> Unit = {},
    consumePendingFirst: (String) -> dev.supermux.android.AppViewModel.PendingFirstMessage? = { null },
    onEditorConsumesBackChange: (Boolean) -> Unit = {},
    // Finish flow — null/empty defaults keep the existing call (and ArchivedChatScreen) compiling.
    finishJob: dev.supermux.proto.FinishJobDto? = null,                                  // finishJobs[session.id]
    onFinishReadiness: suspend () -> dev.supermux.net.FinishReadiness? = { null },        // vm.finishReadiness(id)
    onFinish: (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit = { _, _, _, _, cb -> cb(false) },
    onClearFinishJob: () -> Unit = {},                                                    // vm.clearFinishJob(id)
    onVerifySuggest: suspend () -> dev.supermux.net.VerifySuggestResult? = { null },      // vm.verifySuggest(id)
    onVerifySave: suspend (String) -> dev.supermux.net.VerifySaveResult? = { null },      // vm.verifySave(id, content)
    onSendToAgent: (String) -> Unit = {},                                                 // vm.sendMessage(id, text)
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val haptic = rememberHaptics()

    // ── control action dialog state ──────────────────────────────────────────
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.name) }
    var showKillDialog by remember { mutableStateOf(false) }
    var headerMenuExpanded by remember { mutableStateOf(false) }
    var activePanel by remember { mutableStateOf(SessionPanel.Chat) }

    var pendingEditorOpen by remember(session.id) { mutableStateOf<PendingEditorOpen?>(null) }
    val onOpenFile: (FilePathRef) -> Unit = remember(session.id) {
        { ref ->
            val rel = toWorkdirRelativePath(ref.path, session.workdir, inferHomeDir(session.workdir))
            if (rel == null) {
                Toast.makeText(context, "File is outside this session's project", Toast.LENGTH_SHORT).show()
            } else {
                pendingEditorOpen = PendingEditorOpen(rel, ref.line, ref.endLine)
                activePanel = SessionPanel.Editor
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding(),
    ) {
        // ----------------------------------------------------------------
        // 1. Header
        // ----------------------------------------------------------------
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back button
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 8.dp)
                        .size(22.dp),
                )

                // Avatar — participates in shared-element transition when scopes are provided
                SessionAvatar(
                    name = session.name,
                    agent = session.agent,
                    modifier = Modifier.size(30.dp),
                    sessionId = session.id,
                    sharedScope = sharedScope,
                    animScope = animScope,
                )

                Spacer(Modifier.width(10.dp))

                // Session name + sub-label
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    val subLabel = buildString {
                        if (session.workdir.isNotEmpty()) {
                            append(session.workdir)
                        } else {
                            append(session.agent)
                            session.model?.let { append(" · $it") }
                        }
                    }
                    Text(
                        text = subLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    val badge = gitBadge(session.git)
                    if (badge != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (badge.kind == GitBadgeKind.BASE) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_git_branch),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            val label = if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
                                "${badge.compareRef} ${badge.text}" else badge.text
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Agent pill (only if non-idle). A spinner means "in progress" — so it
                // shows only while working; the dead state is an error-colored label, no spinner.
                // Header shows ONLY the dead "not responding" state — the live running/thinking
                // state is surfaced by the bottom-of-stream status line, so a header pill for it
                // would be redundant.
                if (agent != null && agent.state == "dead") {
                    val error = MaterialTheme.colorScheme.error
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(error.copy(alpha = 0.14f))
                            .border(1.dp, error.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(error))
                        Text(
                            text = "not responding",
                            color = error,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                        )
                    }
                }

                // Exposed proxy links (iOS sessionLinksMenu parity) — also in the phone header.
                if (sessionLinks.isNotEmpty()) {
                    var showLinks by remember { mutableStateOf(false) }
                    val uriHandler = LocalUriHandler.current
                    Box {
                        Icon(
                            painter = painterResource(R.drawable.ic_globe),
                            contentDescription = "Links",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { showLinks = true }
                                .padding(start = 4.dp)
                                .size(20.dp),
                        )
                        DropdownMenu(expanded = showLinks, onDismissRequest = { showLinks = false }) {
                            sessionLinks.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(proxyDisplayUrl(p)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_external_link),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    onClick = { showLinks = false; uriHandler.openUri(proxyUrl(p)) },
                                )
                            }
                        }
                    }
                }

                // Finish — only for worktree-backed sessions (iOS gates on session.session_branch).
                if (session.session_branch != null) {
                    var showFinishSheet by remember(session.id) { mutableStateOf(false) }
                    // Acked startedAt survives rotation/process-death so a result stays "seen".
                    var ackedStartedAt by rememberSaveable(session.id) { mutableStateOf(0.0) }
                    val isUnacked = finishJob != null &&
                        finishJob.status != "running" &&
                        finishJob.startedAt != ackedStartedAt
                    FinishButton(
                        finishJob = finishJob,
                        isUnacked = isUnacked,
                        onClick = {
                            ackedStartedAt = finishJob?.startedAt ?: ackedStartedAt
                            showFinishSheet = true
                        },
                    )
                    if (showFinishSheet) {
                        FinishSheet(
                            session = session,
                            finishJob = finishJob,
                            onReadiness = onFinishReadiness,
                            onFinish = onFinish,
                            onClearJob = onClearFinishJob,
                            onVerifySuggest = onVerifySuggest,
                            onVerifySave = onVerifySave,
                            onSendToAgent = onSendToAgent,
                            onAck = { ackedStartedAt = finishJob?.startedAt ?: ackedStartedAt },
                            onDismiss = { showFinishSheet = false },
                        )
                    }
                }

                // Overflow menu (⋮): rename / mute / kill
                Box {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { headerMenuExpanded = true }
                            .padding(start = 4.dp)
                            .size(20.dp),
                    )
                    DropdownMenu(
                        expanded = headerMenuExpanded,
                        onDismissRequest = { headerMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pencil),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                headerMenuExpanded = false
                                renameText = session.name
                                showRenameDialog = true
                            },
                        )
                        val isMuted = session.mute ?: false
                        DropdownMenuItem(
                            text = { Text(if (isMuted) "Unmute" else "Mute") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(
                                        if (isMuted) R.drawable.ic_volume_2 else R.drawable.ic_volume_x
                                    ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                headerMenuExpanded = false
                                onMute(!isMuted)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Displays") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_monitor),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("chat_overflow_displays"),
                            onClick = {
                                headerMenuExpanded = false
                                onOpenDisplays()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Kill", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trash),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                headerMenuExpanded = false
                                showKillDialog = true
                            },
                        )
                    }
                }
            }

            // Bottom border under header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        // Panel switcher: Chat / Native / Editor / Terminal / Display.
        // Native (raw agent PTY) is gated on the session's agent being "claude" — iOS parity with
        // the gate `(session.agent ?? "claude") == "claude"`. Android's session.agent is non-null.
        val panels = remember(session.agent) {
            SessionPanel.entries.filter { it != SessionPanel.Native || session.agent == "claude" }
        }
        // If the active panel was filtered out (e.g. Native hidden), fall back to Chat.
        LaunchedEffect(panels) {
            if (activePanel !in panels) activePanel = SessionPanel.Chat
        }
        PrimaryTabRow(
            selectedTabIndex = panels.indexOf(activePanel).coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            panels.forEach { panel ->
                val selected = activePanel == panel
                val tag = if (panel == SessionPanel.Native) "tab_native"
                          else "chat_tab_${panel.name.lowercase()}"
                Tab(
                    selected = selected,
                    onClick = { activePanel = panel },
                    modifier = Modifier.testTag(tag),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            panel.name,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                )
            }
        }

        var openedPanels by remember { mutableStateOf(setOf(SessionPanel.Chat)) }
        LaunchedEffect(activePanel) { openedPanels = openedPanels + activePanel }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (SessionPanel.Chat in openedPanels) {
                ChatPanel(
                    session = session,
                    messages = messages,
                    activity = activity,
                    agent = agent,
                    bgTasks = bgTasks,
                    sending = sending,
                    activePanel = activePanel,
                    onSendWith = onSendWith,
                    onInterrupt = onInterrupt,
                    commands = commands,
                    commandsResolved = commandsResolved,
                    onUpload = onUpload,
                    loadBytes = loadBytes,
                    transcribeAudio = transcribeAudio,
                    transcribeDraft = transcribeDraft,
                    loadGlossary = loadGlossary,
                    vmModels = vmModels,
                    vmReasoning = vmReasoning,
                    onPickModel = onPickModel,
                    onPickEffort = onPickEffort,
                    loadDraft = loadDraft,
                    saveDraft = saveDraft,
                    consumePendingFirst = consumePendingFirst,
                    onOpenFile = onOpenFile,
                    onRequestRename = {
                        renameText = session.name
                        showRenameDialog = true
                    },
                    onRequestMute = { onMute(!(session.mute ?: false)) },
                    onRequestKill = { showKillDialog = true },
                    modifier = Modifier.keepAlivePanel(activePanel == SessionPanel.Chat),
                )
            }
            if (SessionPanel.Native in openedPanels) {
                val cat = connectAgentTerminal
                Box(Modifier.keepAlivePanel(activePanel == SessionPanel.Native)) {
                    if (cat != null) {
                        TerminalPanel(
                            connect = cat,
                            modifier = Modifier.fillMaxSize(),
                            active = activePanel == SessionPanel.Native,
                            // Agent PTY exited → fall back to Chat (iOS onExit: { tab = .chat }).
                            onExit = { activePanel = SessionPanel.Chat },
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Native terminal unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (SessionPanel.Editor in openedPanels) {
                EditorPanel(
                    sessionId = session.id,
                    workdir = session.workdir,
                    fsList = fsList,
                    fsRead = fsRead,
                    fsWrite = fsWrite,
                    fsSearch = fsSearch,
                    fsDiff = fsDiff,
                    reviewAddComment = reviewAddComment,
                    reviewResolve = reviewResolve,
                    reviewSubmit = reviewSubmit,
                    fsChanges = fsChanges,
                    lspStatus = lspStatus,
                    lspRpc = lspRpc,
                    editorOpen = editorOpen,
                    editorClose = editorClose,
                    lspStatusQuery = lspStatusQuery,
                    lspOpen = lspOpen,
                    lspRpcOut = lspRpcOut,
                    lspClose = lspClose,
                    onConsumesBackChange = onEditorConsumesBackChange,
                    pendingOpen = pendingEditorOpen,
                    onPendingOpenConsumed = { pendingEditorOpen = null },
                    modifier = Modifier.keepAlivePanel(activePanel == SessionPanel.Editor),
                )
            }
            if (SessionPanel.Terminal in openedPanels) {
                val ct = connectTerminal
                Box(Modifier.keepAlivePanel(activePanel == SessionPanel.Terminal)) {
                    if (ct != null) {
                        TerminalPanel(connect = ct, modifier = Modifier.fillMaxSize(), active = activePanel == SessionPanel.Terminal)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Terminal unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (SessionPanel.Display in openedPanels) {
                val ld = listDisplays
                val cScrcpy = connectScrcpy
                val cVnc = connectVnc
                Box(Modifier.keepAlivePanel(activePanel == SessionPanel.Display)) {
                    if (ld != null && cScrcpy != null && cVnc != null) {
                        DisplayPanel(
                            sessionName = session.name,
                            displays = displays,
                            listDisplays = ld,
                            connectScrcpy = cScrcpy,
                            connectVnc = cVnc,
                            onStartDisplay = onStartDisplay,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Display unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // ── rename dialog ────────────────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(renameText)
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── kill confirm dialog ──────────────────────────────────────────────────
    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { showKillDialog = false },
            title = { Text("Kill session?") },
            text = { Text("This will terminate \"${session.name}\" immediately.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic(HapticKind.Heavy)
                        showKillDialog = false
                        onKill()
                    },
                ) { Text("Kill") }
            },
            dismissButton = {
                TextButton(onClick = { showKillDialog = false }) { Text("Cancel") }
            },
        )
    }

}
