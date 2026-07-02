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
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import java.io.File
import dev.supermux.android.R
import dev.supermux.android.theme.Radii
import dev.supermux.util.formatDuration
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
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.gitBadge

enum class SessionPanel { Chat, Native, Editor, Terminal, Display }

/**
 * Active "/command" token at the END of the draft (cursor assumed at end), at line start or
 * after whitespace — mirrors iOS slashQuery (ChatPane.swift:508). Group 1 is the slash token.
 */
private val slashTokenRegex = Regex("""(?:^|\s)(/\S*)$""")

/** Stable list key for timeline diffing so the optimistic→real id swap (§9) doesn't flicker. */
private fun timelineItemKey(item: TimelineItem): String = when (item) {
    is TimelineItem.Msg -> "m:${item.entry.id}"
    is TimelineItem.Tool -> "t:${item.event.callId ?: "${item.event.kind}:${item.event.seq}:${item.event.ts}"}"
    is TimelineItem.Act -> "a:${item.event.seq ?: -1}:${item.event.ts}"
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    sending: Boolean = false,
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = rememberHaptics()

    // ── attachment state: list of (fileId, displayName, uploading) ────────────
    // uploading==true while the upload coroutine is in-flight
    data class PendingAttachment(val fileId: String, val name: String, val uploading: Boolean)
    val pendingAttachments = remember { mutableStateListOf<PendingAttachment>() }

    // Shared upload flow for ALL attachment sources (Photos / Files / Camera). Reads the URI's
    // bytes, stages a placeholder chip (uploading=true), uploads, then swaps in the real fileId
    // (or drops the chip on failure). The placeholder object is tracked by identity so concurrent
    // uploads can't clobber each other's index.
    suspend fun stageFromUri(uri: Uri) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return
        val placeholder = PendingAttachment(fileId = "", name = name, uploading = true)
        withContext(Dispatchers.Main) { pendingAttachments.add(placeholder) }
        val fileId = onUpload(bytes, name, mime, null)
        withContext(Dispatchers.Main) {
            val idx = pendingAttachments.indexOf(placeholder)
            if (idx < 0) return@withContext
            if (fileId != null) {
                pendingAttachments[idx] = PendingAttachment(fileId, name, uploading = false)
            } else {
                pendingAttachments.removeAt(idx)
            }
        }
    }

    // Clipboard helpers for paste-to-attach (web parity). Reading the clip *description* (mime
    // types) is cheap and avoids the OS "pasted from clipboard" toast; only reading the clip data
    // on the actual paste shows it, which is expected for a user-initiated action.
    fun clipboardHasImage(): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val desc = cm.primaryClipDescription ?: return false
        return (0 until desc.mimeTypeCount).any { isAttachableMediaMime(desc.getMimeType(it)) }
    }

    fun clipboardImageUris(): List<Uri> {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return emptyList()
        val clip = cm.primaryClip ?: return emptyList()
        return (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it).uri }
            .filter { isAttachableMediaMime(context.contentResolver.getType(it)) }
    }

    // Files: system document picker (any mime).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) scope.launch { stageFromUri(uri) }
    }

    // Photos: modern visual-media picker (no storage permission; backported on Play services).
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) scope.launch { stageFromUri(uri) }
    }

    // Camera: delegated capture to the system camera app, writing into our FileProvider URI.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok: Boolean ->
        val uri = cameraUri
        if (ok && uri != null) scope.launch { stageFromUri(uri) }
    }

    // Composer draft text. Hoisted here (not inside the composer Column) so the shared dictation
    // controller (below) can append cleaned/raw transcripts into the same state the BasicTextField
    // edits, via its `onAppend` sink (risk §5).
    var text by remember { mutableStateOf("") }

    // ── per-session draft persistence (DataStore; survives switch + process death) §3 ──
    // Load once per session (parity with iOS loadPane). `draftLoaded` gates the save effect so
    // the initial restore (or an empty load) never clobbers a draft before it is read back.
    var draftLoaded by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id) {
        draftLoaded = false
        text = loadDraft(session.id)
        draftLoaded = true
    }
    // Persist, debounced (~400ms) — avoids a DataStore write per keystroke. Clearing on send
    // sets text="" which writes empty through this same effect.
    LaunchedEffect(session.id, text, draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        delay(400)
        saveDraft(session.id, text)
    }

    // Voice dictation (record → broker cleanup → into the composer draft `text`). The drive logic,
    // permission flow, and RecordingBar all live in Dictation.kt so chat and the new-session
    // launcher share ONE implementation (the launcher previously skipped the cleanup pass).
    val mic = rememberDictation(
        resetKey = session.id,
        loadGlossary = loadGlossary,
        transcribeDraft = transcribeDraft,
        transcribeAudio = transcribeAudio,
        onAppend = { text = if (text.isBlank()) it else text.trimEnd() + " " + it },
    )

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
            "stop" -> onInterrupt()
            "spawn" -> { /* TODO: spawn from control command (needs nav; iOS also skips) */ }
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
                if (agent != null && agent.state != "idle") {
                    val isDead = agent.state == "dead"
                    val pillColor = if (isDead) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(pillColor.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (!isDead) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            text = when {
                                isDead -> "Not responding"
                                agent.detail == "running" -> "Running"
                                else -> "Thinking"
                            },
                            color = pillColor,
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
                Column(Modifier.keepAlivePanel(activePanel == SessionPanel.Chat)) {
        // ----------------------------------------------------------------
        // 2. Timeline
        // ----------------------------------------------------------------
        val timelineItems = remember(messages, activity) { mergeTimeline(messages, activity) }
        val listState = rememberLazyListState()
        var prevTimelineSize by remember { mutableIntStateOf(0) }

        // Working ⇔ the broker says the agent is busy (iOS workingIndicator gate). Drives both the
        // bottom WorkingIndicator row and the auto-scroll target (so the spinner stays in view).
        val working = agent?.working == true

        // Auto-scroll on new content AND when the working row appears/disappears.
        LaunchedEffect(timelineItems.size, working, activePanel) {
            if (activePanel != SessionPanel.Chat) return@LaunchedEffect
            val target = timelineItems.size - 1 + (if (working) 1 else 0)
            if (target >= 0 && (timelineItems.size > prevTimelineSize || working)) {
                listState.animateScrollToItem(target)
            }
            prevTimelineSize = timelineItems.size
        }

        if (timelineItems.isEmpty() && !working) {
            // ── Empty session: starter prompts (iOS ChatPane empty state) ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Spacer(Modifier.height(36.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_sparkle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    "Start the conversation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                listOf("What's the current state?", "Run the tests", "Summarize recent changes")
                    .forEachIndexed { i, prompt ->
                        Surface(
                            onClick = {
                                haptic(HapticKind.Confirm)
                                onSendWith(prompt, emptyList())
                            },
                            shape = RoundedCornerShape(Radii.md),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_starter_$i"),
                        ) {
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.md),
                            )
                        }
                    }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
            ) {
                items(timelineItems, key = { timelineItemKey(it) }) { item ->
                    TimelineItemRow(item, loadBytes, onOpenFile)
                }
                // Live working indicator pinned to the bottom (iOS renders it below the last block).
                if (working && agent != null) {
                    item(key = "__working__") {
                        WorkingIndicator(agent, onStop = onInterrupt)
                    }
                } else if (sending) {
                    item(key = "__sending__") {
                        SendingIndicator(onStop = onInterrupt)
                    }
                }
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

            // ── slash-command menu: active "/token" at end of draft (start-of-line or after
            //    whitespace), filtering on name OR family by `contains`, capped at 8 (iOS parity) ──
            val slashMatch = slashTokenRegex.find(text)
            val slashQuery = slashMatch?.groupValues?.get(1)?.drop(1)?.lowercase()
            val slashMatches = if (slashQuery != null) {
                commands.filter {
                    slashQuery.isEmpty() ||
                        it.name.contains(slashQuery, ignoreCase = true) ||
                        it.family.contains(slashQuery, ignoreCase = true)
                }.take(8)
            } else emptyList()

            // Replace the active "/token" with [insert], preserving any leading whitespace.
            fun replaceSlashToken(insert: String) {
                val m = slashTokenRegex.find(text) ?: run { text = insert; return }
                val lead = m.value.takeWhile { it == ' ' || it == '\n' || it == '\t' }
                text = text.substring(0, m.range.first) + lead + insert
            }

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
                                .testTag("chat_slash_item_${cmd.name}")
                                .clickable {
                                    haptic(HapticKind.Tick)
                                    val action = cmd.action
                                    if (action != null) {
                                        replaceSlashToken("")
                                        onControl(cmd)
                                    } else {
                                        replaceSlashToken(
                                            cmd.insertText?.ifEmpty { null } ?: "${cmd.sigil}${cmd.name} "
                                        )
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
                            // Trailing "executes" glyph for control commands (iOS bolt.fill).
                            if (cmd.action != null) {
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    painter = painterResource(R.drawable.ic_zap),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(13.dp),
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
            } else if (slashQuery != null && !commandsResolved) {
                // §1.5 loading hint: a fresh session whose command set hasn't resolved yet.
                Text(
                    text = "Loading commands…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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
            mic.banner?.let { msg ->
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
            if (mic.transcribing) TranscribingIndicator()

            // ── Composer takeover: RecordingBar replaces the card while dictating ──
            if (mic.recording || mic.listening) {
                RecordingBar(
                    seconds = mic.recordingSeconds,
                    liveTranscript = mic.liveTranscript,
                    onStop = { mic.stopMic() },
                    onCancel = { mic.cancelMic() },
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
                            .testTag("chat_composer")
                            // Paste/drag a copied image straight into the box (web parity). Text
                            // and non-image content falls through to the field's normal handling.
                            .contentReceiver { transferable ->
                                transferable.consume { item ->
                                    val uri = item.uri
                                    if (uri != null &&
                                        isAttachableMediaMime(context.contentResolver.getType(uri))) {
                                        scope.launch { stageFromUri(uri) }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            },
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

                    // Attach (+) button → menu: Photos / Files / Camera (iOS + menu parity).
                    var attachMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { attachMenu = true }) {
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
                        DropdownMenu(
                            expanded = attachMenu,
                            onDismissRequest = { attachMenu = false },
                        ) {
                            // Paste — only offered when the clipboard actually holds an image.
                            if (clipboardHasImage()) {
                                DropdownMenuItem(
                                    text = { Text("Paste") },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_copy), null, modifier = Modifier.size(18.dp))
                                    },
                                    modifier = Modifier.testTag("attach_menu_paste"),
                                    onClick = {
                                        attachMenu = false
                                        scope.launch { clipboardImageUris().forEach { stageFromUri(it) } }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Photos") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_image), null, modifier = Modifier.size(18.dp))
                                },
                                modifier = Modifier.testTag("attach_menu_photos"),
                                onClick = {
                                    attachMenu = false
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Files") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_file), null, modifier = Modifier.size(18.dp))
                                },
                                modifier = Modifier.testTag("attach_menu_files"),
                                onClick = {
                                    attachMenu = false
                                    filePickerLauncher.launch("*/*")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_camera), null, modifier = Modifier.size(18.dp))
                                },
                                modifier = Modifier.testTag("attach_menu_camera"),
                                onClick = {
                                    attachMenu = false
                                    val uri = createImageUri(context)
                                    cameraUri = uri
                                    takePicture.launch(uri)
                                },
                            )
                        }
                    }

                    // Mic button — starts dictation (the RecordingBar takes over while active).
                    // Disabled while a transcription POST is in flight. 48dp tap target / 32dp visual.
                    MicButton(
                        onClick = { mic.onMicClick() },
                        enabled = !mic.transcribing,
                        modifier = Modifier.testTag("chat_mic"),
                    )

                    // Circular send button — ALWAYS sends (iOS parity); the Stop/interrupt
                    // affordance lives in the transcript WorkingIndicator, not here. Scale press +
                    // confirm haptic; dims when there is nothing to send.
                    IconButton(
                        onClick = { doSend() },
                        enabled = canSend,
                        interactionSource = sendInteractionSource,
                        modifier = Modifier.testTag("chat_send"),
                    ) {
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
                                painter = painterResource(R.drawable.ic_send),
                                contentDescription = "Send",
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
            if (SessionPanel.Native in openedPanels) {
                val cat = connectAgentTerminal
                Box(Modifier.keepAlivePanel(activePanel == SessionPanel.Native)) {
                    if (cat != null) {
                        TerminalPanel(
                            connect = cat,
                            modifier = Modifier.fillMaxSize(),
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

    // ── mic-permission-denied dialog (parity with iOS ChatPane) ───────────────
    if (mic.micDenied) MicDeniedDialog(onDismiss = { mic.micDenied = false })

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
 * Live "Working… · Ns" indicator pinned to the bottom of the transcript (iOS workingIndicator
 * parity): a small spinner + phase label + elapsed duration, and a red Stop capsule that
 * interrupts the running agent. Ticks every 1s, recomputing elapsed from `agent.since` (epoch-ms).
 */
@Composable
private fun WorkingIndicator(agent: AgentStatus, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = agent.workingSince?.let { ((now - it).coerceAtLeast(0)) / 1000 }
    val label = when (agent.detail) {
        "running" -> "Working…"
        else -> "Thinking…"
    }
    Row(
        modifier = Modifier.padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = cs.primary,
        )
        Text(
            text = label + (elapsed?.let { " · " + formatDuration(it) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        // Red Stop capsule → interrupt
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.error.copy(alpha = 0.12f))
                .clickable { haptic(HapticKind.Tick); onStop() }
                .testTag("working_stop")
                .padding(horizontal = Space.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_square),
                contentDescription = "Stop",
                tint = cs.error,
                modifier = Modifier.size(11.dp),
            )
            Text("Stop", style = MaterialTheme.typography.labelMedium, color = cs.error)
        }
    }
}

/**
 * Client-local "Sending…" indicator shown between the user tapping Send and the first
 * `agent_state` frame arriving from the broker. No timer (no elapsed), static label.
 * Same Stop capsule as WorkingIndicator so the user can cancel immediately after sending.
 */
@Composable
private fun SendingIndicator(onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    Row(
        modifier = Modifier.padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = cs.primary,
        )
        Text(
            text = "Sending…",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        // Red Stop capsule → interrupt
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.error.copy(alpha = 0.12f))
                .clickable { haptic(HapticKind.Tick); onStop() }
                .testTag("sending_stop")
                .padding(horizontal = Space.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_square),
                contentDescription = "Stop",
                tint = cs.error,
                modifier = Modifier.size(11.dp),
            )
            Text("Stop", style = MaterialTheme.typography.labelMedium, color = cs.error)
        }
    }
}

/**
 * Create a FileProvider URI for a fresh camera capture in cacheDir/attachments (the path already
 * declared in file_paths.xml + reused by openAttachment). The system camera app writes the JPEG
 * here, then [stageFromUri] reads it back and uploads it.
 */
private fun createImageUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
