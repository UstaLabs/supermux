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
package dev.supermux.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
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

/** Best-effort MIME for a path (java.nio Files.probeContentType), octet-stream when unknown. Pure —
 *  mirrors the launcher's `probeMime` so chat + launcher guess identically. */
internal fun composerMime(path: java.nio.file.Path): String =
    runCatching { Files.probeContentType(path) }.getOrNull() ?: "application/octet-stream"

/** Kind guess from a MIME: audio → "voice", else null (broker infers). Mirrors the launcher. */
internal fun composerKind(mime: String): String? =
    if (mime.startsWith("audio")) "voice" else null

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
 */
@Composable
fun DesktopComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    agentWorking: Boolean,
    onSend: (String, List<String>) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
    onUpload: (suspend (
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String?,
        onProgress: (Long, Long) -> Unit,
    ) -> String?)? = null,
    pickFiles: () -> List<File> = ::composerPickFiles,
) {
    val attachments = remember { mutableStateListOf<ComposerAttachment>() }
    val idGen = remember { AtomicLong(0L) }
    val seqGen = remember { AtomicLong(0L) }
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
        val seq = seqGen.incrementAndGet()
        val att = attachments[idx].copy(state = UploadState.Uploading(0f), runSeq = seq)
        attachments[idx] = att
        scope.launch {
            val fileId = up(att.source, att.name, att.mime, att.kind) { sent, total ->
                val pct = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
                // Progress may arrive off the Main thread (uploadResumable runs its IO internally);
                // marshal the state write back onto the composer scope's dispatcher.
                scope.launch { updateAtt(id, seq) { it.copy(state = UploadState.Uploading(pct)) } }
            }
            updateAtt(id, seq) {
                if (fileId != null) it.copy(state = UploadState.Done(fileId))
                else it.copy(state = UploadState.Failed)
            }
        }
    }

    fun stage(file: File) {
        val mime = composerMime(file.toPath())
        val id = idGen.incrementAndGet().toString()
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

    val canSend = canSendComposer(draft, attachments, sending)
    val doSend = {
        if (canSendComposer(draft, attachments, sending)) {
            val fileIds = attachments.mapNotNull { (it.state as? UploadState.Done)?.fileId }
            onSend(draft.trim(), fileIds)
            attachments.clear()
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { att ->
                    ComposerChip(
                        att = att,
                        onRemove = { attachments.removeAll { it.id == att.id } },
                        onRetry = { launchUpload(att.id) },
                    )
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
                        onClick = { pickFiles().forEach { stage(it) } },
                        modifier = Modifier.testTag("composer-attach"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Attach")
                    }
                }
            } else {
                null
            },
            trailingIcon = {
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
            },
        )
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
