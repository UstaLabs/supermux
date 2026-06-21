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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    connectTerminal: (() -> dev.supermux.net.TerminalClient)? = null,
    listDisplays: (suspend () -> List<dev.supermux.net.DisplayStream>)? = null,
    connectScrcpy: ((String) -> dev.supermux.net.ScrcpyClient)? = null,
    consumePendingFirst: (String) -> dev.supermux.android.AppViewModel.PendingFirstMessage? = { null },
    onEditorConsumesBackChange: (Boolean) -> Unit = {},
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

    // ── voice recorder ───────────────────────────────────────────────────────
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    // Elapsed-seconds timer while recording
    LaunchedEffect(recording) {
        if (recording) {
            recordingSeconds = 0
            while (true) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    // Permission launcher — once granted, start recording
    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            recorder.start()
            recording = true
        }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SessionPanel.entries.forEach { panel ->
                val selected = activePanel == panel
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { activePanel = panel }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        panel.name,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

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

            var text by remember { mutableStateOf("") }

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
            Column(
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
                    if (recording) {
                        // Recording indicator: red dot + elapsed time
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.error),
                            )
                            val mm = recordingSeconds / 60
                            val ss = recordingSeconds % 60
                            Text(
                                text = "Recording %d:%02d".format(mm, ss),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 14.sp,
                            )
                        }
                    } else {
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // ── Toolbar row: [Model pill] [Effort pill?]  <spacer>  [+] [🎤] [● send] ──
                val canSend = text.isNotBlank() || pendingAttachments.any { !it.uploading }
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

                    // Attach (+) button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { filePickerLauncher.launch("*/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus),
                            contentDescription = "Add attachment",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // Mic button — tap to start/stop recording
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                if (recording) MaterialTheme.colorScheme.error // red while recording
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .clickable {
                                if (recording) {
                                    // Stop recording — tick haptic on stop
                                    haptic(HapticKind.Tick)
                                    recording = false
                                    val f = recorder.stop()
                                    if (f != null) {
                                        scope.launch(Dispatchers.IO) {
                                            val bytes = f.readBytes()
                                            val name = f.name
                                            val placeholder = PendingAttachment(fileId = "", name = "🎤 voice", uploading = true)
                                            withContext(Dispatchers.Main) { pendingAttachments.add(placeholder) }
                                            val idx = pendingAttachments.lastIndex
                                            val fileId = onUpload(bytes, name, "audio/mp4", "voice")
                                            withContext(Dispatchers.Main) {
                                                if (fileId != null) {
                                                    pendingAttachments[idx] = PendingAttachment(fileId, "🎤 voice", uploading = false)
                                                } else {
                                                    pendingAttachments.removeAt(idx)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Start recording — check/request permission; tick haptic on start
                                    val hasPerm = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasPerm) {
                                        haptic(HapticKind.Tick)
                                        recorder.start()
                                        recording = true
                                    } else {
                                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = "Record voice",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    // Circular send button — scale press + confirm haptic; dims when nothing to send
                    Box(
                        modifier = Modifier
                            .scale(sendScale)
                            .size(38.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            )
                            .clickable(
                                interactionSource = sendInteractionSource,
                                indication = null,
                                enabled = canSend,
                            ) {
                                haptic(HapticKind.Confirm)
                                val attachmentIds = pendingAttachments
                                    .filter { !it.uploading }
                                    .map { it.fileId }
                                onSendWith(text, attachmentIds)
                                text = ""
                                pendingAttachments.clear()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Stop affordance (square) while the agent is working; send (↵) otherwise
                        val agentWorking = agent != null && agent.phase != "idle"
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
            if (SessionPanel.Editor in openedPanels) {
                EditorPanel(
                    fsList = fsList,
                    fsRead = fsRead,
                    fsWrite = fsWrite,
                    fsSearch = fsSearch,
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
