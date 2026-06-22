package dev.supermux.android.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import dev.supermux.android.DevConfig
import dev.supermux.android.R
import dev.supermux.android.display.DisplayPanel
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.android.editor.EditorPanel
import dev.supermux.android.terminal.TerminalPanel
import dev.supermux.android.session.SessionAvatar
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand

enum class SessionPanel { Chat, Editor, Terminal, Display }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    onBack: () -> Unit,
    onSendWith: (text: String, attachments: List<String>) -> Unit,
    onUpload: suspend (bytes: ByteArray, name: String, mime: String, kind: String?) -> String?,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    transcribeDraft: suspend (draft: String) -> String? = { null },
    loadGlossary: suspend () -> List<String> = { emptyList() },
    onRename: (String) -> Unit = {},
    onMute: (Boolean) -> Unit = {},
    onKill: () -> Unit = {},
    vmModels: suspend (String) -> ModelsResponse? = { null },
    vmReasoning: suspend (String) -> ReasoningResponse? = { null },
    onPickModel: (String) -> Unit = {},
    onPickEffort: (String) -> Unit = {},
    commands: List<SlashCommand> = emptyList(),
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
    listDisplays: (suspend () -> List<dev.supermux.net.DisplayStream>)? = null,
    connectScrcpy: ((String) -> dev.supermux.net.ScrcpyClient)? = null,
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = rememberHaptics()

    // ── attachment state: list of (fileId, displayName, uploading) ────────────
    // uploading==true while the upload coroutine is in-flight
    data class PendingAttachment(val fileId: String, val name: String, val uploading: Boolean)
    val pendingAttachments = remember { mutableStateListOf<PendingAttachment>() }

    // file picker launcher — result: read bytes, name, mime, then upload
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            // add a placeholder chip with uploading=true
            val placeholder = PendingAttachment(fileId = "", name = name, uploading = true)
            withContext(Dispatchers.Main) { pendingAttachments.add(placeholder) }
            val idx = pendingAttachments.lastIndex
            val fileId = onUpload(bytes, name, mime, null)
            withContext(Dispatchers.Main) {
                if (fileId != null) {
                    pendingAttachments[idx] = PendingAttachment(fileId, name, uploading = false)
                } else {
                    // upload failed — remove the placeholder
                    pendingAttachments.removeAt(idx)
                }
            }
        }
    }

    // ── voice dictation (record → transcribe → into composer) ─────────────────
    // Composer draft text. Hoisted here (not inside the composer Column) so the dictation
    // drive logic below can append cleaned/raw transcripts into the same state the
    // BasicTextField edits (risk §5). If `text` is ever moved to the VM, move appendToDraft too.
    var text by remember { mutableStateOf("") }

    val recorder = remember { VoiceRecorder(context) }
    val dictation = remember { DictationEngine(context) }
    var recording by remember { mutableStateOf(false) }     // audio (whisper) path active
    var listening by remember { mutableStateOf(false) }     // on-device STT active
    var transcribing by remember { mutableStateOf(false) }  // POST in flight ("Transcribing…")
    var liveTranscript by remember { mutableStateOf("") }   // on-device partials
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var micDenied by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    val glossary = remember { mutableStateListOf<String>() }
    LaunchedEffect(session.id) { glossary.clear(); glossary.addAll(loadGlossary()) }

    // Elapsed-seconds timer while either recording mode is active.
    LaunchedEffect(recording || listening) {
        if (recording || listening) {
            recordingSeconds = 0
            while (true) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    // Auto-clear the transient banner (~4s), parity with iOS showBanner.
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(4000)
            banner = null
        }
    }

    // ── dictation drive logic (kept here so it shares `text`/state) ───────────
    fun appendToDraft(s: String) {
        val t = s.trim()
        if (t.isEmpty()) return
        text = if (text.isBlank()) t else text.trimEnd() + " " + t
    }

    suspend fun runTranscription(rawFallback: String?, call: suspend () -> String?) {
        transcribing = true
        try {
            val cleaned = call()?.trim()
            when {
                !cleaned.isNullOrEmpty() -> appendToDraft(cleaned)
                !rawFallback.isNullOrBlank() -> appendToDraft(rawFallback)  // keep on-device draft
                else -> banner = "Transcription failed"                     // nothing to keep
            }
        } finally {
            transcribing = false
        }
    }

    fun startMic() {
        haptic(HapticKind.Tick)
        val started =
            if (DevConfig.ENABLE_ONDEVICE_STT) dictation.start(glossary.toList())
            else DictationStart.UNAVAILABLE
        when (started) {
            DictationStart.STARTED -> {
                listening = true
                liveTranscript = ""
                dictation.onPartial = { liveTranscript = it }
            }
            DictationStart.DENIED -> micDenied = true
            DictationStart.UNAVAILABLE -> {  // whisper path
                recorder.start()
                recording = true
            }
        }
    }

    fun stopMic() {
        haptic(HapticKind.Tick)
        if (listening) {
            listening = false
            val draft = dictation.stop()
            if (draft.isBlank()) { banner = "Didn't catch that"; return }
            scope.launch { runTranscription(rawFallback = draft) { transcribeDraft(draft) } }
        } else if (recording) {
            recording = false
            val f = recorder.stop()
            if (f == null) { banner = "Didn't catch that"; return }
            scope.launch(Dispatchers.IO) {
                val bytes = f.readBytes()
                val name = f.name
                withContext(Dispatchers.Main) {
                    runTranscription(rawFallback = null) { transcribeAudio(bytes, name) }
                }
            }
        }
    }

    fun cancelMic() {
        dictation.cancel()
        recorder.cancel()
        listening = false
        recording = false
        liveTranscript = ""
    }

    // Mic needs RECORD_AUDIO for BOTH paths (MediaRecorder + SpeechRecognizer). A fresh grant
    // routes through startMic() so it makes the same on-device-vs-audio decision. A permanent
    // denial returns granted=false with no re-prompt → show the "enable in Settings" dialog.
    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startMic() else micDenied = true
    }

    fun onMicClick() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) startMic()
        else audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Cancel any in-flight recording when this session leaves composition / is switched away,
    // so a backgrounded recording never leaks the mic or posts stale audio (risk §6, iOS parity).
    DisposableEffect(session.id) {
        onDispose { cancelMic() }
    }

    // ── model picker state ───────────────────────────────────────────────────
    var modelsData by remember { mutableStateOf<ModelsResponse?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }

    // ── reasoning/effort picker state ────────────────────────────────────────
    var reasoningData by remember { mutableStateOf<ReasoningResponse?>(null) }
    var showEffortSheet by remember { mutableStateOf(false) }
    // show effort pill only when the server says visible=true and there are multiple levels
    var effortVisible by remember { mutableStateOf(false) }

    // ── control action dialog state ──────────────────────────────────────────
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.name) }
    var showKillDialog by remember { mutableStateOf(false) }
    var headerMenuExpanded by remember { mutableStateOf(false) }
    var activePanel by remember { mutableStateOf(SessionPanel.Chat) }

    // On first composition (or session change) fetch reasoning to decide pill visibility
    LaunchedEffect(session.id) {
        val resp = withContext(Dispatchers.IO) { vmReasoning(session.id) }
        reasoningData = resp
        effortVisible = resp != null && resp.visible && resp.levels.size > 1
    }

    LaunchedEffect(session.id) {
        val pending = consumePendingFirst(session.id) ?: return@LaunchedEffect
        onSendWith(pending.text, pending.attachments)
    }

    // ── onControl: handle control commands internally by reusing dialog/sheet state ──
    val onControl: (SlashCommand) -> Unit = { cmd ->
        when (cmd.action?.kind) {
            "rename" -> {
                renameText = session.name
                showRenameDialog = true
            }
            "mute" -> onMute(!(session.mute ?: false))
            "kill" -> showKillDialog = true
            "model" -> {
                scope.launch {
                    val resp = withContext(Dispatchers.IO) { vmModels(session.id) }
                    modelsData = resp
                    showModelSheet = true
                }
            }
            "spawn" -> { /* TODO: spawn from control command */ }
            else -> {}
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
                }

                // Agent pill (only if non-idle)
                if (agent != null && agent.phase != "idle") {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = agent.phase.replaceFirstChar { it.uppercaseChar() },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
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

        // Panel switcher: Chat / Editor / Terminal / Display
        val panels = SessionPanel.entries
        PrimaryTabRow(
            selectedTabIndex = panels.indexOf(activePanel),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            panels.forEach { panel ->
                val selected = activePanel == panel
                Tab(
                    selected = selected,
                    onClick = { activePanel = panel },
                    modifier = Modifier.testTag("chat_tab_${panel.name.lowercase()}"),
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
                Column(Modifier.keepAlivePanel(activePanel == SessionPanel.Chat)) {
        // ----------------------------------------------------------------
        // 2. Timeline
        // ----------------------------------------------------------------
        val timelineItems = remember(messages, activity) { mergeTimeline(messages, activity) }
        val listState = rememberLazyListState()
        var prevTimelineSize by remember { mutableIntStateOf(0) }

        LaunchedEffect(timelineItems.size, activePanel) {
            if (activePanel != SessionPanel.Chat) return@LaunchedEffect
            if (timelineItems.isNotEmpty() && timelineItems.size > prevTimelineSize) {
                listState.animateScrollToItem(timelineItems.size - 1)
            }
            prevTimelineSize = timelineItems.size
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            items(timelineItems) { item ->
                TimelineItemRow(item, loadBytes)
            }
        }

        // ----------------------------------------------------------------
        // 3. Composer
        // ----------------------------------------------------------------
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            // Top border above composer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // ── slash-command menu: shown when text starts with "/" ───────
            val slashQuery = if (text.startsWith("/")) text.drop(1).lowercase() else null
            val slashMatches = if (slashQuery != null) {
                commands.filter { it.name.startsWith(slashQuery, ignoreCase = true) }
            } else emptyList()

            if (slashMatches.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    slashMatches.forEach { cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (cmd.family == "agent") {
                                        text = cmd.insertText ?: "/${cmd.name} "
                                    } else {
                                        text = ""
                                        onControl(cmd)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${cmd.sigil}${cmd.name}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(120.dp),
                            )
                            val desc = cmd.description
                            if (desc != null) {
                                Text(
                                    text = desc,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                // Separator between menu and composer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }

            // ── pending attachment chips ─────────────────────────────────
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pendingAttachments.forEachIndexed { idx, att ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (att.uploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 1.5.dp,
                                )
                            }
                            Text(
                                text = att.name,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                            if (!att.uploading) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_x),
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { pendingAttachments.removeAt(idx) }
                                        .padding(start = 2.dp)
                                        .size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Composer interaction sources for focus border + send scale animations
            val composerInteractionSource = remember { MutableInteractionSource() }
            val composerFocused by composerInteractionSource.collectIsFocusedAsState()
            val composerBorderAlpha by animateFloatAsState(
                targetValue = if (composerFocused) 0.5f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "composer_border_alpha",
            )

            val sendInteractionSource = remember { MutableInteractionSource() }
            val sendPressed by sendInteractionSource.collectIsPressedAsState()
            val sendScale by animateFloatAsState(
                targetValue = if (sendPressed) 0.88f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "send_scale",
            )

            // ── Composer card ────────────────────────────────────────────────
            val focusBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = composerBorderAlpha)

            // Single send path used by BOTH the send button and the IME Send action.
            val canSend = text.isNotBlank() || pendingAttachments.any { !it.uploading }
            fun doSend() {
                if (!canSend) return
                haptic(HapticKind.Confirm)
                val attachmentIds = pendingAttachments
                    .filter { !it.uploading }
                    .map { it.fileId }
                onSendWith(text, attachmentIds)
                text = ""
                pendingAttachments.clear()
            }

            // ── transient banner (transcription failed / didn't catch that) ──
            banner?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // ── "Transcribing…" indicator (parity with iOS transcribingBar) ──
            if (transcribing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 1.5.dp,
                    )
                    Text(
                        text = "Transcribing…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // ── Composer takeover: RecordingBar replaces the card while dictating ──
            if (recording || listening) {
                RecordingBar(
                    seconds = recordingSeconds,
                    liveTranscript = liveTranscript,
                    onStop = { stopMic() },
                    onCancel = { cancelMic() },
                )
            } else Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                // ── Text input area (card background, animated focus border) ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, focusBorderColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Message ${session.name}…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = composerInteractionSource,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_composer"),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // ── Toolbar row: [Model pill] [Effort pill?]  <spacer>  [+] [mic] [● send] ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    // Model pill → opens model picker
                    ModelPill(
                        current = modelsData?.current ?: session.model,
                        onClick = {
                            scope.launch {
                                val resp = withContext(Dispatchers.IO) { vmModels(session.id) }
                                modelsData = resp
                                showModelSheet = true
                            }
                        },
                    )

                    // Effort pill — only when visible
                    if (effortVisible) {
                        EffortPill(
                            current = reasoningData?.current,
                            onClick = {
                                scope.launch {
                                    val resp = withContext(Dispatchers.IO) { vmReasoning(session.id) }
                                    reasoningData = resp
                                    effortVisible = resp != null && resp.visible && resp.levels.size > 1
                                    showEffortSheet = true
                                }
                            },
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Attach (+) button — 48dp tap target wraps the 32dp visual
                    IconButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_plus),
                                contentDescription = "Attach",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Mic button — starts dictation (the RecordingBar takes over while active).
                    // Disabled while a transcription POST is in flight. 48dp tap target / 32dp visual.
                    IconButton(
                        onClick = { onMicClick() },
                        enabled = !transcribing,
                        modifier = Modifier.testTag("chat_mic"),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic),
                                contentDescription = "Record voice",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    // Circular send button — scale press + confirm haptic; dims when nothing to send.
                    // 48dp tap target (IconButton) wraps the 38dp circular visual.
                    IconButton(
                        onClick = { doSend() },
                        enabled = canSend,
                        interactionSource = sendInteractionSource,
                        modifier = Modifier.testTag("chat_send"),
                    ) {
                        // Stop affordance (square) while the agent is working; send (↵) otherwise
                        val agentWorking = agent != null && agent.phase != "idle"
                        Box(
                            modifier = Modifier
                                .scale(sendScale)
                                .size(38.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (agentWorking) R.drawable.ic_square else R.drawable.ic_send
                                ),
                                contentDescription = if (agentWorking) "Stop" else "Send",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
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
                    modifier = Modifier.keepAlivePanel(activePanel == SessionPanel.Editor),
                )
            }
            if (SessionPanel.Terminal in openedPanels) {
                val ct = connectTerminal
                Box(Modifier.keepAlivePanel(activePanel == SessionPanel.Terminal)) {
                    if (ct != null) {
                        TerminalPanel(connect = ct, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Terminal unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (SessionPanel.Display in openedPanels) {
                val ld = listDisplays
                val cs = connectScrcpy
                Box(Modifier.keepAlivePanel(activePanel == SessionPanel.Display)) {
                    if (ld != null && cs != null) {
                        DisplayPanel(
                            sessionName = session.name,
                            listDisplays = ld,
                            connect = cs,
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

    // ── mic-permission-denied dialog (parity with iOS ChatPane) ───────────────
    if (micDenied) {
        AlertDialog(
            onDismissRequest = { micDenied = false },
            title = { Text("Microphone access needed") },
            text = { Text("Enable microphone access in Settings to dictate messages.") },
            confirmButton = {
                TextButton(onClick = { micDenied = false }) { Text("OK") }
            },
        )
    }

    // ── bottom sheets ────────────────────────────────────────────────────────
    if (showModelSheet) {
        val opts = modelsData?.models?.map { it.id to it.displayName } ?: emptyList()
        PickerSheet(
            title = "Select Model",
            options = opts,
            current = modelsData?.current,
            onPick = { onPickModel(it) },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showEffortSheet) {
        val opts = reasoningData?.levels?.map { it.id to (it.description ?: it.id) } ?: emptyList()
        PickerSheet(
            title = "Select Effort Level",
            options = opts,
            current = reasoningData?.current,
            onPick = { onPickEffort(it) },
            onDismiss = { showEffortSheet = false },
        )
    }
}

/**
 * Recording takeover of the composer row (parity with iOS RecordingBar): a small de-emphasized
 * trash CANCEL far left, a blinking red dot + mono timer, and a big primary STOP where Send
 * normally sits. When on-device STT has partial text, a scrollable live transcript sits above.
 *
 * Touch-target rule: STOP is a 48dp visual inside a ≥48dp IconButton (the obvious large target);
 * CANCEL is a 32dp visual inside the 48dp IconButton min-size, so an accidental cancel is hard.
 */
@Composable
private fun RecordingBar(
    seconds: Int,
    liveTranscript: String,   // "" when audio-only (no on-device)
    onStop: () -> Unit,       // big STOP (transcribe)
    onCancel: () -> Unit,     // small trash (discard)
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        // Live transcript area (only when on-device STT has partial text). maxHeight ~120dp, scroll.
        if (liveTranscript.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(liveTranscript, color = cs.onSurface, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // 1) Small de-emphasized CANCEL (trash), 48dp tap target / 32dp visual, far left.
            IconButton(onClick = onCancel, modifier = Modifier.testTag("voice_cancel")) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_trash),
                        contentDescription = "Discard recording",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 2) Blinking red dot + mono timer
            val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "dot",
            )
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(cs.error.copy(alpha = blink)),
            )
            Text(
                "%d:%02d".format(seconds / 60, seconds % 60),
                color = cs.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            // 3) BIG STOP — primary, where Send normally sits. 48dp filled circle, ≥48dp target.
            IconButton(onClick = onStop, modifier = Modifier.testTag("voice_stop")) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_square),
                        contentDescription = "Stop and transcribe",
                        tint = cs.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
