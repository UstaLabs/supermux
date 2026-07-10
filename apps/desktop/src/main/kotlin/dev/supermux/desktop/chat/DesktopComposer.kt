// The desktop chat composer — a keyboard-first input with attachment chips (M4d). One
// OutlinedTextField + a leading Attach icon + a trailing Send/Stop icon, with a chip row above the
// field for staged uploads. Enter sends, Shift+Enter inserts a newline — the same preview-phase key
// handling as OnboardingScreen.submitOnEnter, so hardware Enter never also drops a newline and a
// blank/sending/upload-blocked draft lets the field keep the key.
//
// Unlike the launcher (which STAGES files pre-spawn and uploads them post-spawn), the chat composer
// uploads each chip IMMEDIATELY against the LIVE session — so a chip carries a live upload STATE
// (Uploading(pct) → Done(fileId) | Failed), not a launcher-style StagedUpload. Send is gated while
// any chip is still Uploading OR Failed (the "any upload failure blocks the send" rule, ported from
// Android's ChatPanel composer) so a message is never sent minus its attachment.
//
// M4d-T2 adds external-file drag-and-drop via `androidx.compose.foundation.draganddrop.dragAndDropTarget`
// (compose-multiplatform 1.11.1). The Modifier itself is STABLE; the payload type it hands back —
// `androidx.compose.ui.draganddrop.DragData` / the `DragAndDropEvent.dragData()` accessor — is marked
// `@ExperimentalComposeUiApi` in this release (confirmed by decompiling the shipped jars: no marker on
// `dragAndDropTarget`, but `DragData` and `dragData()` both carry it), hence the file-level `@OptIn`
// below. Compose Desktop surfaces an OS file drop as `DragData.FilesList` (java.awt's
// DataFlavor.javaFileListFlavor under the hood); each entry is a `file:` URI string, converted back to
// a File and funneled through the SAME `stageFiles` path the Attach dialog uses, so a dropped file
// gets an identical ComposerAttachment + upload + progress.
package dev.supermux.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.upload.FileChunkSource
import dev.supermux.net.ChunkSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.math.roundToInt

/**
 * Pure Enter-key predicate for the composer: `true` only for a KeyDown Enter / NumPad-Enter with
 * Shift NOT held — i.e. the "send" chord. Shift+Enter (newline), key-up, and every other key are
 * `false`. Extracted from [DesktopComposer]'s `onPreviewKeyEvent` so the send-on-Enter contract is
 * unit-testable as plain logic, independent of whether the desktop UI-test harness can inject key
 * events into a focused field.
 */
internal fun isComposerSendKey(key: Key, type: KeyEventType, shiftPressed: Boolean): Boolean =
    type == KeyEventType.KeyDown &&
        (key == Key.Enter || key == Key.NumPadEnter) &&
        !shiftPressed

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
 * run whose chip was removed) is dropped, never resurrecting or clobbering a chip (the M4c lesson).
 */
data class ComposerAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val source: FileChunkSource,
    val state: UploadState,
    val kind: String? = null,
    val runSeq: Long = 0L,
)

/**
 * One-shot "attach this file then send" request for [DesktopComposer] (M4d-T3), delivered from
 * outside the composer's own state (WorkspaceUiState.externalAttach → SessionDetail → ChatPanel).
 * Drives the SAME `stageFiles`/`sendWith` funnel the Attach dialog + Send button use — see
 * [DesktopComposer]'s `externalAttach` param KDoc. Set by the off-by-default `SM_CHAT_ATTACH`
 * headless hook in Main.kt so the attach→upload→send round-trip can be proven under Xvfb with no
 * pointer/keyboard input.
 */
data class ComposerExternalAttach(val filePath: String, val text: String)

/** One-shot "transcribe this WAV file and append its cleaned text to the draft" request for
 *  [DesktopComposer] (M5-1), delivered from outside the composer's own mic-click state
 *  (WorkspaceUiState.externalDictate -> SessionDetail -> ChatPanel), mirroring
 *  [ComposerExternalAttach]. Drives the SAME [DesktopComposer]'s `onTranscribeAudio` seam the mic
 *  button uses — only the TRIGGER differs (a file already on disk instead of a live TargetDataLine
 *  capture) — so it proves the real POST->append round-trip under Xvfb, where there is no real mic.
 *  Set by the off-by-default `SM_DICTATE` headless hook in Main.kt. */
data class ComposerExternalDictate(val wavPath: String)

/** Best-effort MIME for a path (java.nio Files.probeContentType), octet-stream when unknown. Pure —
 *  mirrors the launcher's `probeMime` so chat + launcher guess identically. */
internal fun composerMime(path: java.nio.file.Path): String =
    runCatching { Files.probeContentType(path) }.getOrNull() ?: "application/octet-stream"

/** Kind guess from a MIME: audio → "voice", else null (broker infers). Mirrors the launcher. */
internal fun composerKind(mime: String): String? =
    if (mime.startsWith("audio")) "voice" else null

/** Filters picked/dropped files to ones that still exist as a regular file on disk — pure so the
 *  filtering is unit-testable without AWT or Compose. Silently drops entries that vanished between
 *  the OS drop/dialog and staging (a stale symlink target, a file deleted mid-drag) rather than
 *  letting a missing file crash [FileChunkSource]. */
internal fun filterExistingFiles(files: List<File>): List<File> = files.filter { it.isFile }

/** Converts the file-URI strings from `DragData.FilesList.readFiles()` back into [File]s. Compose
 *  Desktop's drag source encodes each dropped OS file as `File.toURI().toString()` (a `file:` URI),
 *  not a raw path — pure so the URI parsing is unit-testable without an actual AWT drag session. An
 *  entry that fails to parse (malformed/non-file URI) is dropped rather than throwing. */
internal fun composerFilesFromDragData(uris: List<String>): List<File> =
    uris.mapNotNull { runCatching { File(URI(it)) }.getOrNull() }

/**
 * Send-gating predicate: something to send (text OR at least one attachment) AND no chip is still
 * Uploading or Failed AND not already sending. Pure so the gating matrix is unit-testable without a
 * UI harness. The Uploading/Failed block is the load-bearing correctness bit — never send a message
 * minus its attachment (ported from Android's `anyBlocking` rule).
 */
internal fun canSendComposer(
    text: String,
    attachments: List<ComposerAttachment>,
    sending: Boolean,
): Boolean =
    (text.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.none { it.state is UploadState.Uploading || it.state is UploadState.Failed } &&
        !sending

/** Blocking AWT multi-select file picker (modal on the EDT by AWT contract — fine, Compose Desktop
 *  Main == EDT). The default [DesktopComposer.pickFiles] seam; tests inject a fake. */
internal fun composerPickFiles(): List<File> {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList() ?: emptyList()
}

/**
 * Chat composer with attachment chips.
 *
 * @param draft current draft text (hoisted — per-session in [ChatPanel]/WorkspaceRoot).
 * @param sending true while the client-local "Sending…" marker is up (blocks re-send).
 * @param agentWorking true while the broker says the agent is busy — flips the trailing icon to
 *   Stop so the user can interrupt without leaving the composer.
 * @param onSend fired with the TRIMMED draft + the finalized attachment file_ids (gated so all
 *   staged chips are Done). The composer clears its own chips on send; the caller clears the draft.
 * @param onInterrupt fired by the Stop icon while [agentWorking].
 * @param onUpload the upload seam — `(source, name, mime, kind, onProgress) -> file_id?`. When null,
 *   the Attach affordance is hidden (text-only composer). [ChatPanel] binds this to
 *   `app.uploadResumable(session.id, …)`; tests inject a fake so they don't hit the network.
 * @param pickFiles the file-picker seam (default = the real AWT dialog); tests inject a fake.
 * @param externalAttach a one-shot "stage this file then send" request (M4d-T3), delivered from
 *   outside the composer's own click-driven state (see [ComposerExternalAttach] KDoc — the
 *   off-by-default `SM_CHAT_ATTACH` headless hook). Routed through the SAME `stageFiles`/`sendWith`
 *   funnel the Attach dialog + Send button use — never a parallel path. Applied once, then
 *   [onExternalAttachConsumed] clears the source (mirrors [ChatPanel]'s `externalOpen` pattern).
 * @param onExternalAttachConsumed fired once [externalAttach] has been staged, uploaded to a
 *   terminal state, and (on success) sent — or dropped (missing file / no [onUpload] bound / upload
 *   failed) — so the caller's one-shot holder resets.
 * @param onTranscribeAudio the mic-dictation transcribe seam — `(wavBytes, filename) -> cleaned
 *   text?`. When null, the MicButton is hidden entirely (mirrors [onUpload]'s null-hides-Attach
 *   rule). [ChatPanel] binds this to `app.transcribeAudio(session.id, bytes, filename)`.
 * @param micRecorderFactory the mic-capture seam (default = the real [MicRecorder]); tests inject
 *   a fake [MicCapture] so they never open a real audio line.
 * @param externalDictate a one-shot "transcribe this WAV file, no mic" request (M5-1), delivered
 *   from outside the composer's own click-driven state — see [ComposerExternalDictate]'s KDoc.
 * @param onExternalDictateConsumed fired once [externalDictate] has been read and its cleaned text
 *   (if any) appended, so the caller's one-shot holder resets.
 */
@OptIn(ExperimentalComposeUiApi::class) // DragData / dragData() (external-file drop payload) — see
// the drop-target comment below for what was checked before opting in.
@Composable
fun DesktopComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    agentWorking: Boolean,
    onSend: (String, List<String>) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
    sessionKey: String = "",
    onUpload: (suspend (
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String?,
        onProgress: (Long, Long) -> Unit,
    ) -> String?)? = null,
    pickFiles: () -> List<File> = ::composerPickFiles,
    externalAttach: ComposerExternalAttach? = null,
    onExternalAttachConsumed: () -> Unit = {},
    onTranscribeAudio: (suspend (bytes: ByteArray, filename: String) -> String?)? = null,
    micRecorderFactory: () -> MicCapture = { MicRecorder() },
    externalDictate: ComposerExternalDictate? = null,
    onExternalDictateConsumed: () -> Unit = {},
) {
    // Attachment state is SCOPED to [sessionKey]: ChatPanel deliberately stays composed across
    // session switches (no key(session.id) wrapper), so a bare remember{} would leak session A's
    // uploaded chips into session B and gather A's file_ids into B's send. remember(sessionKey)
    // re-inits the list + id counters on switch (matches ChatPanel's prevSize/autoFollow pattern).
    val attachments = remember(sessionKey) { mutableStateListOf<ComposerAttachment>() }
    // Plain-var counters (single Main-thread dispatcher — no atomics needed); one holder per session.
    val ids = remember(sessionKey) { object { var nextId = 0L; var nextSeq = 0L } }
    val scope = rememberCoroutineScope()

    // Guarded update: apply only when the chip STILL exists AND belongs to the run identified by
    // [seq]. A late callback from a removed chip (idx < 0) or a superseded run (runSeq mismatch, e.g.
    // after Retry) is silently dropped — the stale-callback guard.
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
                // Progress may arrive off the Main thread (uploadResumable runs its IO internally);
                // marshal the state write back onto the composer scope's dispatcher. Guard against a
                // TERMINAL clobber: uploadResumable fires a final onProgress(total,total) right before
                // returning, and that marshaled write is QUEUED — it lands AFTER the synchronous
                // Done/Failed write below. Only apply while the chip is still Uploading, so the queued
                // final progress can't resurrect a settled chip to Uploading(1.0) (a stuck dead-end:
                // Uploading blocks send AND hides the × remove).
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

    fun stage(file: File) {
        val mime = composerMime(file.toPath())
        val id = (++ids.nextId).toString()
        attachments.add(
            ComposerAttachment(
                id = id,
                name = file.name,
                mime = mime,
                source = FileChunkSource(file),
                state = UploadState.Uploading(0f),
                kind = composerKind(mime),
            ),
        )
        launchUpload(id)
    }

    // Funnels a BATCH of files — from the Attach dialog OR an external OS drag-drop — through [stage]
    // after filtering to files that still exist. The single funnel means a dropped file gets an
    // IDENTICAL ComposerAttachment + upload + progress to a FileDialog-picked one (no parallel path).
    fun stageFiles(files: List<File>) {
        filterExistingFiles(files).forEach { stage(it) }
    }

    val canSend = canSendComposer(draft, attachments, sending)
    // Gather-and-send for an ARBITRARY [text] (not just the hoisted [draft]) — same gating +
    // file_id-gather + chip-clear the Send button/Enter key use. Parameterized so
    // [externalAttach]'s LaunchedEffect below can send its own text without racing the hoisted
    // draft's recomposition (see that effect's comment for why draft-then-doSend() doesn't work).
    fun sendWith(text: String) {
        if (canSendComposer(text, attachments, sending)) {
            val fileIds = attachments.mapNotNull { (it.state as? UploadState.Done)?.fileId }
            onSend(text.trim(), fileIds)
            attachments.clear()
        }
    }
    val doSend = { sendWith(draft) }

    val dictation = rememberDesktopDictation(
        resetKey = sessionKey,
        transcribeAudio = { bytes, name -> onTranscribeAudio?.invoke(bytes, name) },
        onAppend = { cleaned -> onDraftChange(draft + (if (draft.isBlank()) "" else " ") + cleaned) },
        recorderFactory = micRecorderFactory,
    )

    // SM_DICTATE headless hook delivery (M5-1): read the WAV straight off disk and feed it through
    // the SAME onTranscribeAudio seam the mic button uses — no MicCapture involved at all, since
    // there is no mic under Xvfb. A missing/blank path or an unbound seam is logged and dropped.
    LaunchedEffect(externalDictate) {
        val request = externalDictate ?: return@LaunchedEffect
        if (onTranscribeAudio == null) {
            println("[composer] SM_DICTATE ignored — no transcribe seam bound")
            onExternalDictateConsumed()
            return@LaunchedEffect
        }
        val file = File(request.wavPath)
        if (!file.isFile) {
            println("[composer] SM_DICTATE path is not a file: ${request.wavPath}")
            onExternalDictateConsumed()
            return@LaunchedEffect
        }
        val cleaned = onTranscribeAudio.invoke(file.readBytes(), file.name)?.trim()
        if (!cleaned.isNullOrEmpty()) {
            onDraftChange(draft + (if (draft.isBlank()) "" else " ") + cleaned)
            println("[composer] SM_DICTATE appended cleaned text for '${request.wavPath}'")
        } else {
            println("[composer] SM_DICTATE transcribe returned no text for '${request.wavPath}'")
        }
        onExternalDictateConsumed()
    }

    // SM_CHAT_ATTACH headless hook delivery (M4d-T3): stage the requested file through the SAME
    // [stageFiles] funnel the Attach dialog/drop target use, poll (no completion callback exists on
    // the upload seam to suspend on directly) until that chip reaches a TERMINAL state, then —  on
    // success — [sendWith] the requested text through the SAME gather-and-send path the Send button
    // uses. Deliberately does NOT go through onDraftChange+doSend(): draft is hoisted OUTSIDE this
    // composable (WorkspaceRoot's draft map), so writing it here and immediately calling the
    // (stale-closure) doSend would race the recomposition that updates `draft` — sendWith(text)
    // sidesteps that entirely. Keyed on [externalAttach] (not Unit) so a new request re-runs.
    LaunchedEffect(externalAttach) {
        val request = externalAttach ?: return@LaunchedEffect
        if (onUpload == null) {
            println("[composer] SM_CHAT_ATTACH ignored — no upload seam bound (text-only composer)")
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        val file = File(request.filePath)
        if (!file.isFile) {
            println("[composer] SM_CHAT_ATTACH path is not a file: ${request.filePath}")
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        val beforeIds = attachments.map { it.id }.toSet()
        stageFiles(listOf(file))
        val newId = attachments.map { it.id }.firstOrNull { it !in beforeIds }
        if (newId == null) {
            println("[composer] SM_CHAT_ATTACH staging produced no chip: ${request.filePath}")
            onExternalAttachConsumed()
            return@LaunchedEffect
        }
        // Poll (200ms) for the new chip to leave Uploading — up to 60s (a resumable upload chunk
        // loop, not a single request; generous so a slow/large file doesn't false-time-out).
        val deadline = System.currentTimeMillis() + 60_000
        var current = attachments.firstOrNull { it.id == newId }
        while (current != null && current.state is UploadState.Uploading && System.currentTimeMillis() < deadline) {
            delay(200)
            current = attachments.firstOrNull { it.id == newId }
        }
        if (current?.state is UploadState.Done) {
            sendWith(request.text)
            println("[composer] SM_CHAT_ATTACH sent '${request.filePath}' + text to the session")
        } else {
            println("[composer] SM_CHAT_ATTACH upload did not finish Done (state=${current?.state}): ${request.filePath}")
        }
        onExternalAttachConsumed()
    }

    // Drag-over highlight — purely visual, reset defensively on both onExited (pointer left this
    // target's bounds) and onEnded (the whole OS drag session finished, e.g. dropped elsewhere).
    var dragOver by remember(sessionKey) { mutableStateOf(false) }

    // External-file drop target. `androidx.compose.foundation.draganddrop.dragAndDropTarget` (the
    // Modifier attached below) is STABLE — no ExperimentalFoundationApi marker on it. Reading the
    // dropped payload as `DragData` DOES need the file-level `@OptIn(ExperimentalComposeUiApi::class)`
    // above (see the file header). On desktop, an external OS file drop arrives as `DragData.FilesList`
    // — decompiling `DragDataFilesListImpl` shows it reads `DataFlavor.javaFileListFlavor` off the AWT
    // transferable and maps each `java.io.File` to `file.toURI().toString()`, so
    // [composerFilesFromDragData] parses those URIs back to Files. Gated on `onUpload != null` — the
    // same rule as the Attach button: a text-only composer (no upload seam bound) doesn't accept drops.
    // Note: this anonymous target has no equals(), so dragAndDropTarget's DropTargetElement rebuilds
    // the underlying delegate node on every recomposition (a minor churn, not a swap-in-place). It's
    // safe: the AWT DropTarget is owned at the scene root and dispatches per-event by live tree
    // traversal, and onDrop closes over the same remember(sessionKey)-scoped state regardless of which
    // instance is live — so no in-flight drag is lost and no cross-session leak. (A remember(sessionKey)
    // wrap + rememberUpdatedState callback would remove the churn — a cheap future tidy.)
    val dropTarget = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) {
            dragOver = true
        }

        override fun onExited(event: DragAndDropEvent) {
            dragOver = false
        }

        override fun onEnded(event: DragAndDropEvent) {
            dragOver = false
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            val files = (event.dragData() as? DragData.FilesList)
                ?.readFiles()
                ?.let(::composerFilesFromDragData)
                ?: return false
            if (files.isEmpty()) return false
            stageFiles(files)
            return true
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onUpload != null) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                } else {
                    Modifier
                },
            )
            .then(
                if (dragOver) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { att ->
                    key(att.id) {
                        ComposerChip(
                            att = att,
                            onRemove = { attachments.removeAll { it.id == att.id } },
                            onRetry = { launchUpload(att.id) },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("composer-input")
                .onPreviewKeyEvent { e: KeyEvent ->
                    if (isComposerSendKey(e.key, e.type, e.isShiftPressed)) {
                        // Consume ONLY when we actually send; a blank/sending/upload-blocked draft
                        // falls through so the multiline field handles Enter itself (no stray
                        // newline, no double-send).
                        if (canSend) { doSend(); true } else false
                    } else {
                        false
                    }
                },
            placeholder = { Text("Message the agent…") },
            maxLines = 8,
            leadingIcon = if (onUpload != null) {
                {
                    IconButton(
                        onClick = { stageFiles(pickFiles()) },
                        modifier = Modifier.testTag("composer-attach"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Attach")
                    }
                }
            } else {
                null
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onTranscribeAudio != null) {
                        MicButton(
                            recording = dictation.recording,
                            transcribing = dictation.transcribing,
                            micUnavailable = dictation.micUnavailable,
                            onClick = { if (dictation.recording) dictation.stopMic() else dictation.startMic() },
                            modifier = Modifier.testTag("composer-mic"),
                        )
                    }
                    if (agentWorking) {
                        IconButton(onClick = onInterrupt, modifier = Modifier.testTag("composer-stop")) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    } else {
                        IconButton(
                            onClick = doSend,
                            enabled = canSend,
                            modifier = Modifier.testTag("composer-send"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            },
        )
        LaunchedEffect(dictation.errorMessage) {
            if (dictation.errorMessage != null) {
                delay(4000)
                dictation.errorMessage = null
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
}

/** One staged-attachment chip: an in-flight determinate spinner + name (+ %) while Uploading, a
 *  "· Retry" affordance (whole chip clickable, error-tinted) on Failed, and an × remove once the
 *  upload is settled (Done/Failed — an in-flight upload has no ×, matching Android). */
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
