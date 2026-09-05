// The ONE chat composer (cluster D3) — a single soft rounded card holding the multiline draft, a
// chip row while attachments are staged, and a bottom toolbar: + attach · model pill · effort pill
// on the left; mic + send/stop on the right. Above the card sit the `/command` menu, the
// "Transcribing…" strip and (while dictating) the RecordingBar takeover.
//
// It is desktop's composer as the base — the live UploadState machine, the runSeq stale-callback
// guard, remember(sessionKey) scoping, the headless external hooks — widened with everything the
// Android composer had and desktop did not: the slash menu, camera photo/video capture, the
// recreation-stash `pendingPicks` collection, the pending-first message, the mic permission +
// live-transcript UI, and touch-shaped model/effort pickers.
//
// Where the two hosts genuinely differ, the branch is on INPUT MODE, never on "is this Android":
//   - Attach: a pointer host's `+` opens the file dialog directly (paste-image stays on Ctrl/Cmd+V
//     and the right-click menu, which is where a mouse user looks for it); a touch host's `+`
//     opens a menu with Paste / Photos / Files / Camera / Record video, the last two gated on
//     `caps.camera`.
//   - Pickers: `ComposerPill` + `DropdownMenu` under a pointer, Android's `PickerSheet` bottom
//     sheet under touch.
//   - Enter: `shouldComposerSendOnEnter(..., fromPhysicalKeyboard = LocalInputMode == Pointer)`, so
//     a soft-IME Return inserts a newline and a hardware Enter sends — desktop's old
//     `isComposerSendKey` is exactly the `fromPhysicalKeyboard = true` case of that.
//
// Unlike the launcher (which STAGES files pre-spawn and uploads them post-spawn), the chat composer
// uploads each chip IMMEDIATELY against the LIVE session — so a chip carries a live upload STATE
// (Uploading(pct) → Done(fileId) | Failed). Send is gated while any chip is still Uploading OR
// Failed, so a message is never sent minus its attachment.
package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.DEFAULT_MODEL_ID
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.effortSpeedometerParams
import dev.supermux.net.sortEffortLevelsLowToHigh
import dev.supermux.proto.SlashCommand
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.session.hasAgentLogo
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.widgets.Speedometer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Identifies this screen to `Platform.pickFiles`/`captureImage`, so a pick that outlives an
 *  activity recreation comes back HERE and not to the new-session launcher. */
const val COMPOSER_PICK_REQUESTER: String = "chat-composer"

/** Live upload state of one composer attachment chip. */
sealed interface UploadState {
    /** Upload in flight; [pct] is 0f..1f absolute progress (0 until the first callback). */
    data class Uploading(val pct: Float) : UploadState
    /** Finalized on the broker — [fileId] is what a send passes in `attachments`. */
    data class Done(val fileId: String) : UploadState
    /** The resumable upload gave up — the chip stays with a Retry affordance (never a silent drop). */
    data object Failed : UploadState
}

/**
 * One staged attachment in the chat composer. Tracked by a stable [id] (progress copies the object,
 * so object identity is not usable). [source] is kept so Retry can re-run the upload. [runSeq] is
 * the identity of the *current* upload run for this chip: Retry bumps it, and a progress/terminal
 * callback only applies while it still matches — so a late callback from a superseded run (or from a
 * run whose chip was removed) is dropped, never resurrecting or clobbering a chip.
 */
data class ComposerAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val source: ChunkSource,
    val state: UploadState,
    val kind: String? = null,
    val runSeq: Long = 0L,
)

/**
 * One-shot "attach this file then send" request, delivered from outside the composer's own
 * click-driven state (the off-by-default `SM_CHAT_ATTACH` headless hook → `ShellUiState` →
 * `ChatPanel`). Drives the SAME stage/upload/send funnel the attach affordance and Send use.
 *
 * [file] is null when the host could not resolve the request into a file at all (a path that is not
 * a file); the composer then consumes the request without staging anything.
 */
data class ComposerExternalAttach(val file: PickedFile?, val text: String)

/** One-shot "transcribe these bytes and append the cleaned text to the draft" request — the
 *  `SM_DICTATE` headless hook, which proves the real POST→append round-trip under Xvfb where there
 *  is no mic. [bytes] null = the host could not read the audio; consume without appending. */
data class ComposerExternalDictate(val bytes: ByteArray?, val filename: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComposerExternalDictate) return false
        return filename == other.filename && (bytes?.contentEquals(other.bytes ?: ByteArray(0)) ?: (other.bytes == null))
    }

    override fun hashCode(): Int = (bytes?.contentHashCode() ?: 0) * 31 + filename.hashCode()
}

/**
 * Everything the composer needs from the host beyond the four hot callbacks it takes directly.
 *
 * Android's lambda shape, so a host that has no `HostStore` in scope (Android's `ChatPanel` gets a
 * bag of callbacks from its screen) builds one by hand; desktop builds it from the store with
 * [rememberComposerActions]. Every member has a "does nothing" default, so a composer under test
 * only wires the seam it is exercising.
 */
class ComposerActions(
    /** Draft restored per session on open (DataStore on Android, the in-memory map on desktop). */
    val loadDraft: (suspend (sessionId: String) -> String)? = null,
    /** Debounced per-session draft persistence. */
    val saveDraft: ((sessionId: String, text: String) -> Unit)? = null,
    /** The first message a just-spawned session was launched with, consumed exactly once. */
    val consumePendingFirst: ((sessionId: String) -> Pair<String, List<String>>?)? = null,
    /** Broker cleanup pass over an on-device transcript (the live-transcript path). */
    val transcribeDraft: suspend (String) -> String? = { null },
    /** Project/agent names biasing on-device recognition. */
    val loadGlossary: suspend () -> List<String> = { emptyList() },
    /** Git ops for [ComposerFooter]; null hides the branch chip's menu (the label still renders). */
    val gitFetch: (suspend () -> dev.supermux.net.GitOpResult?)? = null,
    val gitPull: (suspend () -> dev.supermux.net.GitOpResult?)? = null,
    val gitPush: (suspend () -> dev.supermux.net.GitOpResult?)? = null,
    val gitPublish: (suspend () -> dev.supermux.net.GitOpResult?)? = null,
)

/**
 * [ComposerActions] wired to a [dev.supermux.state.HostStore] for one session — the ergonomic
 * builder for a host that already holds the store (desktop's `ChatPanel`). `:ui` depends on
 * `:shared`, so this belongs here rather than being re-typed in each app.
 */
@Composable
fun rememberComposerActions(
    app: dev.supermux.state.HostStore,
    sessionId: String,
): ComposerActions = remember(app, sessionId) {
    ComposerActions(
        transcribeDraft = { draft -> app.transcribeDraft(sessionId, draft)?.text },
        loadGlossary = { app.fetchGlossary().orEmpty() },
        consumePendingFirst = { id -> app.consumePendingFirst(id)?.let { it.text to it.attachments } },
        gitFetch = { app.gitFetch(sessionId) },
        gitPull = { app.gitPull(sessionId) },
        gitPush = { app.gitPush(sessionId) },
        gitPublish = { app.gitPublish(sessionId) },
    )
}

/** Kind guess from a MIME: audio → "voice", else null (broker infers). Mirrors the launcher. */
internal fun composerKind(mime: String): String? =
    if (mime.startsWith("audio")) "voice" else null

/**
 * Pure Ctrl/Cmd+V paste-key predicate: `true` only for a KeyDown V with Ctrl OR Meta held
 * (Windows/Linux vs macOS) and Shift **not** held. Ctrl and Meta are **separate** flags so tests can
 * prove each modifier path distinctly. Ctrl/Cmd+Shift+V is the conventional "paste as plain text"
 * chord and must fall through to the text field.
 */
internal fun isComposerPasteKey(
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    shiftPressed: Boolean = false,
): Boolean =
    type == KeyEventType.KeyDown &&
        key == Key.V &&
        (ctrlPressed || metaPressed) &&
        !shiftPressed

/**
 * Whether a paste-image gesture should consume the key and stage: the upload seam must be bound
 * (text-only composers ignore paste-image) AND the clipboard seam returned at least one image.
 */
internal fun shouldStageClipboardPaste(uploadBound: Boolean, files: List<PickedFile>): Boolean =
    uploadBound && files.isNotEmpty()

/**
 * Production paste-key handler decision used by the composer's `onPreviewKeyEvent`. Returns true
 * when the event should be **consumed** for paste-image (and [onPasteImage] is invoked); false when
 * the key must fall through to the text field (text paste / non-paste keys).
 */
internal fun handleComposerPasteKey(
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    shiftPressed: Boolean,
    uploadBound: Boolean,
    /** Lazily evaluated so the clipboard is only probed after the paste-key chord matches. */
    likelyHasImage: () -> Boolean,
    onPasteImage: () -> Unit,
): Boolean {
    if (!isComposerPasteKey(key, type, ctrlPressed, metaPressed, shiftPressed)) return false
    if (uploadBound && likelyHasImage()) {
        onPasteImage()
        return true
    }
    return false
}

/**
 * Send-gating predicate: something to send (text OR at least one attachment) AND no chip is still
 * Uploading or Failed AND not already sending. The Uploading/Failed block is the load-bearing
 * correctness bit — never send a message minus its attachment.
 */
internal fun canSendComposer(
    text: String,
    attachments: List<ComposerAttachment>,
    sending: Boolean,
): Boolean =
    (text.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.none { it.state is UploadState.Uploading || it.state is UploadState.Failed } &&
        !sending

/**
 * Display label for the composer's model pill: the [ModelInfo.displayName] of the current model id,
 * the raw id when it isn't in the catalog, or "Default" when the session has no explicit model.
 */
internal fun composerModelLabel(current: String?, models: List<ModelInfo>): String {
    val id = current?.takeIf { it.isNotBlank() } ?: return "Default"
    return models.firstOrNull { it.id == id }?.displayName ?: id
}

/** The picker-option id that matches [current] (so it gets the check): the raw model id, or the
 *  [DEFAULT_MODEL_ID] sentinel when the session has no explicit model (null/blank). */
internal fun composerModelSelectedId(current: String?): String =
    current?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID

/**
 * Display label for the composer's effort pill. Always the short wire id (`low` / `medium` /
 * `high` / `xhigh` / `max` / …) — never the long broker [ReasoningLevel.description].
 */
internal fun composerReasoningLabel(reasoning: ReasoningResponse): String {
    val current = reasoning.current?.takeIf { it.isNotBlank() } ?: return "effort"
    // Prefer catalog id when present (canonical casing); else the raw current value.
    return reasoning.levels.firstOrNull { it.id == current }?.id ?: current
}

/** Short effort label for a single level — always [ReasoningLevel.id], never the long description. */
internal fun composerReasoningLevelLabel(level: ReasoningLevel): String = level.id

/**
 * The chat composer.
 *
 * @param draft current draft text (hoisted — per-session in the host's `ChatPanel`).
 * @param sending true while the client-local "Sending…" marker is up (blocks re-send).
 * @param agentWorking true while the broker says the agent is busy — flips the trailing icon to
 *   Stop so the user can interrupt without leaving the composer.
 * @param onSend fired with the TRIMMED draft + the finalized attachment file_ids (gated so all
 *   staged chips are Done). The composer clears its own chips on send; the caller clears the draft.
 * @param onUpload the upload seam. When null, the attach affordance is hidden (text-only composer).
 * @param onTranscribeAudio the dictation transcribe seam. When null the mic button is hidden
 *   entirely (mirrors [onUpload]'s null-hides-attach rule).
 * @param commands the session's `/command` catalog; [onControl] fires for a picked control command
 *   (one with an `action`), everything else inserts its text into the draft.
 */
@Composable
fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    agentWorking: Boolean,
    onSend: (String, List<String>) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
    sessionKey: String = "",
    actions: ComposerActions = ComposerActions(),
    onUpload: (suspend (
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String?,
        onProgress: (Long, Long) -> Unit,
    ) -> String?)? = null,
    onTranscribeAudio: (suspend (bytes: ByteArray, filename: String) -> String?)? = null,
    commands: List<SlashCommand> = emptyList(),
    commandsResolved: Boolean = true,
    onControl: (SlashCommand) -> Unit = {},
    placeholder: String = "Message the agent, tag @files, or use /commands and /skills",
    externalAttach: ComposerExternalAttach? = null,
    onExternalAttachConsumed: () -> Unit = {},
    externalDictate: ComposerExternalDictate? = null,
    onExternalDictateConsumed: () -> Unit = {},
    models: ModelsResponse? = null,
    reasoning: ReasoningResponse? = null,
    sessionModel: String? = null,
    sessionAgent: String? = null,
    onPickModel: (String) -> Unit = {},
    onPickReasoning: (String) -> Unit = {},
    /**
     * One-shot paste-image request from the native Edit ▸ Paste image menu (or tests). When the
     * nonce changes to a non-zero value, runs the same paste path as Ctrl/Cmd+V, then calls
     * [onPasteImageRequestConsumed].
     */
    pasteImageRequestNonce: Long = 0L,
    onPasteImageRequestConsumed: () -> Unit = {},
    /**
     * Opens the model picker from outside the pill — the `/model` control command, which the host
     * routes back here rather than owning a second picker of its own. Any change to a non-zero
     * value opens it once.
     */
    openModelPickerNonce: Long = 0L,
) {
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val physicalKeyboard = LocalInputMode.current == InputMode.Pointer
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()

    // Attachment state is SCOPED to [sessionKey]: the panel deliberately stays composed across
    // session switches, so a bare remember{} would leak session A's uploaded chips into session B
    // and gather A's file_ids into B's send.
    val attachments = remember(sessionKey) { mutableStateListOf<ComposerAttachment>() }
    // Plain-var counters (single Main-thread dispatcher — no atomics needed); one holder per session.
    val ids = remember(sessionKey) { object { var nextId = 0L; var nextSeq = 0L } }

    // Guarded update: apply only when the chip STILL exists AND belongs to the run identified by
    // [seq]. A late callback from a removed chip or a superseded run (e.g. after Retry) is dropped.
    fun updateAtt(id: String, seq: Long, transform: (ComposerAttachment) -> ComposerAttachment) {
        val idx = attachments.indexOfFirst { it.id == id }
        if (idx >= 0 && attachments[idx].runSeq == seq) attachments[idx] = transform(attachments[idx])
    }

    // Start (or restart, on Retry) the resumable upload for one chip, driving Uploading(pct) →
    // Done(fileId) | Failed. Each run gets a fresh [seq] so an older run's callbacks can't win.
    fun launchUpload(id: String) {
        val up = onUpload ?: return
        val idx = attachments.indexOfFirst { it.id == id }
        if (idx < 0) return
        val seq = ++ids.nextSeq
        val att = attachments[idx].copy(state = UploadState.Uploading(0f), runSeq = seq)
        attachments[idx] = att
        scope.launch {
            val fileId = up(att.source, att.name, att.mime, att.kind) { sent, total ->
                val pct = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
                // Progress may arrive off the Main thread; marshal the state write back onto the
                // composer scope's dispatcher. Guard against a TERMINAL clobber: the upload fires a
                // final onProgress(total,total) right before returning, and that marshaled write is
                // QUEUED — it lands AFTER the synchronous Done/Failed write below. Only apply while
                // the chip is still Uploading, so the queued final progress can't resurrect a
                // settled chip to Uploading(1.0) (a stuck dead-end: Uploading blocks send AND hides
                // the × remove).
                scope.launch {
                    updateAtt(id, seq) {
                        if (it.state is UploadState.Uploading) it.copy(state = UploadState.Uploading(pct)) else it
                    }
                }
            }
            updateAtt(id, seq) {
                if (fileId != null) it.copy(state = UploadState.Done(fileId))
                else it.copy(state = UploadState.Failed)
            }
        }
    }

    /** Stage ONE picked/captured/pasted/dropped file — the single funnel every source shares, so a
     *  dropped file gets an identical chip + upload + progress to a dialog-picked one. */
    fun stage(picked: PickedFile): String {
        val id = (++ids.nextId).toString()
        attachments.add(
            ComposerAttachment(
                id = id,
                name = picked.name,
                mime = picked.mime,
                source = picked.source,
                state = UploadState.Uploading(0f),
                kind = composerKind(picked.mime),
            ),
        )
        launchUpload(id)
        return id
    }

    fun stageFiles(files: List<PickedFile>) {
        files.forEach { stage(it) }
    }

    /** Drop a chip. Host-owned staging (desktop's paste cache) is reclaimed by age, never by path. */
    fun removeAttachment(id: String) {
        val idx = attachments.indexOfFirst { it.id == id }
        if (idx < 0) return
        attachments.removeAt(idx)
    }

    // A pick/capture the user started before an activity recreation (rotation while the system
    // picker was in the foreground) finishes with no coroutine left to await it. Collected for the
    // whole lifetime of the composer — a one-shot read would race the delivery — and tagged with
    // this screen's id so the launcher screen never steals it.
    LaunchedEffect(platform, sessionKey) {
        platform.pendingPicks(COMPOSER_PICK_REQUESTER).collect { stage(it) }
    }

    // ── per-session draft persistence (survives switch + process death) ──────────────────
    // Load once per session; `draftLoaded` gates the save effect so the initial restore (or an
    // empty load) never clobbers a draft before it is read back.
    val loadDraft = actions.loadDraft
    val saveDraft = actions.saveDraft
    var draftLoaded by remember(sessionKey) { mutableStateOf(loadDraft == null) }
    if (loadDraft != null) {
        LaunchedEffect(sessionKey) {
            draftLoaded = false
            onDraftChange(loadDraft(sessionKey))
            draftLoaded = true
        }
    }
    if (saveDraft != null) {
        // Debounced (~400ms) — avoids a persistence write per keystroke. Clearing on send writes
        // the empty draft through this same effect.
        LaunchedEffect(sessionKey, draft, draftLoaded) {
            if (!draftLoaded) return@LaunchedEffect
            delay(400)
            saveDraft(sessionKey, draft)
        }
    }

    // The first message a just-spawned session was launched with — sent exactly once, through the
    // same send callback the Send button uses.
    val consumePendingFirst = actions.consumePendingFirst
    if (consumePendingFirst != null) {
        LaunchedEffect(sessionKey) {
            val pending = consumePendingFirst(sessionKey) ?: return@LaunchedEffect
            onSend(pending.first, pending.second)
        }
    }

    // True while the clipboard image decode is running — shows a pending chip so the encode is not
    // silent (the chip for the real file appears only after decode+stage returns).
    var pastePending by remember(sessionKey) { mutableStateOf(false) }

    /** Read clipboard images off the UI thread, then stage them. Used by Ctrl/Cmd+V, the
     *  Paste-image menu entries, and the nonce hook — all one funnel. */
    fun launchPasteImages() {
        if (onUpload == null) return
        if (pastePending) return
        pastePending = true
        scope.launch {
            try {
                // OFF the UI thread: a raster paste re-encodes a screenshot to PNG, which is ~1s.
                val files = withContext(Dispatchers.Default) { platform.clipboard.readImages() }
                if (shouldStageClipboardPaste(uploadBound = true, files = files)) stageFiles(files)
            } finally {
                pastePending = false
            }
        }
    }

    // Edit ▸ Paste image / ShellUiState.pasteImageRequestNonce — same funnel as Ctrl/Cmd+V.
    LaunchedEffect(pasteImageRequestNonce) {
        if (pasteImageRequestNonce > 0L) {
            launchPasteImages()
            onPasteImageRequestConsumed()
        }
    }

    val canSend = canSendComposer(draft, attachments, sending)

    // Gather-and-send for an ARBITRARY [text] (not just the hoisted [draft]) — same gating +
    // file_id gather + chip clear the Send button/Enter key use. Parameterized so [externalAttach]
    // can send its own text without racing the hoisted draft's recomposition.
    fun sendWith(text: String) {
        if (canSendComposer(text, attachments, sending)) {
            val fileIds = attachments.mapNotNull { (it.state as? UploadState.Done)?.fileId }
            onSend(text.trim(), fileIds)
            attachments.clear()
        }
    }
    val doSend = {
        if (canSend) haptic.perform(HapticKind.Confirm)
        sendWith(draft)
    }

    val dictation = rememberDictation(
        resetKey = sessionKey,
        loadGlossary = actions.loadGlossary,
        transcribeDraft = actions.transcribeDraft,
        transcribeAudio = { bytes, name -> onTranscribeAudio?.invoke(bytes, name) },
        onAppend = { cleaned -> onDraftChange(draft + (if (draft.isBlank()) "" else " ") + cleaned) },
    )

    // SM_DICTATE headless hook: feed bytes already on disk through the SAME transcribe seam the mic
    // button uses — no MicCapture involved at all, since there is no mic under Xvfb.
    LaunchedEffect(externalDictate) {
        val request = externalDictate ?: return@LaunchedEffect
        val bytes = request.bytes
        if (onTranscribeAudio != null && bytes != null) {
            val cleaned = onTranscribeAudio.invoke(bytes, request.filename)?.trim()
            if (!cleaned.isNullOrEmpty()) {
                onDraftChange(draft + (if (draft.isBlank()) "" else " ") + cleaned)
            }
        }
        onExternalDictateConsumed()
    }

    // SM_CHAT_ATTACH headless hook: stage the requested file through the SAME funnel the attach
    // affordance and the drop target use, poll (the upload seam has no completion callback to
    // suspend on) until that chip reaches a TERMINAL state, then — on success — sendWith the
    // requested text. Deliberately NOT onDraftChange+doSend(): `draft` is hoisted OUTSIDE this
    // composable, so writing it here and immediately calling the stale-closure doSend would race
    // the recomposition that updates it.
    LaunchedEffect(externalAttach) {
        val request = externalAttach ?: return@LaunchedEffect
        val file = request.file
        if (onUpload == null || file == null) {
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        val newId = stage(file)
        // Poll (200ms) for the new chip to leave Uploading — up to 60s (a resumable chunk loop, not
        // a single request; generous so a slow/large file doesn't false-time-out).
        var waited = 0L
        var current = attachments.firstOrNull { it.id == newId }
        while (current != null && current.state is UploadState.Uploading && waited < 60_000) {
            delay(200)
            waited += 200
            current = attachments.firstOrNull { it.id == newId }
        }
        if (current?.state is UploadState.Done) sendWith(request.text)
        onExternalAttachConsumed()
    }

    // ── slash-command menu: the active "/token" at the end of the draft (start-of-line or after
    //    whitespace), filtering on name OR family, capped at 8. Matching lives in SlashCommands.kt,
    //    shared with the New Session launcher. ──
    val slashQuery = activeSlashQuery(draft)
    val slashMatches = slashCommandMatches(draft, commands)
    var selectedSlashIndex by remember { mutableIntStateOf(0) }
    var slashMenuDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(slashQuery) { selectedSlashIndex = 0; slashMenuDismissed = false }
    val slashMenuOpen = slashMatches.isNotEmpty() && !slashMenuDismissed
    val safeSlashIndex = selectedSlashIndex.coerceIn(0, (slashMatches.size - 1).coerceAtLeast(0))

    // Apply a slash command — shared by a tap and by keyboard Enter. Control commands clear the
    // token and fire onControl; everything else inserts its text.
    fun selectSlashCommand(cmd: SlashCommand) {
        haptic.perform(HapticKind.Tick)
        if (cmd.action != null) {
            onDraftChange(replaceSlashToken(draft, ""))
            onControl(cmd)
        } else {
            onDraftChange(replaceSlashToken(draft, slashInsertText(cmd)))
        }
    }

    // ── pickers ─────────────────────────────────────────────────────────────────────────
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    LaunchedEffect(openModelPickerNonce) { if (openModelPickerNonce > 0L) modelMenu = true }
    val modelCurrent = models?.current ?: sessionModel
    val showModelPill = models != null || !sessionModel.isNullOrBlank()
    val r = reasoning
    val showReasoningPill = r != null && r.visible && r.levels.size > 1

    val cs = MaterialTheme.colorScheme
    val inputInteraction = remember { MutableInteractionSource() }
    val inputFocused by inputInteraction.collectIsFocusedAsState()
    var dragOver by remember(sessionKey) { mutableStateOf(false) }

    val cardShape = RoundedCornerShape(Radii.lg + 8.dp) // ~24dp — matches the mock capsule
    val cardBorder = when {
        dragOver -> cs.primary
        inputFocused -> cs.outline.copy(alpha = 0.55f)
        else -> cs.outlineVariant.copy(alpha = 0.65f)
    }
    val cardBorderWidth = if (dragOver) 2.dp else 1.dp

    Column(
        modifier
            .fillMaxWidth()
            .externalFileDropTarget(
                enabled = onUpload != null,
                onDragOver = { dragOver = it },
                onFiles = { stageFiles(it) },
            ),
    ) {
        if (slashMenuOpen) {
            SlashMenu(
                matches = slashMatches,
                selectedIndex = safeSlashIndex,
                onSelect = { selectSlashCommand(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer),
                testTagPrefix = "chat_slash_item_",
                showActionGlyph = true,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        } else if (slashQuery != null && !commandsResolved) {
            // A fresh session whose command set hasn't resolved yet.
            Text(
                text = "Loading commands…",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("chat_slash_loading"),
            )
        }

        dictation.banner?.let { msg ->
            Text(
                msg,
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("composer_banner"),
            )
        }
        if (dictation.transcribing) TranscribingIndicator()

        if (dictation.active) {
            // Recording takes the composer over entirely (iOS/Android parity).
            RecordingBar(
                seconds = dictation.recordingSeconds,
                liveTranscript = dictation.liveTranscript.orEmpty(),
                onStop = { dictation.stopMic() },
                onCancel = { dictation.cancelMic() },
            )
        } else ComposerContextMenu(
            pasteEnabled = onUpload != null,
            onPasteImage = { launchPasteImages() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(cs.surfaceContainerHigh.copy(alpha = 0.72f))
                    .border(cardBorderWidth, cardBorder, cardShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("composer-card"),
            ) {
                if (attachments.isNotEmpty() || pastePending) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (pastePending) PastePendingChip()
                        attachments.forEach { att ->
                            key(att.id) {
                                ComposerChip(
                                    att = att,
                                    onRemove = { removeAttachment(att.id) },
                                    onRetry = { launchUpload(att.id) },
                                )
                            }
                        }
                    }
                }

                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp, max = 160.dp)
                        .testTag("composer-input")
                        .onPreviewKeyEvent { e: KeyEvent ->
                            when {
                                // Slash menu open → arrows move the highlight, Enter picks, Esc closes.
                                slashMenuOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> {
                                    selectedSlashIndex = (safeSlashIndex + 1).coerceAtMost(slashMatches.size - 1)
                                    true
                                }
                                slashMenuOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> {
                                    selectedSlashIndex = (safeSlashIndex - 1).coerceAtLeast(0)
                                    true
                                }
                                // Enter picks a slash command — soft OR hardware, since the menu is
                                // on screen and picking is what Enter obviously means there.
                                slashMenuOpen && e.isComposerEnterKey() && !e.isShiftPressed -> {
                                    slashMatches.getOrNull(safeSlashIndex)?.let { selectSlashCommand(it) }
                                    true
                                }
                                slashMenuOpen && e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                    slashMenuDismissed = true
                                    true
                                }
                                shouldComposerSendOnEnter(
                                    isEnterKey = e.isComposerEnterKey(),
                                    shiftPressed = e.isShiftPressed,
                                    fromPhysicalKeyboard = physicalKeyboard,
                                ) -> {
                                    // Consume ONLY when we actually send; a blank/sending/upload-blocked
                                    // draft falls through so the multiline field handles Enter itself.
                                    if (canSend) {
                                        doSend()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                handleComposerPasteKey(
                                    key = e.key,
                                    type = e.type,
                                    ctrlPressed = e.isCtrlPressed,
                                    metaPressed = e.isMetaPressed,
                                    shiftPressed = e.isShiftPressed,
                                    uploadBound = onUpload != null,
                                    // Probe the clipboard ONLY after the paste chord matches — not
                                    // on every keystroke (cross-process selection can stall).
                                    likelyHasImage = { platform.clipboard.hasImage() },
                                    onPasteImage = { launchPasteImages() },
                                ) -> true
                                else -> false
                            }
                        },
                    textStyle = TextStyle(
                        color = cs.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    maxLines = 8,
                    interactionSource = inputInteraction,
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = cs.onSurfaceVariant.copy(alpha = 0.72f),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    maxLines = 2,
                                )
                            }
                            inner()
                        }
                    },
                )

                // Bottom toolbar — + · model · effort on the left; mic · send on the right.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (onUpload != null) {
                            AttachControl(
                                pointer = pointer,
                                camera = platform.caps.camera,
                                clipboardHasImage = {
                                    platform.caps.clipboardImages && platform.clipboard.hasImage()
                                },
                                onPickFiles = { kind ->
                                    scope.launch {
                                        stageFiles(platform.pickFiles(kind, COMPOSER_PICK_REQUESTER))
                                    }
                                },
                                onCaptureImage = {
                                    scope.launch {
                                        platform.captureImage(COMPOSER_PICK_REQUESTER)?.let { stage(it) }
                                    }
                                },
                                onCaptureVideo = {
                                    scope.launch {
                                        platform.captureVideo(COMPOSER_PICK_REQUESTER)?.let { stage(it) }
                                    }
                                },
                                onPasteImage = { launchPasteImages() },
                            )
                        }
                        if (showModelPill) {
                            val modelOptions = listOf(DEFAULT_MODEL_ID to "Default") +
                                (models?.models?.map { it.id to it.displayName } ?: emptyList())
                            Box(Modifier.testTag("composer-model-picker")) {
                                ComposerPill(
                                    label = composerModelLabel(modelCurrent, models?.models ?: emptyList()),
                                    testTag = "composer-model-pill",
                                    onClick = { modelMenu = true },
                                    leadingIcon = {
                                        if (sessionAgent != null && hasAgentLogo(sessionAgent)) {
                                            AgentLogo(sessionAgent, size = 12.dp)
                                        } else {
                                            Icon(
                                                Icons.Filled.AutoAwesome,
                                                contentDescription = null,
                                                tint = cs.onSurfaceVariant,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    },
                                )
                                if (pointer) {
                                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                        val selectedId = composerModelSelectedId(modelCurrent)
                                        modelOptions.forEach { (id, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                trailingIcon = {
                                                    if (id == selectedId) {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            null,
                                                            Modifier.size(16.dp),
                                                            tint = cs.primary,
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.testTag("composer-model-$id"),
                                                onClick = {
                                                    modelMenu = false
                                                    onPickModel(if (id == DEFAULT_MODEL_ID) "" else id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (!pointer && modelMenu) {
                                PickerSheet(
                                    title = "Select Model",
                                    options = modelOptions,
                                    current = composerModelSelectedId(modelCurrent),
                                    onPick = { onPickModel(if (it == DEFAULT_MODEL_ID) "" else it) },
                                    onDismiss = { modelMenu = false },
                                )
                            }
                        }
                        if (r != null && showReasoningPill) {
                            // Always low→high for menu + gauge (broker order is not trusted).
                            val effortLevels = sortEffortLevelsLowToHigh(r.levels)
                            val (gaugeLevels, gaugeValue) = effortSpeedometerParams(
                                current = r.current,
                                levels = r.levels,
                            )
                            Box(Modifier.testTag("composer-reasoning-picker")) {
                                ComposerPill(
                                    label = composerReasoningLabel(r),
                                    testTag = "composer-reasoning-pill",
                                    onClick = { reasoningMenu = true },
                                    leadingIcon = {
                                        Speedometer(
                                            levels = gaugeLevels,
                                            value = gaugeValue,
                                            tint = cs.onSurfaceVariant,
                                            activeTint = cs.primary,
                                            iconSize = 14.dp,
                                            testTag = "composer-effort-gauge",
                                        )
                                    },
                                )
                                if (pointer) {
                                    DropdownMenu(
                                        expanded = reasoningMenu,
                                        onDismissRequest = { reasoningMenu = false },
                                    ) {
                                        effortLevels.forEach { level ->
                                            DropdownMenuItem(
                                                text = { Text(composerReasoningLevelLabel(level)) },
                                                trailingIcon = {
                                                    if (level.id == r.current) {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            null,
                                                            Modifier.size(16.dp),
                                                            tint = cs.primary,
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.testTag("composer-reasoning-${level.id}"),
                                                onClick = {
                                                    reasoningMenu = false
                                                    onPickReasoning(level.id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (!pointer && reasoningMenu) {
                                PickerSheet(
                                    title = "Select Effort Level",
                                    options = effortLevels.map { it.id to (it.description ?: it.id) },
                                    current = r.current,
                                    onPick = { onPickReasoning(it) },
                                    onDismiss = { reasoningMenu = false },
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onTranscribeAudio != null) {
                            MicButton(
                                recording = dictation.recording,
                                transcribing = dictation.transcribing,
                                micUnavailable = dictation.micUnavailable,
                                onClick = { dictation.onMicClick() },
                                modifier = Modifier.testTag("composer-mic"),
                            )
                        }
                        if (agentWorking) {
                            IconButton(
                                onClick = onInterrupt,
                                modifier = Modifier.size(32.dp).testTag("composer-stop"),
                            ) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(cs.error),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Stop,
                                        contentDescription = "Stop",
                                        tint = cs.onError,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = doSend,
                                enabled = canSend,
                                modifier = Modifier.size(32.dp).testTag("composer-send"),
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (canSend) cs.primary else cs.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        dictation.errorMessage?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("composer-mic-error"),
            )
        }
    }

    if (dictation.micDenied) MicDeniedDialog(onDismiss = { dictation.micDenied = false })
}

/**
 * The `+` affordance.
 *
 * Under a POINTER it opens the file dialog directly — paste-image is on Ctrl/Cmd+V and the
 * right-click menu, where a mouse user looks for it, and desktop has no camera. Under TOUCH it
 * opens the menu Android has always had: Paste (only when the clipboard actually holds an image),
 * Photos, Files, and — gated on `caps.camera` — Camera and Record video.
 */
@Composable
private fun AttachControl(
    pointer: Boolean,
    camera: Boolean,
    clipboardHasImage: () -> Boolean,
    onPickFiles: (PickKind) -> Unit,
    onCaptureImage: () -> Unit,
    onCaptureVideo: () -> Unit,
    onPasteImage: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (pointer) onPickFiles(PickKind.Any) else menu = true },
            modifier = Modifier.size(32.dp).testTag("composer-attach"),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Attach",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (!pointer) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (clipboardHasImage()) {
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("attach_menu_paste"),
                        onClick = { menu = false; onPasteImage() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Photos") },
                    leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("attach_menu_photos"),
                    onClick = { menu = false; onPickFiles(PickKind.Media) },
                )
                DropdownMenuItem(
                    text = { Text("Files") },
                    leadingIcon = { Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("attach_menu_files"),
                    onClick = { menu = false; onPickFiles(PickKind.Any) },
                )
                if (camera) {
                    DropdownMenuItem(
                        text = { Text("Camera") },
                        leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("attach_menu_camera"),
                        onClick = { menu = false; onCaptureImage() },
                    )
                    DropdownMenuItem(
                        text = { Text("Record video") },
                        leadingIcon = { Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("attach_menu_record_video"),
                        onClick = { menu = false; onCaptureVideo() },
                    )
                }
            }
        }
    }
}

/** Indeterminate chip shown while the clipboard image decode runs (before a real upload chip). */
@Composable
private fun PastePendingChip() {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("composer-paste-pending"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            color = cs.primary,
            strokeWidth = 1.5.dp,
        )
        Text(text = "Pasting image…", color = cs.onSurface, fontSize = 12.sp, maxLines = 1)
    }
}

/** One staged-attachment chip: an in-flight determinate spinner + name (+ %) while Uploading, a
 *  "· Retry" affordance (whole chip clickable, error-tinted) on Failed, and an × remove once the
 *  upload is settled (Done/Failed — an in-flight upload has no ×). */
@Composable
private fun ComposerChip(
    att: ComposerAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val state = att.state
    val failed = state is UploadState.Failed
    val uploading = state is UploadState.Uploading

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (failed) cs.errorContainer else cs.surfaceContainerHigh)
            .then(
                if (failed) Modifier.clickable { onRetry() }.testTag("composer-chip-retry")
                else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("composer-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state is UploadState.Uploading) {
            CircularProgressIndicator(
                progress = { state.pct },
                modifier = Modifier.size(12.dp),
                color = cs.primary,
                strokeWidth = 1.5.dp,
            )
        }
        val label = when (state) {
            is UploadState.Uploading -> "${att.name} · ${(state.pct * 100).roundToInt()}%"
            is UploadState.Failed -> "${att.name} · Retry"
            is UploadState.Done -> att.name
        }
        Text(
            text = label,
            color = if (failed) cs.onErrorContainer else cs.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (!uploading) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = if (failed) cs.onErrorContainer else cs.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove() }
                    .testTag("composer-chip-remove"),
            )
        }
    }
}

/** Compact text-style pill (optional leading icon + label + chevron) — the model / effort chips
 *  inside the composer card. Borderless + muted ink so they sit as chrome on the soft card. */
@Composable
private fun ComposerPill(
    label: String,
    testTag: String,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(Radii.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leadingIcon?.invoke()
        Text(text = label.take(22), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
    }
}
