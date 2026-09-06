// The ONE composer (cluster D3, widened to the launcher in F7) — a single soft rounded card holding
// the multiline draft, a chip row while attachments are staged, and a bottom toolbar: + attach ·
// model pill · effort pill on the left; mic + send/stop on the right. Above the card sit the
// `/command` menu, the "Transcribing…" strip and (while dictating) the RecordingBar takeover.
//
// TWO screens render it: the chat panel and the New Session launcher, whose capsule card, staged
// chips, field, key policy, attach/mic/send and slash menu used to be a hand-copy of this file's
// (acknowledged as such in its own comments). The launcher now passes:
//   - [ComposerChrome] — the rendering-only differences (solid card on a raised page, a taller
//     field, its "/" menu drawn inside the card, a thumb-sized send disc) plus [ComposerTags], so
//     its own suite and device automation keep addressing the very same nodes;
//   - [ComposerStaging] — PRE-SPAWN staging: a pick goes to the caller's hoisted list instead of an
//     upload, because there is no session to upload against until the spawn returns;
//   - a `toolbar` slot — the launcher lays out its OWN pills (agent / model / effort) around the
//     composer's attach / mic / send, which stay the composer's in behaviour, tag and shape;
//   - `sendEnabled` / `onSend` — its submit policy (a project must be chosen too) and its spawn.
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
//   - Enter: `isComposerSendEnter()`, which asks PER EVENT whether the key came from a real
//     keyboard (Android inspects the native event; desktop always says yes), so a soft-IME Return
//     inserts a newline even on a phone with a keyboard paired.
//
// Unlike the launcher (which STAGES files pre-spawn and uploads them post-spawn), the chat composer
// uploads each chip IMMEDIATELY against the LIVE session — so a chip carries a live upload STATE
// (Uploading(pct) → Done(fileId) | Failed). Send is gated while any chip is still Uploading OR
// Failed, so a message is never sent minus its attachment.
package dev.supermux.ui.chat

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.session.hasAgentLogo
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
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

/**
 * Test tags for the composer's own chrome.
 *
 * The composer is rendered by TWO screens (cluster F7): the chat panel and the New Session
 * launcher, whose card/field/attach/send used to be a hand-copy of this one. The launcher passes
 * its own tags so its suite (and device automation) keeps addressing the very same nodes it always
 * did, while there is only one implementation left to keep correct.
 *
 * Every piece is always rendered when its state calls for it; [stop] is simply never reached on a
 * screen with no interruptible agent (the launcher).
 */
@Immutable
data class ComposerTags(
    val card: String = "composer-card",
    val input: String = "composer-input",
    val attach: String = "composer-attach",
    val mic: String = "composer-mic",
    val send: String = "composer-send",
    val stop: String = "composer-stop",
    val banner: String = "composer_banner",
    val micError: String = "composer-mic-error",
    /** Slash rows are tagged `"$slashItemPrefix${command.name}"`. */
    val slashItemPrefix: String = "chat_slash_item_",
    /** Staged (pre-spawn) chips are tagged `"$stagedChipPrefix${file.name}"`. */
    val stagedChipPrefix: String = "composer_staged_",
)

/** Where the [RecordingBar] takes the composer over while dictating. */
enum class ComposerRecordingTakeover {
    /**
     * Chat: the whole card is replaced by the recording bar, under a pointer as well as under
     * touch — there is nothing to type into while the mic is live.
     */
    WholeCard,

    /**
     * Launcher: only the field + toolbar are replaced, and only where there is NO pointer. A
     * desktop launcher keeps its field and spins the mic button in place, which is what it has
     * always done; the card, its border and any staged chips stay put on both.
     */
    FieldOnTouch,
}

/**
 * The composer's shape. Defaults ARE the chat composer; the launcher overrides the handful of
 * places the two screens genuinely differ (a solid card on a raised page, a taller field, its
 * insert-only "/" menu drawn inside the card, a big thumb-sized send disc).
 *
 * Everything here is a rendering decision only — behaviour (send gating, staging, dictation, the
 * key policy) is shared verbatim by both screens.
 */
@Immutable
data class ComposerChrome(
    val tags: ComposerTags = ComposerTags(),
    /** null → the chat card's translucent `surfaceContainerHigh`. */
    val cardBackground: Color? = null,
    val cardVerticalPadding: Dp = 12.dp,
    /**
     * Animate the whole border primary-tinted on focus. Touch only — a pointer host always gets
     * the quiet static outline, so no animation runs on desktop at all.
     */
    val animatedFocusBorder: Boolean = false,
    val fieldMinHeight: Dp = 28.dp,
    val fieldMaxHeight: Dp = 160.dp,
    val fieldFontSize: TextUnit = 14.sp,
    val fieldLineHeight: TextUnit = 20.sp,
    val fieldMaxLines: Int = 8,
    val capitalizeSentences: Boolean = false,
    /** Draw the "/" menu inside the card under the field (launcher) rather than above it (chat). */
    val slashInsideCard: Boolean = false,
    /**
     * Every command inserts its text and nothing is filtered by `handledControlKinds`. The
     * pre-spawn launcher has no session to run a CONTROL command against, so it offers the whole
     * catalogue as insert-only (and draws no action glyph).
     */
    val slashInsertOnly: Boolean = false,
    /** Draw the "Transcribing…" strip and the dictation banner inside the card, under the field. */
    val transientLinesInsideCard: Boolean = false,
    val recordingTakeover: ComposerRecordingTakeover = ComposerRecordingTakeover.WholeCard,
    /** Under touch, the send disc grows to Android's 40dp press-scaled button. */
    val largeTouchSend: Boolean = false,
    val sendContentDescription: String = "Send",
)

/** One file staged BEFORE any session exists — the launcher's pre-spawn attachment. It carries no
 *  upload state because there is nothing to upload against yet; the caller uploads them itself
 *  once the spawn returns. */
data class ComposerStagedFile(
    val id: Long,
    val name: String,
    val mime: String,
    val source: ChunkSource,
)

/**
 * Pre-spawn staging (the launcher). Non-null replaces the live upload funnel entirely: a pick is
 * handed to [onStage] instead of being uploaded, the chip strip renders name + × instead of upload
 * progress, and clipboard paste / external drop stay off (there is no session to paste against).
 *
 * The list is HOISTED because only the caller can turn it into the `staged` argument of its spawn.
 */
@Immutable
class ComposerStaging(
    val files: List<ComposerStagedFile>,
    val onStage: (PickedFile) -> Unit,
    val onRemove: (ComposerStagedFile) -> Unit,
)

/**
 * The composer's own toolbar controls, handed to a custom [Composer] `toolbar` so a screen can lay
 * them out its own way (the launcher's pointer row vs its two thumb-reachable touch rows) while the
 * composer keeps owning their behaviour, their tags and their input-mode branches.
 *
 * Each is a no-op where its seam is unbound: [Attach] draws nothing without an upload or staging
 * seam, [Mic] nothing without a transcribe seam.
 */
@Stable
interface ComposerToolbarScope {
    @Composable fun Attach()

    @Composable fun Mic()

    /** The send disc — or the Stop disc while the agent is working. */
    @Composable fun Send()
}

/** The composer's default hint line (a pointer host shows it; touch hosts name the session). */
const val DEFAULT_COMPOSER_PLACEHOLDER =
    "Message the agent, tag @files, or use /commands and /skills"

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
internal fun composerReasoningLabel(
    reasoning: ReasoningResponse,
    sessionCurrent: String? = null,
): String {
    val current = sessionCurrent?.takeIf { it.isNotBlank() }
        ?: reasoning.current?.takeIf { it.isNotBlank() }
        ?: return "effort"
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
    /**
     * A CONTROL command (one with an `action`) was picked. Return true when the host acted on it.
     *
     * The return value is load-bearing: a command whose kind this host does not handle is never
     * OFFERED in the menu at all (see [handledControlKinds]), because picking it would clear the
     * typed token and then do nothing.
     */
    onControl: (SlashCommand) -> Unit = {},
    /**
     * The `ControlAction.kind`s this host can actually perform (`rename`, `mute`, `kill`, `model`,
     * `stop`, …). Anything else is filtered out of the slash menu. The default is "none", so a
     * composer that never wires [onControl] offers insert-only commands and nothing dead.
     */
    handledControlKinds: Set<String> = emptySet(),
    placeholder: String = DEFAULT_COMPOSER_PLACEHOLDER,
    externalAttach: ComposerExternalAttach? = null,
    onExternalAttachConsumed: () -> Unit = {},
    externalDictate: ComposerExternalDictate? = null,
    onExternalDictateConsumed: () -> Unit = {},
    models: ModelsResponse? = null,
    reasoning: ReasoningResponse? = null,
    sessionModel: String? = null,
    /**
     * The session's stored reasoning/effort level, kept fresh by `session_state` frames and by the
     * optimistic update after a pick. Preferred over [reasoning]'s `current`, which is only a
     * snapshot of the catalog fetch — without this the pill goes stale the moment the level changes
     * anywhere else (the same precedence [sessionModel] has for the model pill).
     */
    sessionReasoning: String? = null,
    sessionAgent: String? = null,
    onPickModel: (String) -> Unit = {},
    onPickReasoning: (String) -> Unit = {},
    /**
     * A model/effort pill was tapped, BEFORE the picker opens. A host that fetches its catalogs
     * lazily (Android under touch — the phone should not spend a request on every chat it opens)
     * refreshes them here; a host that fetched on open ignores it.
     */
    onPickerOpened: () -> Unit = {},
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
    /** How this screen wants the card drawn (cluster F7). Default = the chat composer. */
    chrome: ComposerChrome = ComposerChrome(),
    /**
     * Caret-aware text, for a caller that must place the cursor itself (the launcher puts it at the
     * end after a draft restore, a "/" insert and a dictation append). When non-null it REPLACES
     * [draft]/[onDraftChange] as the field's value, and every programmatic edit the composer makes
     * is delivered as a [TextFieldValue] whose selection is collapsed at the end of the new text.
     */
    value: TextFieldValue? = null,
    onValueChange: (TextFieldValue) -> Unit = {},
    /** Pre-spawn staging instead of live uploads — see [ComposerStaging]. */
    staging: ComposerStaging? = null,
    /** Identifies this screen to `Platform.pickFiles`/`captureImage`, so a pick that outlives an
     *  activity recreation comes back to the screen that asked for it. */
    pickRequester: String = COMPOSER_PICK_REQUESTER,
    /** The mic behind dictation; defaults to the platform's. Tests inject a fake. */
    micCapture: MicCapture? = null,
    /**
     * Overrides the composer's own send gating. The launcher also needs a project chosen, and its
     * staged files never carry an upload state here, so it computes the whole predicate itself.
     */
    sendEnabled: Boolean? = null,
    /** Spinner inside the send disc while a spawn is in flight (the launcher's submit). */
    sendProgress: Boolean = false,
    /**
     * Replaces the default bottom toolbar. The screen lays out its OWN pills around the composer's
     * attach / mic / send controls, which stay the composer's (behaviour, tags, input-mode shape).
     */
    toolbar: (@Composable ColumnScope.(ComposerToolbarScope) -> Unit)? = null,
) {
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val tags = chrome.tags
    // The field's text, whichever of the two value APIs the caller bound.
    val text = value?.text ?: draft
    // Every PROGRAMMATIC edit (slash insert, dictation append, external hooks) goes through here,
    // so a caret-aware caller lands the cursor at the end instead of wherever it happened to be.
    val setText: (String) -> Unit = { t ->
        if (value != null) onValueChange(TextFieldValue(t, TextRange(t.length))) else onDraftChange(t)
    }
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
     *  dropped file gets an identical chip + upload + progress to a dialog-picked one. In
     *  [ComposerStaging] mode the file is handed to the caller's hoisted list instead: there is no
     *  session to upload against yet. */
    fun stage(picked: PickedFile): String {
        if (staging != null) {
            val id = ++ids.nextId
            staging.onStage(picked)
            return id.toString()
        }
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
    // Keyed on the PLATFORM only, never the session: re-subscribing on a session switch drops the
    // recreation-stashed pick that is mid-delivery, which is exactly the case this seam exists for.
    // `stage` reads the current session's list through the composition, so a late pick lands in the
    // session that is on screen when it arrives.
    val stageLatest by rememberUpdatedState<(PickedFile) -> Unit> { stage(it) }
    LaunchedEffect(platform, pickRequester) {
        platform.pendingPicks(pickRequester).collect { stageLatest(it) }
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
        LaunchedEffect(sessionKey, text, draftLoaded) {
            if (!draftLoaded) return@LaunchedEffect
            delay(400)
            saveDraft(sessionKey, text)
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

    // The launcher overrides this wholesale (it also needs a project, and its staged files carry
    // no upload state); everything downstream — Enter, the disc, the external-attach send — reads
    // this one value, so there is exactly one gate.
    val canSend = sendEnabled ?: canSendComposer(text, attachments, sending)

    // Gather-and-send for an ARBITRARY [text] (not just the hoisted [draft]) — same gating +
    // file_id gather + chip clear the Send button/Enter key use. Parameterized so [externalAttach]
    // can send its own text without racing the hoisted draft's recomposition.
    fun sendWith(text: String) {
        if (sendEnabled ?: canSendComposer(text, attachments, sending)) {
            val fileIds = attachments.mapNotNull { (it.state as? UploadState.Done)?.fileId }
            onSend(text.trim(), fileIds)
            attachments.clear()
        }
    }
    val doSend = {
        if (canSend) haptic.perform(HapticKind.Confirm)
        sendWith(text)
    }

    val dictation = rememberDictation(
        resetKey = sessionKey,
        loadGlossary = actions.loadGlossary,
        transcribeDraft = actions.transcribeDraft,
        transcribeAudio = { bytes, name -> onTranscribeAudio?.invoke(bytes, name) },
        onAppend = { cleaned -> setText(text + (if (text.isBlank()) "" else " ") + cleaned) },
        mic = micCapture ?: platform.mic,
    )

    // SM_DICTATE headless hook: feed bytes already on disk through the SAME transcribe seam the mic
    // button uses — no MicCapture involved at all, since there is no mic under Xvfb.
    LaunchedEffect(externalDictate) {
        val request = externalDictate ?: return@LaunchedEffect
        val bytes = request.bytes
        if (onTranscribeAudio != null && bytes != null) {
            val cleaned = onTranscribeAudio.invoke(bytes, request.filename)?.trim()
            if (!cleaned.isNullOrEmpty()) {
                setText(text + (if (text.isBlank()) "" else " ") + cleaned)
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
    val slashQuery = activeSlashQuery(text)
    // Remembered: this ran on EVERY recomposition (every keystroke recomposes the whole card).
    val slashMatches = remember(text, commands, handledControlKinds, chrome.slashInsertOnly) {
        slashCommandMatches(text, commands) { chrome.slashInsertOnly || it in handledControlKinds }
    }
    var selectedSlashIndex by remember { mutableIntStateOf(0) }
    var slashMenuDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(slashQuery) { selectedSlashIndex = 0; slashMenuDismissed = false }
    val slashMenuOpen = slashMatches.isNotEmpty() && !slashMenuDismissed
    val safeSlashIndex = selectedSlashIndex.coerceIn(0, (slashMatches.size - 1).coerceAtLeast(0))

    // Apply a slash command — shared by a tap and by keyboard Enter. Control commands clear the
    // token and fire onControl; everything else inserts its text.
    fun selectSlashCommand(cmd: SlashCommand) {
        haptic.perform(HapticKind.Tick)
        if (cmd.action != null && !chrome.slashInsertOnly) {
            setText(replaceSlashToken(text, ""))
            onControl(cmd)
        } else {
            setText(replaceSlashToken(text, slashInsertText(cmd)))
        }
    }

    // ── pickers ─────────────────────────────────────────────────────────────────────────
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    LaunchedEffect(openModelPickerNonce) { if (openModelPickerNonce > 0L) modelMenu = true }
    // LIVE session state first, catalog second: `session.model` is kept fresh by session_state
    // frames and by the optimistic write after a pick, while `models.current` is a snapshot of the
    // fetch that happened when the panel opened.
    val modelCurrent = sessionModel?.takeIf { it.isNotBlank() } ?: models?.current
    val showModelPill = models != null || !sessionModel.isNullOrBlank()
    val r = reasoning
    val showReasoningPill = r != null && r.visible && r.levels.size > 1
    val reasoningCurrent = sessionReasoning?.takeIf { it.isNotBlank() } ?: r?.current

    val cs = MaterialTheme.colorScheme
    val inputInteraction = remember { MutableInteractionSource() }
    val inputFocused by inputInteraction.collectIsFocusedAsState()
    var dragOver by remember(sessionKey) { mutableStateOf(false) }

    val cardShape = RoundedCornerShape(Radii.lg + 8.dp) // ~24dp — matches the mock capsule
    // Touch-only: the whole border animates primary-tinted on focus (the launcher's card). Read
    // ONLY inside the branch that wants it, so no animation is ever started on a pointer host.
    val animatedBorder = if (chrome.animatedFocusBorder && !pointer) {
        val animated by animateColorAsState(
            targetValue = if (inputFocused) cs.primary else cs.outlineVariant,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "composer_card_border",
        )
        animated
    } else {
        null
    }
    val cardBorder = when {
        dragOver -> cs.primary
        animatedBorder != null -> animatedBorder
        inputFocused -> cs.outline.copy(alpha = 0.55f)
        else -> cs.outlineVariant.copy(alpha = 0.65f)
    }
    val cardBorderWidth = if (dragOver) 2.dp else 1.dp

    // Attach is bound by EITHER seam: a live upload (chat) or pre-spawn staging (the launcher).
    val attachBound = onUpload != null || staging != null
    val stagedFiles = staging?.files.orEmpty()
    val largeSend = chrome.largeTouchSend && !pointer

    // The composer's own controls, so a screen supplying its own [toolbar] still gets THESE — same
    // behaviour, same tags, same input-mode branches — around its own pills.
    val toolbarScope = object : ComposerToolbarScope {
        @Composable
        override fun Attach() {
            if (!attachBound) return
            AttachControl(
                pointer = pointer,
                camera = platform.caps.camera,
                testTag = tags.attach,
                clipboardHasImage = {
                    // Pre-spawn staging has no clipboard path at all (nothing to paste against).
                    staging == null && platform.caps.clipboardImages && platform.clipboard.hasImage()
                },
                onPickFiles = { kind ->
                    scope.launch { stageFiles(platform.pickFiles(kind, pickRequester)) }
                },
                onCaptureImage = {
                    scope.launch { platform.captureImage(pickRequester)?.let { stage(it) } }
                },
                onCaptureVideo = {
                    scope.launch { platform.captureVideo(pickRequester)?.let { stage(it) } }
                },
                onPasteImage = { launchPasteImages() },
            )
        }

        @Composable
        override fun Mic() {
            if (onTranscribeAudio == null) return
            MicButton(
                recording = dictation.recording,
                transcribing = dictation.transcribing,
                micUnavailable = dictation.micUnavailable,
                onClick = { dictation.onMicClick() },
                modifier = Modifier.testTag(tags.mic),
            )
        }

        @Composable
        override fun Send() {
            if (agentWorking) {
                IconButton(
                    onClick = onInterrupt,
                    modifier = Modifier.size(32.dp).testTag(tags.stop),
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
                ComposerSendButton(
                    enabled = canSend,
                    progress = sendProgress,
                    large = largeSend,
                    contentDescription = chrome.sendContentDescription,
                    testTag = tags.send,
                    onClick = doSend,
                )
            }
        }
    }

    // Recording takes the composer over: the WHOLE card in chat, only the field + toolbar (and only
    // where there is no pointer) in the launcher.
    val wholeCardRecording =
        dictation.active && chrome.recordingTakeover == ComposerRecordingTakeover.WholeCard
    val fieldRecording =
        dictation.active && chrome.recordingTakeover == ComposerRecordingTakeover.FieldOnTouch && !pointer

    val slashMenuBlock: @Composable () -> Unit = {
        SlashMenu(
            matches = slashMatches,
            selectedIndex = safeSlashIndex,
            onSelect = { selectSlashCommand(it) },
            modifier = if (chrome.slashInsideCard) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerHigh)
            } else {
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer)
            },
            testTagPrefix = tags.slashItemPrefix,
            showActionGlyph = !chrome.slashInsertOnly,
        )
    }

    val card: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(chrome.cardBackground ?: cs.surfaceContainerHigh.copy(alpha = 0.72f))
                .border(cardBorderWidth, cardBorder, cardShape)
                .padding(horizontal = 14.dp, vertical = chrome.cardVerticalPadding)
                .testTag(tags.card),
        ) {
            if (staging != null) {
                // Pre-spawn strip: name + × only. There is no upload in flight to report on.
                if (stagedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        stagedFiles.forEach { file ->
                            key(file.id) {
                                StagedChip(
                                    name = file.name,
                                    testTag = tags.stagedChipPrefix + file.name,
                                    onRemove = { staging.onRemove(file) },
                                )
                            }
                        }
                    }
                }
            } else if (attachments.isNotEmpty() || pastePending) {
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

            if (fieldRecording) {
                RecordingBar(
                    seconds = dictation.recordingSeconds,
                    liveTranscript = dictation.liveTranscript.orEmpty(),
                    onStop = { dictation.stopMic() },
                    onCancel = { dictation.cancelMic() },
                )
            } else {
                val fieldModifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = chrome.fieldMinHeight, max = chrome.fieldMaxHeight)
                    .testTag(tags.input)
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
                            // Per EVENT, not per window: a phone with a keyboard paired still
                            // shows a soft IME, whose Return must insert a newline.
                            e.isComposerSendEnter() -> {
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
                                // Staged mode has no live upload to paste into.
                                uploadBound = onUpload != null,
                                // Probe the clipboard ONLY after the paste chord matches — not
                                // on every keystroke (cross-process selection can stall).
                                likelyHasImage = { platform.clipboard.hasImage() },
                                onPasteImage = { launchPasteImages() },
                            ) -> true
                            else -> false
                        }
                    }
                val fieldTextStyle = TextStyle(
                    color = cs.onSurface,
                    fontSize = chrome.fieldFontSize,
                    lineHeight = chrome.fieldLineHeight,
                )
                val fieldKeyboardOptions = if (chrome.capitalizeSentences) {
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                } else {
                    KeyboardOptions.Default
                }
                val fieldDecoration: @Composable (@Composable () -> Unit) -> Unit = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = cs.onSurfaceVariant.copy(alpha = 0.72f),
                                fontSize = chrome.fieldFontSize,
                                lineHeight = chrome.fieldLineHeight,
                                maxLines = 2,
                            )
                        }
                        inner()
                    }
                }
                if (value != null) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = fieldModifier,
                        textStyle = fieldTextStyle,
                        cursorBrush = SolidColor(cs.primary),
                        maxLines = chrome.fieldMaxLines,
                        keyboardOptions = fieldKeyboardOptions,
                        interactionSource = inputInteraction,
                        decorationBox = fieldDecoration,
                    )
                } else {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = fieldModifier,
                        textStyle = fieldTextStyle,
                        cursorBrush = SolidColor(cs.primary),
                        maxLines = chrome.fieldMaxLines,
                        keyboardOptions = fieldKeyboardOptions,
                        interactionSource = inputInteraction,
                        decorationBox = fieldDecoration,
                    )
                }

                if (chrome.slashInsideCard && slashMenuOpen) {
                    Spacer(Modifier.height(8.dp))
                    slashMenuBlock()
                }

                if (toolbar != null) {
                    toolbar(toolbarScope)
                } else {
                    // Bottom toolbar — + · model · effort on the left; mic · send/stop on the right.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            toolbarScope.Attach()
                            if (showModelPill) {
                                val modelOptions = listOf(DEFAULT_MODEL_ID to "Default") +
                                    (models?.models?.map { it.id to it.displayName } ?: emptyList())
                                Box(Modifier.testTag("composer-model-picker")) {
                                    ComposerPill(
                                        label = composerModelLabel(modelCurrent, models?.models ?: emptyList()),
                                        testTag = "composer-model-pill",
                                        onClick = { onPickerOpened(); modelMenu = true },
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
                                    current = reasoningCurrent,
                                    levels = r.levels,
                                )
                                Box(Modifier.testTag("composer-reasoning-picker")) {
                                    ComposerPill(
                                        label = composerReasoningLabel(r, reasoningCurrent),
                                        testTag = "composer-reasoning-pill",
                                        onClick = { onPickerOpened(); reasoningMenu = true },
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
                                                        if (level.id == reasoningCurrent) {
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
                                        current = reasoningCurrent,
                                        onPick = { onPickReasoning(it) },
                                        onDismiss = { reasoningMenu = false },
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            toolbarScope.Mic()
                            toolbarScope.Send()
                        }
                    }
                }
            }

            if (chrome.transientLinesInsideCard) {
                if (dictation.transcribing) {
                    Spacer(Modifier.height(Space.sm))
                    TranscribingIndicator()
                }
                if (!pointer) dictation.banner?.let { msg ->
                    Spacer(Modifier.height(Space.xs))
                    Text(msg, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.testTag(tags.banner))
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .externalFileDropTarget(
                enabled = onUpload != null,
                onDragOver = { dragOver = it },
                onFiles = { stageFiles(it) },
            ),
    ) {
        if (!chrome.slashInsideCard) {
            if (slashMenuOpen) {
                slashMenuBlock()
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
        }

        if (!chrome.transientLinesInsideCard) {
            // ONE transient line, not two: a touch host gets the takeover-style banner above the
            // card, a pointer host the quieter inline line under it (both carry the same text).
            if (!pointer) dictation.banner?.let { msg ->
                Text(
                    msg,
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(tags.banner),
                )
            }
            if (dictation.transcribing) TranscribingIndicator()
        }

        if (wholeCardRecording) {
            // Recording takes the composer over entirely (iOS/Android parity).
            RecordingBar(
                seconds = dictation.recordingSeconds,
                liveTranscript = dictation.liveTranscript.orEmpty(),
                onStop = { dictation.stopMic() },
                onCancel = { dictation.cancelMic() },
            )
        } else if (staging != null) {
            // No clipboard image path pre-spawn, so no right-click "Paste image" area either.
            card()
        } else {
            ComposerContextMenu(
                pasteEnabled = onUpload != null,
                onPasteImage = { launchPasteImages() },
                content = card,
            )
        }
        if (pointer) dictation.errorMessage?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).testTag(tags.micError),
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
 *
 * Touch-target rule: a pointer keeps the compact 32dp button a mouse can hit exactly; a finger gets
 * a full 48dp `IconButton` around a 34dp `surfaceContainerHigh` chip, so the `+` is both reachable
 * and visible as an affordance rather than a bare glyph.
 */
@Composable
private fun AttachControl(
    pointer: Boolean,
    camera: Boolean,
    testTag: String,
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
            modifier = Modifier.size(if (pointer) 32.dp else 48.dp).testTag(testTag),
        ) {
            if (pointer) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Attach",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(cs.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Attach",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
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
 *  inside the composer card, and the launcher's own pointer-side pills. Borderless + muted ink so
 *  they sit as chrome on the soft card. [testTag] is null where the tag lives on a wrapping node. */
@Composable
internal fun ComposerPill(
    label: String,
    testTag: String?,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
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

/** One pre-spawn staged chip: the file's name and an × to drop it. No progress, no retry — there
 *  is no upload in flight before the session exists. */
@Composable
private fun StagedChip(name: String, testTag: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(name, color = cs.onSurface, fontSize = 12.sp, maxLines = 1)
        Icon(
            Icons.Filled.Close,
            contentDescription = "Remove",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(14.dp).clickable { onRemove() },
        )
    }
}

/**
 * The send disc. A pointer gets the compact 28dp disc inside a 32dp button; [large] gives a finger
 * Android's 40dp press-scaled disc inside the IconButton's own 48dp target, and paints a DISABLED
 * disc in a dimmed primary rather than the pointer host's grey (it reads as "not yet", not "off").
 *
 * [progress] swaps the glyph for a spinner while a spawn is in flight (the launcher's submit).
 */
@Composable
private fun ComposerSendButton(
    enabled: Boolean,
    progress: Boolean,
    large: Boolean,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    // Only the big touch disc scales on press — nothing animates on a pointer host.
    val scale = if (large) {
        val pressed by interaction.collectIsPressedAsState()
        val animated by animateFloatAsState(
            targetValue = if (pressed) 0.88f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "send_scale",
        )
        animated
    } else {
        1f
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.then(if (large) Modifier else Modifier.size(32.dp)).testTag(testTag),
    ) {
        Box(
            modifier = Modifier
                .then(if (large) Modifier.scale(scale) else Modifier)
                .size(if (large) 40.dp else 28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        enabled -> cs.primary
                        large -> cs.primary.copy(alpha = 0.35f)
                        else -> cs.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (progress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (large) 18.dp else 14.dp),
                    strokeWidth = 2.dp,
                    color = cs.onPrimary,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = contentDescription,
                    tint = if (enabled || large) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(if (large) 18.dp else 14.dp),
                )
            }
        }
    }
}
