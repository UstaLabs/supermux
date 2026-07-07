package dev.supermux.android.chat

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.ui.FilePathRef
import dev.supermux.util.formatDuration
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicLong

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

/**
 * The reusable chat body — transcript timeline + composer — extracted verbatim from [ChatScreen]
 * so both the phone screen and the tablet multi-pane workspace can render it. Behavior is
 * identical to the inline version; the caller supplies the panel modifier (e.g.
 * `Modifier.keepAlivePanel(...)`), which already applies fillMaxSize/alpha.
 *
 * All composer-owned state (draft text + persistence, attachments, dictation, model/effort
 * pickers, the activity-result launchers) lives here. Session-control slash actions that open
 * ChatScreen-level dialogs (rename / mute / kill) are surfaced through the [onRequestRename],
 * [onRequestMute], and [onRequestKill] callbacks; the model/effort/stop slash actions are handled
 * internally (they touch composer-owned sheets or the already-passed [onInterrupt]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPanel(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    sending: Boolean,
    activePanel: SessionPanel,
    onSendWith: (text: String, attachments: List<String>) -> Unit,
    onInterrupt: () -> Unit,
    commands: List<SlashCommand>,
    commandsResolved: Boolean,
    onUpload: suspend (source: ChunkSource, name: String, mime: String, kind: String?, onProgress: (Long, Long) -> Unit) -> String?,
    loadBytes: suspend (String) -> ByteArray?,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String?,
    transcribeDraft: suspend (draft: String) -> String?,
    loadGlossary: suspend () -> List<String>,
    vmModels: suspend (String) -> ModelsResponse?,
    vmReasoning: suspend (String) -> ReasoningResponse?,
    onPickModel: (String) -> Unit,
    onPickEffort: (String) -> Unit,
    loadDraft: suspend (String) -> String,
    saveDraft: (String, String) -> Unit,
    consumePendingFirst: (String) -> dev.supermux.android.AppViewModel.PendingFirstMessage?,
    onOpenFile: (FilePathRef) -> Unit,
    onRequestRename: () -> Unit,
    onRequestMute: () -> Unit,
    onRequestKill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = rememberHaptics()

    // ── attachment state ──────────────────────────────────────────────────────
    // Each chip is tracked by a stable [id] (progress updates copy the object, so
    // object identity is not usable). `uploading` while in-flight; `failed` after
    // the resumable upload gave up (chip stays with a Retry affordance — never a
    // silent drop). `source` is kept so Retry can re-run the upload.
    data class PendingAttachment(
        val id: Long,
        val fileId: String,
        val name: String,
        val uploading: Boolean,
        val progress: Float = 0f,
        val failed: Boolean = false,
        val source: ChunkSource? = null,
        val mime: String = "",
    )
    val pendingAttachments = remember { mutableStateListOf<PendingAttachment>() }
    val attIdGen = remember { AtomicLong(0L) }

    fun updateAtt(id: Long, transform: (PendingAttachment) -> PendingAttachment) {
        val idx = pendingAttachments.indexOfFirst { it.id == id }
        if (idx >= 0) pendingAttachments[idx] = transform(pendingAttachments[idx])
    }

    // Name + byte size for a content Uri (DISPLAY_NAME/SIZE, falling back to the
    // fd's statSize). Size is required to chunk + show determinate progress.
    fun queryNameSize(uri: Uri): Pair<String, Long?> {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size == null) {
            size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { s -> s >= 0 } } }.getOrNull()
        }
        return name to size
    }

    // Run (or re-run, on Retry) the resumable upload for one staged attachment,
    // driving its progress + failed state. Bounded RAM: streams from the Uri.
    suspend fun uploadAtt(attId: Long, source: ChunkSource, name: String, mime: String) {
        withContext(Dispatchers.Main) { updateAtt(attId) { it.copy(uploading = true, failed = false, progress = 0f) } }
        val fileId = onUpload(source, name, mime, null) { sent, total ->
            val p = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
            scope.launch(Dispatchers.Main) { updateAtt(attId) { it.copy(progress = p) } }
        }
        withContext(Dispatchers.Main) {
            if (fileId != null) updateAtt(attId) { it.copy(fileId = fileId, uploading = false, failed = false, progress = 1f) }
            else updateAtt(attId) { it.copy(uploading = false, failed = true) }
        }
    }

    // Shared staging for ALL attachment sources (Photos / Files / Camera / paste).
    // Keeps the Uri as a streaming ChunkSource instead of reading the whole file
    // into RAM, then uploads it resumably with a progress chip.
    suspend fun stageFromUri(uri: Uri) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val (name, size) = queryNameSize(uri)
        if (size == null || size <= 0L) return
        val source = ContentResolverChunkSource(resolver, uri, size)
        val attId = attIdGen.incrementAndGet()
        withContext(Dispatchers.Main) {
            pendingAttachments.add(PendingAttachment(id = attId, fileId = "", name = name, uploading = true, source = source, mime = mime))
        }
        uploadAtt(attId, source, name, mime)
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

    // Camera video: system camera records into our FileProvider URI; CaptureVideo() returns
    // true on a successful capture, mirroring TakePicture() above. A separate URI state so a
    // photo capture in flight can't clobber a video capture's output target.
    var videoCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val captureVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo(),
    ) { ok: Boolean ->
        val uri = videoCaptureUri
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

    // ── onControl: composer-scoped control commands are handled here; session-control actions
    //    (rename / mute / kill) bubble up to ChatScreen via callbacks so its dialog state stays put ──
    val onControl: (SlashCommand) -> Unit = { cmd ->
        when (cmd.action?.kind) {
            "rename" -> onRequestRename()
            "mute" -> onRequestMute()
            "kill" -> onRequestKill()
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

    Column(modifier) {
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
                // First paint for this session: jump to the bottom instantly — opening a chat should
                // START at the bottom, not animate a fast scroll down. Only animate for content that
                // arrives while you're already watching.
                if (prevTimelineSize == 0) listState.scrollToItem(target)
                else listState.animateScrollToItem(target)
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
                    .padding(start = Space.sm, end = Space.md, top = Space.md, bottom = Space.md),
                verticalArrangement = Arrangement.spacedBy(0.dp),
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

            // Hardware-keyboard nav for the slash menu: ↑/↓ move the highlight, Enter picks, Esc
            // dismisses — so you can drive it without touch (DeX / attached keyboard).
            var selectedSlashIndex by remember { mutableIntStateOf(0) }
            var slashMenuDismissed by remember { mutableStateOf(false) }
            LaunchedEffect(slashQuery) { selectedSlashIndex = 0; slashMenuDismissed = false }
            val slashMenuOpen = slashMatches.isNotEmpty() && !slashMenuDismissed
            val safeSlashIndex = selectedSlashIndex.coerceIn(0, (slashMatches.size - 1).coerceAtLeast(0))

            // Replace the active "/token" with [insert], preserving any leading whitespace.
            fun replaceSlashToken(insert: String) {
                val m = slashTokenRegex.find(text) ?: run { text = insert; return }
                val lead = m.value.takeWhile { it == ' ' || it == '\n' || it == '\t' }
                text = text.substring(0, m.range.first) + lead + insert
            }
            // Apply a slash command — shared by a tap and by keyboard Enter.
            fun selectSlashCommand(cmd: SlashCommand) {
                haptic(HapticKind.Tick)
                val action = cmd.action
                if (action != null) {
                    replaceSlashToken("")
                    onControl(cmd)
                } else {
                    replaceSlashToken(cmd.insertText?.ifEmpty { null } ?: "${cmd.sigil}${cmd.name} ")
                }
            }

            if (slashMenuOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    slashMatches.forEachIndexed { i, cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_slash_item_${cmd.name}")
                                .background(
                                    if (i == safeSlashIndex) MaterialTheme.colorScheme.surfaceContainerHighest
                                    else Color.Transparent,
                                )
                                .clickable { selectSlashCommand(cmd) }
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
                    pendingAttachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (att.failed) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .then(
                                    att.source?.takeIf { att.failed }?.let { src ->
                                        Modifier.clickable { scope.launch { uploadAtt(att.id, src, att.name, att.mime) } }
                                    } ?: Modifier
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (att.uploading) {
                                CircularProgressIndicator(
                                    progress = { att.progress },
                                    modifier = Modifier.size(12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 1.5.dp,
                                )
                            }
                            Text(
                                text = if (att.failed) "${att.name} · Retry" else att.name,
                                color = if (att.failed) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                            if (!att.uploading) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_x),
                                    contentDescription = "Remove",
                                    tint = if (att.failed) MaterialTheme.colorScheme.onErrorContainer
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { pendingAttachments.removeAll { it.id == att.id } }
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
            // Block send while any attachment is still uploading OR has failed —
            // never send a message minus its attachment (the old silent drop).
            val anyBlocking = pendingAttachments.any { it.uploading || it.failed }
            val canSend = !anyBlocking && (text.isNotBlank() || pendingAttachments.isNotEmpty())
            fun doSend() {
                if (!canSend) return
                haptic(HapticKind.Confirm)
                val attachmentIds = pendingAttachments.map { it.fileId }
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
                            // Physical keyboard: Enter sends, Shift+Enter inserts a newline. Handled
                            // in the PREVIEW phase and consumed, so the multiline field never also
                            // inserts a newline and no duplicate IME "Send" fires — fixes hardware
                            // Enter not sending, and the newline double-send, on DeX/desktop keyboards.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) {
                                    false
                                } else when {
                                    // Slash menu open → arrows move the highlight, Enter picks, Esc closes.
                                    slashMenuOpen && e.key == Key.DirectionDown -> {
                                        selectedSlashIndex = (safeSlashIndex + 1).coerceAtMost(slashMatches.size - 1)
                                        true
                                    }
                                    slashMenuOpen && e.key == Key.DirectionUp -> {
                                        selectedSlashIndex = (safeSlashIndex - 1).coerceAtLeast(0)
                                        true
                                    }
                                    slashMenuOpen && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> {
                                        slashMatches.getOrNull(safeSlashIndex)?.let { selectSlashCommand(it) }
                                        true
                                    }
                                    slashMenuOpen && e.key == Key.Escape -> {
                                        slashMenuDismissed = true
                                        true
                                    }
                                    // Otherwise: Enter sends, Shift+Enter inserts a newline.
                                    (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> {
                                        doSend()
                                        true
                                    }
                                    else -> false
                                }
                            }
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
                            DropdownMenuItem(
                                text = { Text("Record video") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_play), null, modifier = Modifier.size(18.dp))
                                },
                                modifier = Modifier.testTag("attach_menu_record_video"),
                                onClick = {
                                    attachMenu = false
                                    val uri = createVideoUri(context)
                                    videoCaptureUri = uri
                                    captureVideo.launch(uri)
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
    val label = if (agent.detail == "running") "working" else "thinking"
    // Terminal-prompt status line: a gutter-aligned live pulse continues the spine's thread,
    // then a mono status + elapsed, then a compact stop. Reads as the prompt of a live session.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp)) {
            BreathingDot(cs.primary, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), size = 7.dp)
        }
        Text(
            text = label + (elapsed?.let { " · " + formatDuration(it) } ?: ""),
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            color = cs.primary,
        )
        Spacer(Modifier.width(Space.sm))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.sm))
                .clickable { haptic(HapticKind.Tick); onStop() }
                .testTag("working_stop")
                .padding(horizontal = Space.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_square),
                contentDescription = "Stop",
                tint = cs.error,
                modifier = Modifier.size(10.dp),
            )
            Text("stop", fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.error)
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
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp)) {
            BreathingDot(cs.primary, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), size = 7.dp)
        }
        Text(
            text = "sending",
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            color = cs.primary,
        )
        Spacer(Modifier.width(Space.sm))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.sm))
                .clickable { haptic(HapticKind.Tick); onStop() }
                .testTag("sending_stop")
                .padding(horizontal = Space.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_square),
                contentDescription = "Stop",
                tint = cs.error,
                modifier = Modifier.size(10.dp),
            )
            Text("stop", fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.error)
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

/**
 * Create a FileProvider URI for a fresh camera video capture in cacheDir/attachments (the same
 * path createImageUri + openAttachment already use, so no file_paths.xml change is needed). The
 * system camera app writes the MP4 here; stageFromUri then reads it back — contentResolver
 * .getType() maps the .mp4 extension to video/mp4 — and uploads it with kind=null so the broker
 * infers "video".
 */
private fun createVideoUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.mp4")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
