// The ONE chat stream renderer. Both apps' `Timeline.kt` collapsed into this file (cluster D2):
// desktop's copy is the base (inline image + video, the hand-written transport, the retry chip),
// Android's touch behaviour is the Touch/Compact branch (full-bleed reading width, the pinch-zoom
// lightbox, tap-to-open-externally), and the union is what both now get.
//
// Nothing here may name `java.*` / `android.*`: bytes are staged through `Platform.files.stageTemp`,
// images decode through Coil, video plays through Compose Media Player, and timestamps format
// through `:shared`'s `formatChatTime`.
package dev.supermux.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.chat.chatTimeLabel
import dev.supermux.chat.isRedactedReasoning
import dev.supermux.fs.pathLeaf
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.proto.Attachment
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.work.handOverAsTapped
import dev.supermux.ui.work.rememberDownloads
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadSource
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import dev.supermux.ui.resolveBashParts
import dev.supermux.ui.resolveEditParts
import dev.supermux.ui.toolDiffDoc
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.editor.rememberAppEditorTheme
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.packagedEditorFontFamily
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Media
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Sizes
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import dev.supermux.chat.previewFilename
import dev.supermux.fs.fileNameOf

// ---------------------------------------------------------------------------
// Reading width
// ---------------------------------------------------------------------------

/** The chat column's reading-width cap on a pointer-sized window (desktop's `CONTENT_MAX_WIDTH`). */
val TIMELINE_MAX_WIDTH = 860.dp

/**
 * The transcript's width rule, one line for both hosts:
 *  - Compact (a phone) → full bleed, which is what Android always did.
 *  - anything wider → capped at [TIMELINE_MAX_WIDTH], which is what desktop always did, and which
 *    an Android tablet now gets too (prose should never run the width of a 13" screen).
 *
 * Window insets are deliberately NOT applied here: both hosts already keep the status bar and the
 * IME clear at the screen level, and re-applying them around the transcript would double-pad it.
 */
@Composable
fun Modifier.timelineReadingWidth(): Modifier =
    if (LocalWindowWidthClass.current == WindowWidthClass.Compact) {
        this.fillMaxWidth()
    } else {
        this.widthIn(max = TIMELINE_MAX_WIDTH)
    }

// ---------------------------------------------------------------------------
// Read-aloud seam
// ---------------------------------------------------------------------------

/**
 * The read-aloud control the message meta row drives.
 *
 * Kept as an interface after D3 folded `MessageTts` into `:ui` (the production implementation is
 * [PlatformReadAloud], built from `LocalPlatform`) so a test can render a transcript with a silent,
 * recording stand-in instead of touching the process-wide speech state.
 */
@Stable
interface ReadAloud {
    /** True while [text] is the message currently being read. Reads Compose state. */
    fun isSpeaking(text: String): Boolean

    /** Start reading [text], or stop when it is already being read. */
    fun toggle(text: String)

    /** False on a host with no synthesiser wired — the affordance is hidden entirely. */
    val available: Boolean
}

/** "No voice here" — what a test that wants no read-aloud affordance at all provides. */
object NoReadAloud : ReadAloud {
    override fun isSpeaking(text: String): Boolean = false
    override fun toggle(text: String) = Unit
    override val available: Boolean get() = false
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

/**
 * Outbound (assistant) message — full-width prose, no card.
 * Footer: local time + one-tap copy + read-aloud.
 */
@Composable
fun AssistantMessage(
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
    ts: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    loadImage: (suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    /** The reply's media and files: between its text and its time row, so they read as one message. */
    media: (@Composable () -> Unit)? = null,
    /** Card #76: the live reply's unfinished reveal this stored reply takes over and runs to the end. */
    reveal: LiveRevealState? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        if (reveal != null) RevealDriver(reveal, text, done = true)
        // LinkSelectionContainer makes the prose selectable/copyable; links inside stay clickable, and a
        // right-click on one selects the whole link.
        LinkSelectionContainer {
            MarkdownBody(
                text = if (reveal == null) text else dev.supermux.chat.liveMarkdown(text.take(reveal.laidOut)),
                modifier = Modifier.fillMaxWidth(),
                onOpenFile = onOpenFile,
                linkify = true,
                onOpenUrl = onOpenUrl,
                loadImage = loadImage,
                reveal = reveal,
            )
        }
        media?.invoke()
        MessageMetaRow(text = text, ts = ts)
    }
}

/**
 * An agent reply that is its turn's error (the broker's `error: true` row): the reply's own look —
 * selectable, with its time / copy / read-aloud row — in the error colour, after an error mark.
 */
@Composable
fun AgentErrorMessage(
    entryId: String,
    text: String,
    onOpenFile: (FilePathRef) -> Unit = {},
    ts: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge
    val markSize = 16.dp
    // The mark sits on the first line's centre.
    val markTop = with(LocalDensity.current) {
        if (style.lineHeight.isSp) ((style.lineHeight.toDp() - markSize) / 2).coerceAtLeast(0.dp) else 2.dp
    }
    Column(Modifier.fillMaxWidth().testTag("agent-error:$entryId")) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = "Error",
                tint = cs.error,
                modifier = Modifier.padding(top = markTop).size(markSize),
            )
            LinkSelectionContainer(Modifier.weight(1f)) {
                MdText(text = mdAnnotated(text, onOpenFile, linkify = true), color = cs.error, style = style)
            }
        }
        MessageMetaRow(text = text, ts = ts)
    }
}

/** Compact time + copy + read-aloud under an agent reply. */
@Composable
private fun MessageMetaRow(text: String, ts: String?) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val readAloud = remember(platform) { PlatformReadAloud(platform) }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val speaking = readAloud.isSpeaking(text)
    // `nowMs` is read once per composition of the row: the label only has minute resolution and a
    // transcript row is recomposed whenever anything about it changes.
    val time = remember(ts) { chatTimeLabel(ts, Clock.System.now().toEpochMilliseconds()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (time != null) {
            Text(
                text = time,
                color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = Space.xs),
            )
        }
        IconButton(
            onClick = {
                platform.copyToClipboard(text)
                copied = true
                scope.launch {
                    delay(1500)
                    copied = false
                }
            },
            modifier = Modifier
                .size(28.dp)
                .testTag("message_copy"),
        ) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy response",
                tint = if (copied) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (readAloud.available) {
            IconButton(
                onClick = { readAloud.toggle(text) },
                modifier = Modifier
                    .size(28.dp)
                    .testTag("message_read_aloud"),
            ) {
                Icon(
                    imageVector = if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (speaking) "Stop reading" else "Read aloud",
                    tint = if (speaking) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Inbound (user) message — right-aligned chat bubble, capped at ~75% width.
 * Short text hugs content; long text wraps inside the cap. No gutter, no label.
 */
@Composable
fun UserMessage(text: String) {
    val cs = MaterialTheme.colorScheme
    // Tail sits on the bottom-end (right) so it reads as a sent bubble.
    val bubbleShape = RoundedCornerShape(
        topStart = Radii.md,
        topEnd = Radii.md,
        bottomStart = Radii.md,
        bottomEnd = 4.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.75f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .wrapContentWidth()
                    .clip(bubbleShape)
                    .background(cs.surfaceContainer.copy(alpha = 0.92f))
                    .border(1.dp, cs.outlineVariant, bubbleShape)
                    .padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                LinkSelectionContainer {
                    MdText(
                        text = mdAnnotated(text),
                        color = cs.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** Strip an mcp__server__tool name down to its last segment. */
private fun toolLabel(tool: String): String =
    if (tool.startsWith("mcp__")) tool.substringAfterLast("__") else tool

/**
 * Tool-use activity.
 * Medium: quiet inline row. High: terminal pane for Bash, diff pane for Edit/Write.
 */
@Composable
fun ToolCard(
    event: ActivityEvent,
    status: ToolStatus,
    output: String? = null,
    resultBody: ActivityToolBody? = null,
    highDetail: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    val input = event.detail
    val toolName = event.tool ?: "tool"
    val bash = if (highDetail) resolveBashParts(event.body, resultBody, input, output, toolName) else null
    val edit = if (highDetail) resolveEditParts(event.body, input, toolName) else null

    if (bash != null) {
        ToolTerminalPane(
            command = bash.command,
            output = bash.output,
            exitCode = bash.exitCode,
            description = event.description,
            status = status,
            truncated = event.truncated == true,
        )
        return
    }
    if (edit != null) {
        ToolDiffPane(
            path = edit.path,
            mode = edit.mode,
            diff = edit.diff,
            content = edit.content,
            description = event.description,
            status = status,
            truncated = event.truncated == true,
        )
        return
    }

    val hasContent = !input.isNullOrBlank() || !output.isNullOrBlank()
    val verb = toolLabel(toolName)
    val primary = event.description
        ?: event.title?.let { t -> event.tool?.let { t.removePrefix("$it: ") } ?: t }

    Column(Modifier.fillMaxWidth().testTag("tool_card")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasContent) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == ToolStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    strokeWidth = 1.5.dp,
                )
            } else {
                Text(
                    text = "·",
                    color = if (status == ToolStatus.ERROR) cs.error.copy(alpha = 0.7f)
                    else cs.onSurfaceVariant.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.width(Space.xs + 2.dp))
            Text(
                text = verb,
                color = cs.onSurfaceVariant.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!primary.isNullOrBlank()) {
                Text(
                    text = primary,
                    color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (status == ToolStatus.ERROR) {
                Text(
                    text = "failed",
                    color = cs.error.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                Modifier
                    .padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.25f),
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(start = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                input?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                output?.takeIf { it.isNotBlank() }?.let {
                    if (looksLikeDiff(it)) InlineDiff(it)
                    else Text(
                        text = it + if (event.truncated == true) " …" else "",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (status == ToolStatus.ERROR) cs.error.copy(alpha = 0.8f)
                        else cs.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTerminalPane(
    command: String?,
    output: String?,
    exitCode: Int?,
    description: String?,
    status: ToolStatus,
    truncated: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val statusLabel = when {
        status == ToolStatus.RUNNING -> "running"
        status == ToolStatus.ERROR && exitCode != null -> "exit $exitCode"
        status == ToolStatus.ERROR -> "error"
        exitCode != null -> "exit $exitCode"
        else -> "done"
    }
    val statusColor = when (status) {
        ToolStatus.RUNNING -> cs.tertiary
        ToolStatus.ERROR -> cs.error
        ToolStatus.DONE -> cs.primary.copy(alpha = 0.85f)
    }
    // A terminal stays dark in a light app: the editor's dark palette, whatever the app's theme.
    val font = packagedEditorFontFamily()
    val terminal = remember(font) { EditorTheme.dark(font) }
    val bash = LocalPlatform.current.editorSyntax.registry.aliasFor("bash")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(Color(0xFF0C0C0E))
            .border(0.5.dp, cs.outline.copy(alpha = 0.35f), RoundedCornerShape(Radii.sm))
            .testTag("tool_terminal"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = Space.sm + 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", color = Color(0xFFFF5F57), fontSize = 8.sp)
            Text(" ●", color = Color(0xFFFEBC2E).copy(alpha = 0.85f), fontSize = 8.sp)
            Text(" ●", color = Color(0xFF28C840).copy(alpha = 0.85f), fontSize = 8.sp)
            Spacer(Modifier.width(Space.sm))
            Text("terminal", color = Color(0xFFA1A1AA), fontSize = 11.sp)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = Color(0xFFD4D4D8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            } else Spacer(Modifier.weight(1f))
            if (status == ToolStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = statusColor,
                    strokeWidth = 1.2.dp,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(statusLabel, color = statusColor, fontSize = 10.sp, fontFamily = MonoFontFamily)
        }
        Column(
            Modifier.padding(horizontal = Space.sm + 2.dp, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!command.isNullOrBlank()) {
                Row {
                    Text("$ ", color = Color(0xFF4ADE80).copy(alpha = 0.9f), fontFamily = MonoFontFamily, fontSize = 12.sp, lineHeight = 18.sp)
                    ToolCodeEditor(
                        text = command,
                        language = bash,
                        label = "Command",
                        theme = terminal,
                        toggleColor = Color(0xFF71717A),
                        maxLines = TERMINAL_COMMAND_LINES,
                        tag = "tool_terminal_command",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!output.isNullOrBlank()) {
                ToolCodeEditor(
                    text = output,
                    language = null,
                    label = "Output",
                    theme = if (status == ToolStatus.ERROR) terminal.copy(foreground = Color(0xFFFCA5A5)) else terminal,
                    toggleColor = Color(0xFF71717A),
                    tag = "tool_terminal_output",
                )
                if (truncated) Text("… truncated", color = Color(0xFF71717A), fontSize = 11.sp)
            } else if (status == ToolStatus.RUNNING && command.isNullOrBlank()) {
                Text("Running…", color = Color(0xFF71717A), fontSize = 11.sp)
            }
        }
    }
}

/** A Bash command shows this many lines before "Show all" (a heredoc can be long). */
private const val TERMINAL_COMMAND_LINES = 8

@Composable
private fun ToolDiffPane(
    path: String,
    mode: String?,
    diff: String?,
    content: String?,
    description: String?,
    status: ToolStatus,
    truncated: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val modeLabel = when (mode?.lowercase()) {
        "add", "added" -> "added"
        "delete", "deleted" -> "deleted"
        "move", "renamed" -> "moved"
        else -> "edited"
    }
    val statusLabel = when (status) {
        ToolStatus.RUNNING -> "applying"
        ToolStatus.ERROR -> "error"
        ToolStatus.DONE -> "done"
    }
    val statusColor = when (status) {
        ToolStatus.RUNNING -> cs.tertiary
        ToolStatus.ERROR -> cs.error
        ToolStatus.DONE -> cs.primary.copy(alpha = 0.85f)
    }
    val language = LocalPlatform.current.editorSyntax.languageFor(path)
    // A written file is its content (no all-green "+" column); an edit is its diff as a document.
    val diffDoc = remember(diff, content) { if (content == null) diff?.let(::toolDiffDoc) else null }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(cs.surfaceContainerLowest)
            .border(0.5.dp, cs.outline.copy(alpha = 0.35f), RoundedCornerShape(Radii.sm))
            .testTag("tool_diff"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainer)
                .padding(horizontal = Space.sm + 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = path,
                color = cs.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = cs.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            }
            Spacer(Modifier.width(Space.xs))
            Text(modeLabel, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 10.sp)
            Spacer(Modifier.width(Space.xs))
            Text(statusLabel, color = statusColor, fontSize = 10.sp)
        }
        val text = content ?: diffDoc?.text
        if (!text.isNullOrBlank()) {
            Column(Modifier.padding(vertical = Space.xs)) {
                ToolCodeEditor(
                    text = text,
                    language = language,
                    label = pathLeaf(path),
                    theme = rememberAppEditorTheme(),
                    toggleColor = cs.primary,
                    lineKinds = diffDoc?.kinds,
                    tag = "tool_diff_editor",
                    modifier = Modifier.padding(end = Space.sm),
                )
                if (truncated) {
                    Text("… truncated", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(horizontal = Space.sm))
                }
            }
        } else {
            Text(
                text = if (status == ToolStatus.RUNNING) "Preparing edit…" else "No diff content",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(Space.sm),
            )
        }
    }
}

/** True when [text] reads as a unified diff (hunk header or several ± lines). */
internal fun looksLikeDiff(text: String): Boolean {
    val lines = text.lineSequence().take(40).toList()
    if (lines.any { it.startsWith("@@ ") || it.startsWith("diff --git") }) return true
    val pm = lines.count { (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---")) }
    return pm >= 3
}

/** Inline unified-diff renderer: mono, semantic add/remove tints, horizontal scroll. */
@Composable
private fun InlineDiff(text: String) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(cs.surfaceContainerLowest)
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Space.xs),
    ) {
        text.lineSequence().forEach { line ->
            val add = line.startsWith("+") && !line.startsWith("+++")
            val del = line.startsWith("-") && !line.startsWith("---")
            val hunk = line.startsWith("@@")
            val fg = when { add -> sem.success; del -> sem.danger; hunk -> cs.primary; else -> cs.onSurfaceVariant }
            val bg = when { add -> sem.success.copy(alpha = 0.10f); del -> sem.danger.copy(alpha = 0.10f); else -> Color.Transparent }
            Text(
                text = line.ifEmpty { " " },
                fontFamily = MonoFontFamily,
                fontSize = 12.5.sp,
                lineHeight = 18.5.sp,
                color = fg,
                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = Space.md, vertical = 0.5.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Chat timeline layout
// ---------------------------------------------------------------------------

/** Dispatches a single TimelineItem into the chat stream. */
@Composable
fun TimelineItemRow(
    item: TimelineItem,
    media: AttachmentMedia = AttachmentMedia.None,
    onOpenFile: (FilePathRef) -> Unit = {},
    highDetail: Boolean = false,
    onOpenWalkthrough: () -> Unit = {},
    /** Expansion + actions for subagent cards; null = local expansion, no actions (previews). */
    subagentUi: SubagentUi? = null,
    /** Whether the app lists this session, so an inbox message's sender opens its chat on a tap. */
    canOpenChat: (sessionId: String) -> Boolean = { false },
    /** Ends a council (the council card's Stop); null = no Stop. */
    endCouncil: ((councilId: String) -> Unit)? = null,
) {
    when (item) {
        is TimelineItem.Msg -> {
            val text = item.entry.text
            val atts = item.entry.attachments
            val isUser = item.entry.direction == "inbound"
            // Another session (or supermux, or a council) sent it: inbound, but not the user's (decision #19).
            val inbox = item.entry.channel == "inbox"
            // An agent's reply carries its files between its text and its time row.
            val filesInReply = !text.isNullOrBlank() && !atts.isNullOrEmpty() && messageBody(item.entry) == MessageBody.AgentReply
            if (!text.isNullOrBlank() || !atts.isNullOrEmpty()) {
                // Media drawn anywhere in this row (attachments, a reply's `![](…)`) name themselves
                // in the chat's viewer by this entry.
                CompositionLocalProvider(LocalChatMediaEntry provides item.entry.id) {
                    Column(
                        // The row container carries the tag so the text and any attachments are INSIDE
                        // it: on Compose-for-Web the tag becomes this element's DOM `id`, and a journey
                        // asserts `[id^="chat-message:outbound:"]` has the reply's text within it.
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.sm)
                            .testTag(TestIds.chatMessage(item.entry.direction, item.entry.id)),
                        horizontalAlignment = if (isUser && !inbox) Alignment.End else Alignment.Start,
                    ) {
                        if (!text.isNullOrBlank()) {
                            when (messageBody(item.entry)) {
                                MessageBody.Walkthrough -> WalkthroughReadyCard(text, onOpenWalkthrough)
                                MessageBody.SupermuxNotice -> SupermuxNoticeRow(item.entry.id, text)
                                MessageBody.Inbox ->
                                    InboxMessageCard(item.entry, onOpenFile = onOpenFile, subagentUi = subagentUi, canOpenChat = canOpenChat)
                                MessageBody.ToSubagent -> {
                                    // A message to a subagent: a compact marker that opens its card.
                                    val toSubagent = item.entry.subagent_id.orEmpty()
                                    SubagentMarker(
                                        entryId = item.entry.id,
                                        text = text,
                                        name = subagentUi?.nameOf?.invoke(toSubagent),
                                        onOpen = subagentUi?.let { ui -> { ui.open(toSubagent) } },
                                    )
                                }
                                MessageBody.User -> UserMessage(text)
                                MessageBody.AccountNotice -> dev.supermux.ui.accounts.AccountNoticeRow(text)
                                MessageBody.AgentError ->
                                    AgentErrorMessage(item.entry.id, text, onOpenFile = onOpenFile, ts = item.entry.ts)
                                MessageBody.AgentReply -> AssistantMessage(
                                    text,
                                    onOpenFile = onOpenFile,
                                    ts = item.entry.ts,
                                    media = if (!filesInReply) null else {
                                        { AttachmentList(atts.orEmpty(), alignEnd = false, media = media, entryId = item.entry.id) }
                                    },
                                    reveal = LocalLiveReveals.current?.unfinished(item.entry.stream),
                                )
                            }
                        }
                        if (!atts.isNullOrEmpty() && !filesInReply) {
                            AttachmentList(atts, alignEnd = isUser && !inbox, media = media, entryId = item.entry.id)
                        }
                    }
                }
            }
        }
        is TimelineItem.Tool -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.xs),
            ) {
                ToolCard(
                    event = item.event,
                    status = item.status,
                    output = item.output,
                    resultBody = item.resultBody,
                    highDetail = highDetail,
                )
            }
        }
        is TimelineItem.Activity -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.xs),
            ) {
                ActivityKindCard(item.event)
            }
        }
        is TimelineItem.SubagentCard -> {
            val id = item.subagent.id
            var localOpen by remember(id) { mutableStateOf(false) }
            SubagentCard(
                item = item,
                expanded = subagentUi?.isExpanded?.invoke(id) ?: localOpen,
                onToggle = { if (subagentUi != null) subagentUi.onToggle(id) else localOpen = !localOpen },
                actions = subagentUi?.actions ?: SubagentActions(),
                media = media,
                onOpenFile = onOpenFile,
                highDetail = highDetail,
                unread = subagentUi?.unread?.invoke(item.subagent) ?: 0,
            )
        }
        is TimelineItem.CouncilCard -> CouncilCard(item, onOpenFile = onOpenFile, endCouncil = endCouncil)
    }
}

@Composable
fun ActivityKindCard(event: ActivityEvent) {
    when (event.kind) {
        "reasoning" -> ReasoningCard(event)
        "plan" -> PlanCard(event)
        // Agent-kind tasks are subagents, which have their own card now (S3).
        "task" -> if (!isAgentTask(event)) TaskCard(event)
        else -> {}
    }
}

/** `agent` / `subagent` / `collab` task rows (the broker writes "<kind> <phase>" in detail). */
internal fun isAgentTask(event: ActivityEvent): Boolean {
    val kind = event.taskKind ?: event.detail?.substringBefore(' ')
    return kind == "agent" || kind == "subagent" || kind == "collab"
}

@Composable
private fun ReasoningCard(event: ActivityEvent) {
    val cs = MaterialTheme.colorScheme
    // A redacted row only reaches here while it is the live tail of a running turn (mergeTimeline
    // drops it once anything follows), so it is a spinner, not a "(redacted)" label.
    if (event.isRedactedReasoning) {
        Row(
            Modifier.fillMaxWidth().testTag("activity-reasoning").padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CircularProgressIndicator(Modifier.size(12.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
            Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val canExpand = !event.detail.isNullOrBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("activity-reasoning")
            .clickable(enabled = canExpand) { expanded = !expanded }
            .padding(vertical = 2.dp),
    ) {
        Text(
            "Thinking…",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        AnimatedVisibility(visible = expanded && canExpand) {
            Text(
                event.detail.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

@Composable
private fun PlanCard(event: ActivityEvent) {
    val cs = MaterialTheme.colorScheme
    val lines = event.detail.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    Column(Modifier.fillMaxWidth().testTag("activity-plan").padding(vertical = 2.dp)) {
        Text(event.title ?: "Plan", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        lines.forEachIndexed { i, line ->
            val text = line.replace(Regex("^(pending|completed|in_progress|failed):\\s*"), "")
            Text("${i + 1}. $text", style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
        }
    }
}

@Composable
private fun TaskCard(event: ActivityEvent) {
    val cs = MaterialTheme.colorScheme
    val status = when {
        event.phase == "failed" || event.title.equals("error", ignoreCase = true) -> "failed"
        event.phase == "started" -> "started"
        else -> "completed"
    }
    val pillColor = when (status) {
        "failed" -> cs.error
        "started" -> cs.tertiary
        else -> cs.primary
    }
    Row(
        Modifier.fillMaxWidth().testTag("activity-task").padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(event.title ?: event.taskKind ?: "task", style = MaterialTheme.typography.labelMedium)
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = pillColor,
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.sm))
                .background(pillColor.copy(alpha = 0.15f))
                .padding(horizontal = Space.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun WalkthroughReadyCard(text: String, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md))
            .background(cs.primaryContainer.copy(alpha = 0.55f))
            .clickable(onClick = onOpen)
            .padding(Space.md)
            .testTag("walkthrough_ready_card"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
        Text("Open walkthrough →", style = MaterialTheme.typography.labelMedium, color = cs.primary)
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

/**
 * Renders a message's attachments below its text, aligned to the sender side. Two or more
 * pictures/clips become one [MediaCollage] (the message's other files still follow, as chips); one
 * keeps the single inline view. [entryId] names the message, so a tap opens the chat's viewer at
 * the right item.
 */
@Composable
fun AttachmentList(
    attachments: List<Attachment>,
    alignEnd: Boolean,
    media: AttachmentMedia,
    entryId: String = "",
) {
    val mediaItems = remember(attachments, entryId) {
        attachments
            .mapNotNull { att -> attachmentMediaKind(att)?.let { ChatMediaItem.FromAttachment(entryId, att, it) } }
            .distinctBy { it.key }
    }
    WithMessageViewer(mediaItems, media) { open ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (mediaItems.size >= 2) {
                MediaCollage(mediaItems, media, onOpen = open)
                for (att in attachments) if (attachmentMediaKind(att) == null) AttachmentItem(att, alignEnd, media, entryId, open)
            } else {
                for (att in attachments) AttachmentItem(att, alignEnd, media, entryId, open)
            }
        }
    }
}

/**
 * How a message's media open: the chat's viewer when this row sits in a chat whose set holds them,
 * else a viewer over just this message's [items] (an archived transcript, a preview) — the same
 * viewer either way.
 */
@Composable
private fun WithMessageViewer(
    items: List<ChatMediaItem.FromAttachment>,
    media: AttachmentMedia,
    content: @Composable (open: (ChatMediaItem) -> Unit) -> Unit,
) {
    val chat = LocalChatMediaViewer.current
    var localKey by remember(items) { mutableStateOf<String?>(null) }
    content { item -> if (chat?.open(item.key) != true) localKey = item.key }
    localKey?.let { key ->
        ChatMediaViewer(
            items = items,
            startKey = key,
            resolve = { attachmentHandle(it as ChatMediaItem.FromAttachment, media) },
            onDismiss = { localKey = null },
        )
    }
}

@Composable
private fun AttachmentItem(
    att: Attachment,
    alignEnd: Boolean,
    media: AttachmentMedia,
    entryId: String,
    open: (ChatMediaItem) -> Unit,
) {
    when (attachmentMediaKind(att)) {
        FilePreviewKind.Image -> {
            InlineImageAttachment(att, alignEnd, media, onOpen = { open(ChatMediaItem.FromAttachment(entryId, att, FilePreviewKind.Image)) })
            return
        }
        FilePreviewKind.Video -> {
            InlineVideo(att, media, onOpenViewer = { open(ChatMediaItem.FromAttachment(entryId, att, FilePreviewKind.Video)); true })
            return
        }
        null -> Unit
    }
    val isAudio = att.kind == "voice" || att.kind == "audio" || (att.mime ?: "").startsWith("audio/")
    val label = when {
        isAudio -> att.name ?: "voice message"
        else -> att.name ?: att.file_id
    }
    val icon = when {
        isAudio -> MediaIcons.Volume
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    AttachmentFileCard(icon, label, att, media)
}

/**
 * Inline preview for image attachments, loaded and decoded by [rememberAttachmentImage].
 *
 * Painted at natural size, shrunk-only to fit the column and [MdImageDimens.MaxHeight] — the same
 * rule as markdown images, so a 32×32 icon is not blown up to a 280 dp blob. A tap opens the chat's
 * fullscreen viewer at this picture ([onOpen]), whose picture page is the lightbox's zoom/pan/save.
 * A load or decode failure falls back to the ordinary [AttachmentFileCard] so the user keeps the
 * retry + save path.
 */
@Composable
private fun InlineImageAttachment(
    att: Attachment,
    alignEnd: Boolean,
    media: AttachmentMedia,
    onOpen: () -> Unit,
) {
    when (val image = rememberAttachmentImage(att, media)) {
        AttachmentImage.Failed -> AttachmentFileCard(Icons.Filled.Image, att.name ?: "image", att, media)
        AttachmentImage.Loading -> {
            val width = att.width
            val height = att.height
            if (width != null && height != null && width > 0 && height > 0) {
                // The broker read the header: the placeholder takes the picture's own box, so the
                // row does not jump when the bitmap lands.
                PictureFrame(width, height, alignEnd = alignEnd, tag = "attachment_image_loading") { modifier, _, _ ->
                    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.size(MdImageDimens.SpinnerSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            strokeWidth = MdImageDimens.SpinnerStroke,
                        )
                    }
                }
            } else {
                Box(Modifier.mediaColumnWidth()) { MdImageLoadingBox("attachment_image_loading", Radii.md) }
            }
        }
        is AttachmentImage.Ready -> PictureFrame(
            width = image.decoded.width,
            height = image.decoded.height,
            alignEnd = alignEnd,
            tag = "attachment_image",
            onClick = onOpen,
        ) { modifier, scale, alignment ->
            Image(
                painter = image.decoded.painter,
                contentDescription = att.name ?: "image",
                contentScale = scale,
                alignment = alignment,
                modifier = modifier,
            )
        }
    }
}

/**
 * One picture in a message, in its [pictureFrame]: the media column's width at most (a collage's),
 * [Media.inlineImageMaxHeight] tall, never bigger than the picture, and past 1:2 or 5:2 cut to the
 * frame, a long screenshot to its top and a panorama to its middle. [content] draws into the frame
 * with the scale and alignment that cut needs.
 */
@Composable
private fun PictureFrame(
    width: Int,
    height: Int,
    alignEnd: Boolean,
    tag: String,
    onClick: (() -> Unit)? = null,
    content: @Composable (modifier: Modifier, scale: ContentScale, alignment: Alignment) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart) {
        val column = minOf(maxWidth * Media.inlineVideoWidthFraction, Media.collageMaxWidth)
        val frame = pictureFrame(width, height, column, Media.inlineImageMaxHeight, LocalDensity.current.density)
        var modifier = Modifier.size(frame.width, frame.height).clip(RoundedCornerShape(Radii.md))
        if (onClick != null) modifier = modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
        content(
            modifier.testTag(tag),
            if (frame.crop == null) ContentScale.Fit else ContentScale.Crop,
            if (frame.crop == PictureCrop.Top) Alignment.TopCenter else Alignment.Center,
        )
    }
}

/** Coil memory-cache key of an attachment's decode. File ids are unique and never rewritten. */
internal fun attachmentImageKey(att: Attachment): String = "att:${att.file_id}"

/** A picture attachment on its way to the screen. */
internal sealed interface AttachmentImage {
    data object Loading : AttachmentImage

    data object Failed : AttachmentImage

    class Ready(val decoded: DecodedImage.Ready, val bytes: ByteArray) : AttachmentImage
}

/**
 * Load and decode a picture attachment — the ONE path the timeline row, the collage tile, the
 * viewer's page and its strip all take. Bytes come from the [media] seam (the broker file fetch,
 * through the media cache) and decode through Coil, so the same path runs on every host.
 *
 * A row scrolled back into view paints in its first frame: the bytes come from the cache's memory
 * tier synchronously ([AttachmentMedia.peek]) and the decoded bitmap from Coil's memory cache under
 * [memoryCacheKey] — no spinner, no jump. A cached copy that will not decode is fetched again once
 * before this reports [AttachmentImage.Failed].
 */
@Composable
internal fun rememberAttachmentImage(
    att: Attachment,
    media: AttachmentMedia,
    memoryCacheKey: String? = attachmentImageKey(att),
): AttachmentImage {
    var bytes by remember(att.file_id) { mutableStateOf(media.peek(att)) }
    var fetchFailed by remember(att.file_id) { mutableStateOf(false) }
    var refetched by remember(att.file_id) { mutableStateOf(false) }
    LaunchedEffect(att.file_id, refetched) {
        if (bytes != null) return@LaunchedEffect
        val loaded = runCatching { media.bytes(att, refresh = refetched) }.getOrNull()
        if (loaded == null) fetchFailed = true else bytes = loaded
    }
    if (fetchFailed) return AttachmentImage.Failed
    val data = bytes ?: return AttachmentImage.Loading
    return when (val decoded = rememberDecodedImage(data, memoryCacheKey = memoryCacheKey)) {
        DecodedImage.Loading -> AttachmentImage.Loading
        DecodedImage.Failed -> if (refetched) {
            AttachmentImage.Failed
        } else {
            // A cached copy that no longer decodes: fetch a fresh one once before giving up.
            LaunchedEffect(data) {
                bytes = null
                refetched = true
            }
            AttachmentImage.Loading
        }
        is DecodedImage.Ready -> AttachmentImage.Ready(decoded, data)
    }
}

/**
 * The lightbox's zoom/pan state, hoisted out of the composable so the clamping rules are unit
 * testable and a UI test can assert what a wheel, a drag or a button actually did.
 *
 * Clamping is what makes panning usable rather than a way to fling the picture off-screen: the
 * offset can never exceed the overflow the current [scale] creates, so at 1× the image is pinned
 * and at 3× it can be dragged to any edge but no further.
 */
@Stable
internal class LightboxTransform {
    var scale by mutableFloatStateOf(MIN)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The viewport in px, from the backdrop's own layout — the clamp needs it. */
    var viewport by mutableStateOf(androidx.compose.ui.geometry.Size.Zero)

    /**
     * The content's unscaled size in px when it is smaller than [viewport] and centred in it (an
     * inline diagram narrower than the chat column), so a zoom can spill into the spare room while
     * the pan still stops at the content's edges. Null means the content fills the viewport.
     */
    var content by mutableStateOf<androidx.compose.ui.geometry.Size?>(null)

    fun zoomBy(factor: Float) = scaleTo(scale * factor)

    /**
     * Zoom keeping the content under [focus] (viewport px, from its top-left) where it is — a pinch
     * centroid or the cursor — rather than about the centre, so what you aim at stays put.
     */
    fun zoomBy(factor: Float, focus: Offset) {
        val next = (scale * factor).coerceIn(MIN, MAX)
        val ratio = next / scale
        val fromCentre = focus - Offset(viewport.width / 2f, viewport.height / 2f)
        scale = next
        offset = clamp(fromCentre - (fromCentre - offset) * ratio)
    }

    fun scaleTo(value: Float) {
        scale = value.coerceIn(MIN, MAX)
        offset = clamp(offset)
    }

    fun panBy(pan: Offset) {
        offset = clamp(offset + pan)
    }

    /** Double-click / double-tap: snap between fit and a 2× look, recentring on the way out. */
    fun toggleZoom() {
        scaleTo(if (scale > MIN) MIN else DOUBLE_TAP)
    }

    /** As [toggleZoom], but zooming in on the tapped point. */
    fun toggleZoom(focus: Offset) {
        if (scale > MIN) scaleTo(MIN) else zoomBy(DOUBLE_TAP / scale, focus)
    }

    private fun clamp(candidate: Offset): Offset {
        val c = content ?: viewport
        val maxX = ((c.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((c.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
        // `+ 0f` turns a clamped -0.0 into 0.0: Offset compares packed bits, so -0.0 != Offset.Zero.
        return Offset(candidate.x.coerceIn(-maxX, maxX) + 0f, candidate.y.coerceIn(-maxY, maxY) + 0f)
    }

    companion object {
        const val MIN = 1f
        const val MAX = 5f
        const val DOUBLE_TAP = 2f

        /** One wheel notch / one button press. */
        const val STEP = 1.25f
    }
}

/**
 * Fullscreen image lightbox: black backdrop, fit-scaled image, zoom + pan, download and close.
 *
 * It opens on both hosts (cluster D2), so every gesture has a mouse equivalent — pinch alone would
 * leave a desktop user with an inert full-screen picture, and desktop's old "open the original in
 * the OS viewer" is now the download button rather than the only way to see it bigger:
 *  - wheel scroll zooms, pinch zooms, `+` / `−` zoom
 *  - drag pans (one finger or the mouse), clamped to the image's own bounds
 *  - double-click / double-tap toggles fit ↔ 2×
 *
 * A Dialog with `usePlatformDefaultWidth = false` is the idiomatic fullscreen overlay, and
 * predictive-back dismisses it for free on Android.
 */
@Composable
internal fun ImageLightbox(
    painter: androidx.compose.ui.graphics.painter.Painter,
    name: String,
    mime: String?,
    bytes: ByteArray?,
    onDismiss: () -> Unit,
    transform: LightboxTransform = remember { LightboxTransform() },
) {
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { transform.viewport = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
                // Separate pointerInput blocks, not one: a single detector cannot both consume the
                // scroll wheel and arbitrate taps against drags.
                .lightboxWheelZoom(transform)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { transform.toggleZoom() })
                }
                .pointerInput(Unit) {
                    // Pinch on touch, single-pointer drag everywhere — the clamp decides how far.
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) transform.zoomBy(zoom)
                        transform.panBy(pan)
                    }
                }
                .testTag("image_lightbox"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offset.x
                        translationY = transform.offset.y
                    },
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                LightboxButton(
                    icon = MediaIcons.ZoomOut,
                    label = "Zoom out",
                    tag = "image_lightbox_zoom_out",
                    onClick = { transform.zoomBy(1f / LightboxTransform.STEP) },
                )
                LightboxButton(
                    icon = MediaIcons.ZoomIn,
                    label = "Zoom in",
                    tag = "image_lightbox_zoom_in",
                    onClick = { transform.zoomBy(LightboxTransform.STEP) },
                )
                if (bytes != null) {
                    LightboxButton(
                        icon = MediaIcons.Save,
                        label = "Download",
                        tag = "image_lightbox_download",
                        onClick = { scope.launch { saveOrOpenAttachment(platform, pointer, name, mime, bytes) } },
                    )
                }
                LightboxButton(
                    icon = MediaIcons.Close,
                    label = "Close",
                    tag = "image_lightbox_close",
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun LightboxButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag(tag),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White)
    }
}

/**
 * Inline video playback.
 *
 * The player is Compose Media Player (MIT) on every host now — Android's old media3 player (a
 * platform view with its own controls) is gone, so a clip looks and behaves the same everywhere.
 * It decodes into a Compose `Canvas`, so it composes inside the scrolling message list.
 *
 * Nothing is fetched until the user opts into playback — a transcript must never eagerly download
 * every clip. The media cache's own file then plays in place ([AttachmentMedia.playableUri]): it was
 * downloaded straight to disk, so a long clip never passes through memory. A media that downloads
 * files and has none for it shows the file card ([resolvePlayableClip]); one without downloads (a
 * preview's in-memory media) stages the bytes through `Platform.files.stageTemp` as before, so the
 * native backend can open them by URI. A failed download, or a backend error, falls back to the
 * ordinary [AttachmentFileCard] so save + the OS player stay reachable.
 *
 * [onOpenViewer] opens the clip in the chat's fullscreen viewer (the ⤢ button; the inline player
 * pauses first); false — or no viewer at all — keeps the old "open in the system player", which
 * hands over the clip's FILE through the fleet's downloads ([startDownload]).
 *
 * [renderPlayer] is the mount seam: tests inject a stub so the suite never loads native media
 * libraries in a headless worker.
 */
@Composable
internal fun InlineVideo(
    att: Attachment,
    media: AttachmentMedia,
    onOpenViewer: (() -> Boolean)? = null,
    renderPlayer: @Composable (uri: String, onExternal: () -> Unit, onError: () -> Unit) -> Unit =
        { uri, onExternal, onError ->
            MountedVideoPlayer(uri, onError = onError, onExternal = onExternal, expandsInViewer = onOpenViewer != null)
        },
) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val scope = rememberCoroutineScope()
    val downloads = rememberDownloads()
    var playing by remember(att.file_id) { mutableStateOf(false) }
    var uri by remember(att.file_id) { mutableStateOf<String?>(null) }
    var staged by remember(att.file_id) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(att.file_id) { mutableStateOf(false) }

    LaunchedEffect(att.file_id, playing) {
        if (!playing || uri != null || failed) return@LaunchedEffect
        val clip = resolvePlayableClip(att, media, platform)
        if (clip == null) {
            failed = true
        } else {
            staged = clip.staged
            uri = clip.uri
        }
    }

    val located = uri
    val surface = Modifier
        .fillMaxWidth(Media.inlineVideoWidthFraction)
        .heightIn(max = Media.inlineVideoMaxHeight)
    when {
        failed -> AttachmentFileCard(Icons.Filled.Movie, att.name ?: "video", att, media)
        !playing -> Box(
            modifier = surface
                .height(Media.inlineVideoMaxHeight)
                .clip(RoundedCornerShape(Radii.md))
                .background(cs.surfaceContainer)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { playing = true }
                .testTag("attachment_video_poster"),
            contentAlignment = Alignment.Center,
        ) {
            // The phone's poster when it sent one (a small picture — the clip is still not fetched),
            // else the frame the viewer kept when this clip last played.
            att.poster_file_id?.let { PosterPicture(it, media, Modifier.fillMaxSize().testTag("attachment_video_poster_picture")) }
            if (att.poster_file_id == null) {
                ClipFrames.of(att.file_id)?.let {
                    Image(BitmapPainter(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Icon(
                imageVector = MediaIcons.Play,
                contentDescription = att.name ?: "Play video",
                tint = if (att.poster_file_id != null) VideoControls.Foreground else cs.onSurface,
                modifier = Modifier.size(Sizes.videoPlayGlyph),
            )
            att.duration_ms?.let {
                ClipDurationBadge(it, Modifier.align(Alignment.BottomEnd).padding(Space.sm).testTag("attachment_video_duration"))
            }
        }
        located == null -> Box(
            modifier = surface
                .height(Media.inlineVideoMaxHeight)
                .clip(RoundedCornerShape(Radii.md))
                .background(cs.surfaceContainer)
                .testTag("attachment_video_loading"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                Modifier.size(MdImageDimens.SpinnerSize),
                color = cs.onSurfaceVariant,
                strokeWidth = MdImageDimens.SpinnerStroke,
            )
        }
        else -> Box(surface) {
            renderPlayer(
                located,
                {
                    if (onOpenViewer?.invoke() != true) {
                        scope.launch {
                            // The clip's FILE, through the fleet's downloads: leaving the chat lets go of it, and it waits in the bar.
                            if (startDownload(downloads, media, att, platform, pointer)) return@launch
                            // No download here (a preview): the staged bytes, else read them now.
                            val body = staged ?: runCatching { media.bytes(att) }.getOrNull()
                            if (body != null) saveOrOpenAttachment(platform, pointer, att.name ?: "video", att.mime, body)
                        }
                    }
                },
                { failed = true },
            )
        }
    }
}

/** A URI the platform's player opens, and the bytes behind it when they had to be staged. */
internal class PlayableClip(val uri: String, val staged: ByteArray?)

/**
 * Where a clip plays from: the media cache's own file first (downloaded straight to disk, played in
 * place — a long clip never passes through memory). A media that downloads files (#95: a host with a
 * file store, the web's browser download) plays from that file or not at all: no file is a failure,
 * and the caller shows the file card. Its transfer is the one a download of the same file shares, so
 * after the user cancelled that download in the bar, reading the bytes here would fetch the whole
 * clip again, into memory. Without a download (a preview's in-memory media) the bytes are staged
 * through `Platform.files.stageTemp`. Null when nothing works.
 */
internal suspend fun resolvePlayableClip(att: Attachment, media: AttachmentMedia, platform: Platform): PlayableClip? {
    runCatching { media.playableUri(att) }.getOrNull()?.let { return PlayableClip(it, staged = null) }
    if (media.downloadKey(att) != null) return null
    val bytes = runCatching { media.bytes(att) }.getOrNull() ?: return null
    // Name by the unique file_id so two clips never collide in the staging dir, and keep the
    // original extension — the native backends pick their demuxer from it.
    // platform-ok: a file id, not a path
    val name = attachmentTempName("video_${att.file_id.substringAfterLast('/')}", att.name, "mp4")
    val located = runCatching { platform.files.stageTemp(name, bytes) }.getOrNull() ?: return null
    return PlayableClip(located, staged = bytes)
}

/**
 * Name a staged attachment file. [base] names it (so an OS viewer's title bar is meaningful, and so
 * two clips keyed by file_id cannot collide) and the extension comes from [extensionFrom], falling
 * back to [defaultExt] — both OS viewers and the video backends pick their decoder from the suffix.
 */
internal fun attachmentTempName(base: String, extensionFrom: String?, defaultExt: String): String {
    val leaf = fileNameOf(base)
    val safeBase = leaf.substringBeforeLast('.', leaf).take(64).ifBlank { "file" }
    val ext = (extensionFrom ?: "").substringAfterLast('.', "").ifBlank { defaultExt }
    return "$safeBase.$ext"
}

/**
 * Transport surface the video controls draw against. Extracted from the player state so the control
 * bar is a pure composable that can be driven by a fake in tests — mounting the real player would
 * load AVFoundation/GStreamer/MediaFoundation inside a headless worker.
 */
@Stable
internal interface VideoTransport {
    val isPlaying: Boolean
    val isLoading: Boolean

    /** Playback position on the backend's 0f..1000f scale. */
    val sliderPos: Float
    val positionText: String
    val durationText: String
    val muted: Boolean

    fun togglePlay()

    fun seekStart(value: Float)

    fun seekFinished()

    fun toggleMute()

    /** Hand the clip to the OS player — full screen, system controls, scrubbing a long file. */
    fun openExternally()

    /** The viewer's F key: the clip alone, full screen. */
    fun toggleFullscreen() = Unit
}

/**
 * The mounted player: the video surface with its controls overlaid.
 *
 * Autoplays, because the user already opted in (the poster, or the viewer's page). A backend error
 * is reported through [onError] so the caller can fall back (the download chip, the viewer's
 * "couldn't play") rather than leave a black rectangle.
 *
 * Inline, the bar's last button is [onExternal]: the system player, or with [expandsInViewer] the
 * chat's viewer (pausing here first, or the clip would play twice). [fill] is the viewer's page:
 * the frame fills it, and the last button is full screen. [onTransport] hands the transport to the
 * viewer while mounted, for its Space and F keys.
 */
@Composable
internal fun MountedVideoPlayer(
    uri: String,
    onError: () -> Unit,
    fill: Boolean = false,
    onExternal: () -> Unit = {},
    expandsInViewer: Boolean = false,
    onTransport: (VideoTransport?) -> Unit = {},
) {
    val external by rememberUpdatedState(onExternal)
    val expands by rememberUpdatedState(expandsInViewer)
    val player = rememberVideoPlayerState()
    // The viewer's page keeps a frame of a clip it plays (one with no poster from its phone), and
    // the clip's tiles show it from then on. Grabbed off this surface once the clip really plays.
    val keepFrameOf = if (fill) LocalViewerClip.current?.takeIf { ClipFrames.of(it) == null } else null
    val frameLayer = rememberGraphicsLayer()
    var grabbing by remember(keepFrameOf) { mutableStateOf(keepFrameOf != null) }
    LaunchedEffect(keepFrameOf) {
        val id = keepFrameOf ?: return@LaunchedEffect
        // Only observable state here: `currentTime` is a blocking native call on Linux (runBlocking),
        // which must never run on the main thread.
        snapshotFlow { player.isPlaying && !player.isLoading }.first { it }
        delay(POSTER_AFTER_MS)
        if (!player.isPlaying) {
            grabbing = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        // Best effort: a clip's tile can do without its frame; the app must not go down for one.
        try {
            val frame = softwareFrame(frameLayer.toImageBitmap())
            if (!looksBlank(frame)) ClipFrames.keep(id, posterSized(frame))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        grabbing = false
    }
    // ⚠️ Where openUri runs is per platform ([VideoOpenContext]). On the desktop it MUST NOT run on
    // the Compose/AWT main thread: Compose Media Player's macOS backend reports failures through
    // `setPlayerError`, which is `runBlocking { withContext(Main) }`, and on the main thread that
    // parks the EDT waiting for the EDT and freezes the entire app. On Android it MUST run there:
    // ExoPlayer throws when it is called off the thread that made it.
    //
    // runCatching, not try/catch-in-composition, because a Linux box with no GStreamer throws
    // UnsatisfiedLinkError (an Error — runCatching catches Throwable) the first time the JNI shim
    // is touched. Either way we degrade to the download chip instead of taking the timeline down.
    LaunchedEffect(uri) {
        runCatching { kotlinx.coroutines.withContext(VideoOpenContext) { player.openUri(uri) } }
            .onFailure { onError() }
    }
    val error = player.error
    LaunchedEffect(error) { if (error != null) onError() }

    // Mute is remembered here, not in the backend: restoring the previous level is nicer than
    // ramping back to 1.0, and the player exposes volume as a plain Float.
    var mutedVolume by remember(uri) { mutableStateOf<Float?>(null) }
    val transport = remember(player, uri) {
        object : VideoTransport {
            override val isPlaying get() = player.isPlaying
            override val isLoading get() = player.isLoading
            override val sliderPos get() = player.sliderPos
            override val positionText get() = player.positionText
            override val durationText get() = player.durationText
            override val muted get() = mutedVolume != null

            override fun togglePlay() {
                if (player.isPlaying) player.pause() else player.play()
            }

            override fun seekStart(value: Float) = player.seekStart(value)

            override fun seekFinished() = player.seekFinished()

            override fun toggleMute() {
                val saved = mutedVolume
                if (saved == null) {
                    mutedVolume = player.volume
                    player.volume = 0f
                } else {
                    player.volume = saved
                    mutedVolume = null
                }
            }

            override fun openExternally() {
                if (expands && player.isPlaying) player.pause()
                external()
            }

            override fun toggleFullscreen() = player.toggleFullscreen()
        }
    }
    DisposableEffect(transport) {
        onTransport(transport)
        onDispose { onTransport(null) }
    }
    val pointer = LocalPointerAvailable.current
    val trailing = when {
        fill -> if (pointer) VideoTrailing.Fullscreen else VideoTrailing.None
        expandsInViewer -> VideoTrailing.OpenViewer
        else -> VideoTrailing.External
    }
    VideoPlayerFrame(transport, fill = fill, trailing = trailing) { modifier ->
        val surface = if (!grabbing) {
            modifier
        } else {
            modifier.drawWithContent {
                frameLayer.record { this@drawWithContent.drawContent() }
                drawLayer(frameLayer)
            }
        }
        VideoPlayerSurface(playerState = player, modifier = surface)
    }
}

/** How long into playing a clip its kept frame is taken: past a first black frame or a fade-in. */
private const val POSTER_AFTER_MS = 600L

/** The clip a viewer page is playing (its file id), so the player can keep a frame of it. */
internal val LocalViewerClip = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/** What the control bar's last button does. */
internal enum class VideoTrailing { External, OpenViewer, Fullscreen, None }

/**
 * Video frame + overlaid controls. [surface] paints the actual frames (the library's
 * `VideoPlayerSurface` in production, anything in tests).
 *
 * Layout rules, chosen to match how a video behaves everywhere else and to stay calm inside a
 * message list:
 *  - Fixed height (same as the poster) with the frame letterboxed on black, so mounting the player
 *    never reflows the timeline and a portrait clip does not blow the bubble open.
 *  - Controls sit ON the video over a bottom scrim, rather than stealing a row underneath it.
 *  - They show while paused, while hovered and while scrubbing; otherwise they fade away.
 *  - The whole surface is a play/pause target, with a large centred glyph while paused.
 */
@Composable
internal fun VideoPlayerFrame(
    transport: VideoTransport,
    fill: Boolean = false,
    trailing: VideoTrailing = VideoTrailing.External,
    surface: @Composable (Modifier) -> Unit,
) {
    val pointer = LocalPointerAvailable.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var scrubbing by remember { mutableStateOf(false) }
    // Touch has no hover, so "show the controls while the pointer is over the video" would mean a
    // phone never sees them during playback. There, a tap REVEALS them for [CONTROLS_REVEAL_MS]
    // (the standard mobile-player gesture) instead of toggling play — play/pause is the button.
    var revealedAt by remember { mutableStateOf(0L) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealedAt) {
        if (revealedAt == 0L) return@LaunchedEffect
        revealed = true
        delay(CONTROLS_REVEAL_MS)
        revealed = false
    }
    val controlsVisible =
        scrubbing || !transport.isPlaying || (if (pointer) hovered else revealed)
    Box(
        modifier = Modifier
            // In the viewer the frame is the page; in the transcript it is a fixed, rounded box.
            .then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(Media.inlineVideoMaxHeight).clip(RoundedCornerShape(Radii.md)))
            .background(Color.Black)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (pointer) transport.togglePlay() else revealedAt = revealedAt + 1 },
            )
            .testTag("attachment_video_player"),
        contentAlignment = Alignment.Center,
    ) {
        surface(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth())

        if (transport.isLoading) {
            CircularProgressIndicator(
                Modifier.size(MdImageDimens.SpinnerSize).testTag("attachment_video_buffering"),
                color = VideoControls.Foreground,
                strokeWidth = MdImageDimens.SpinnerStroke,
            )
        } else if (!transport.isPlaying) {
            // Centred glyph on a translucent disc — readable over a bright or a dark frame.
            Box(
                modifier = Modifier
                    .size(Sizes.videoPlayGlyph + Space.md)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = VideoControls.DiscAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MediaIcons.Play,
                    contentDescription = "Play",
                    tint = VideoControls.Foreground,
                    modifier = Modifier.size(Sizes.videoPlayGlyph).testTag("attachment_video_center_play"),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoTransportBar(
                transport = transport,
                trailing = trailing,
                onScrubbingChange = { scrubbing = it },
            )
        }
    }
}

/** The scrubber's track: thin and rounded, the played part solid. */
@Composable
private fun ScrubberTrack(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(VideoControls.TrackHeight)
            .clip(CircleShape)
            .background(VideoControls.Foreground.copy(alpha = VideoControls.TrackAlpha)),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(VideoControls.Foreground))
    }
}

/** The scrubber's handle: a small round dot. */
@Composable
private fun ScrubberThumb() {
    Box(Modifier.size(VideoControls.ThumbSize).clip(CircleShape).background(VideoControls.Foreground))
}

/** Bottom control bar: play/pause · elapsed · scrubber · total · mute · [trailing]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTransportBar(
    transport: VideoTransport,
    trailing: VideoTrailing,
    onScrubbingChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Scrim, not a solid bar: controls stay legible over a bright frame without boxing
            // the picture in.
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = VideoControls.ScrimAlpha)),
                ),
            )
            .padding(horizontal = Space.sm, vertical = Space.xs)
            .testTag("attachment_video_controls"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        VideoControlButton(
            icon = if (transport.isPlaying) MediaIcons.Pause else MediaIcons.Play,
            label = if (transport.isPlaying) "Pause" else "Play",
            tag = "attachment_video_playpause",
            onClick = transport::togglePlay,
        )
        VideoTimeLabel(transport.positionText, "attachment_video_position")
        Slider(
            value = transport.sliderPos,
            onValueChange = {
                onScrubbingChange(true)
                transport.seekStart(it)
            },
            onValueChangeFinished = {
                transport.seekFinished()
                onScrubbingChange(false)
            },
            valueRange = 0f..VideoControls.SliderRange,
            thumb = { ScrubberThumb() },
            track = { state ->
                val range = state.valueRange.endInclusive - state.valueRange.start
                ScrubberTrack(if (range > 0f) (state.value - state.valueRange.start) / range else 0f)
            },
            modifier = Modifier.weight(1f).height(Sizes.iconButton).testTag("attachment_video_scrubber"),
        )
        VideoTimeLabel(transport.durationText, "attachment_video_duration")
        VideoControlButton(
            icon = if (transport.muted) MediaIcons.Muted else MediaIcons.Volume,
            label = if (transport.muted) "Unmute" else "Mute",
            tag = "attachment_video_mute",
            onClick = transport::toggleMute,
        )
        when (trailing) {
            VideoTrailing.External -> VideoControlButton(
                icon = MediaIcons.OpenExternal,
                label = "Open in system player",
                tag = "attachment_video_external",
                onClick = transport::openExternally,
            )
            VideoTrailing.OpenViewer -> VideoControlButton(
                icon = MediaIcons.Fullscreen,
                label = "Open in viewer",
                tag = "attachment_video_expand",
                onClick = transport::openExternally,
            )
            VideoTrailing.Fullscreen -> VideoControlButton(
                icon = MediaIcons.Fullscreen,
                label = "Full screen",
                tag = "chat_media_viewer_fullscreen",
                onClick = transport::toggleFullscreen,
            )
            VideoTrailing.None -> Unit
        }
    }
}

@Composable
private fun VideoTimeLabel(text: String, tag: String) {
    Text(
        text = text,
        color = VideoControls.Foreground,
        fontSize = 11.sp,
        fontFamily = MonoFontFamily,
        maxLines = 1,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun VideoControlButton(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(Sizes.iconButton).pointerHoverIcon(PointerIcon.Hand).testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = VideoControls.Foreground,
            modifier = Modifier.size(Sizes.iconSm),
        )
    }
}

/**
 * Video controls paint over arbitrary frames, so they use their own fixed palette rather than the
 * theme's — a themed tint would vanish against the wrong picture.
 */
/** How long a tap keeps the controls on screen on a touch host. */
internal const val CONTROLS_REVEAL_MS = 3_000L

internal object VideoControls {
    val Foreground = Color.White
    const val ScrimAlpha = 0.72f
    const val DiscAlpha = 0.45f
    const val TrackAlpha = 0.3f

    /** The backends express position on a 0..1000 scale, not 0..1. */
    const val SliderRange = 1000f

    /** The scrubber: a thin track and a small round handle, as a Mac player draws them. */
    val TrackHeight = 4.dp
    val ThumbSize = 12.dp
}

/**
 * A file in a message, as a card: a badge of its type (its extension on its family's colour, else
 * [icon]), its name, "PDF · 28.2 KB", and a download mark. As wide as the message's media column
 * ([mediaColumnWidth]), so a file under a collage lines up with it. A tap downloads it, then:
 *
 * Two behaviours, one composable, keyed on whether the host has a pointer:
 *  - Pointer (desktop): download, "Save as…", then open the file the user chose.
 *  - Touch (Android): download and hand straight to the system chooser — a phone has no browsable
 *    file system to save into, and the platform gesture is "open with".
 *
 * A host's attachment downloads in the fleet's [DownloadCenter] (#95, [startDownload]), by its
 * key: leaving the chat lets go of it and it carries on, waiting in the activity bar; the card
 * draws the download whenever there is one — its ring on the badge while it runs, so coming back
 * shows the same download. A preview's in-memory media has no key and keeps the bytes path.
 *
 * A failed DOWNLOAD (or one cancelled in the bar) turns the second line into "Download failed — tap
 * to retry"; a cancelled save does not (cancel is not an error, and a red card on cancel would be
 * worse than a silent one).
 */
@Composable
private fun AttachmentFileCard(
    icon: ImageVector,
    label: String,
    att: Attachment,
    media: AttachmentMedia,
) {
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val downloads = rememberDownloads()
    val key = media.downloadKey(att)
    val download by remember(downloads, key) { downloads.items.map { all -> all.firstOrNull { it.key == key } } }
        .collectAsState(downloads.items.value.firstOrNull { it.key == key })
    val running = download?.state as? DownloadState.Running
    var busy by remember(att.file_id) { mutableStateOf(false) }
    var failed by remember(att.file_id) { mutableStateOf(false) }
    // This card waits for its download: one tap, one hand-over.
    var waiting by remember(att.file_id) { mutableStateOf(false) }
    val badge = remember(att.name, label) { fileBadgeLabel(att.name ?: label) }
    val family = remember(att.name, att.mime, label) { fileFamily(fileExtension(att.name ?: label), att.mime) }
    val meta = remember(att.name, att.mime, att.size, label) { fileMetaLine(att.name ?: label, att.mime, att.size) }
    Row(
        modifier = Modifier
            .mediaColumnWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(cs.surfaceContainerHigh)
            .clickable(enabled = !busy && !waiting && running == null) {
                if (key != null) {
                    // The card's scope holds only the claim: leaving lets go, the download goes on.
                    waiting = true
                    scope.launch {
                        try {
                            startDownload(downloads, media, att, platform, pointer)
                        } finally {
                            waiting = false
                        }
                    }
                    return@clickable
                }
                busy = true
                failed = false
                scope.launch {
                    val bytes = runCatching { media.bytes(att) }.getOrNull()
                    busy = false
                    if (bytes == null) {
                        failed = true
                        return@launch
                    }
                    saveOrOpenAttachment(platform, pointer, att.name ?: att.file_id, att.mime, bytes)
                }
            }
            .padding(Space.sm)
            .testTag("attachment_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm + Space.xs),
    ) {
        Box(
            Modifier.size(FileCard.BadgeSize).clip(RoundedCornerShape(Radii.sm)).background(family.color),
            contentAlignment = Alignment.Center,
        ) {
            when {
                running != null -> DownloadRing(running)
                busy -> CircularProgressIndicator(Modifier.size(18.dp).testTag("attachment_progress"), color = Color.White, strokeWidth = 2.dp)
                badge != null -> Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                else -> Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                // The extension stays in view: "very-long-report…-final.pdf".
                overflow = TextOverflow.MiddleEllipsis,
            )
            val state = download?.state
            val retry = failed || state is DownloadState.Failed || state == DownloadState.Cancelled
            val second = if (retry) "Download failed — tap to retry" else meta
            if (second.isNotEmpty()) {
                Text(
                    text = second,
                    color = if (retry) cs.error else cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = MediaIcons.Save,
            contentDescription = "Download",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The badge's ring while the file downloads: how far it got once the total is known, else turning. */
@Composable
private fun DownloadRing(state: DownloadState.Running) {
    val modifier = Modifier.size(18.dp).testTag("attachment_progress")
    val total = state.total?.takeIf { it > 0 }
    if (total == null) {
        CircularProgressIndicator(modifier, color = Color.White, strokeWidth = 2.dp)
    } else {
        val fraction = (state.received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        CircularProgressIndicator(
            progress = { fraction },
            modifier = modifier,
            color = Color.White,
            trackColor = Color.White.copy(alpha = FileCard.TrackAlpha),
            strokeWidth = 2.dp,
        )
    }
}

private object FileCard {
    val BadgeSize = 40.dp

    /** The ring's track on the badge's colour. */
    const val TrackAlpha = 0.3f
}

/**
 * Start (or join) [att]'s download in [downloads] and, while this caller is still here when it
 * finishes, hand it over as a tap does (R1). Leaving releases the claim; the download carries on and
 * waits in the bar. Null key (a preview's in-memory media): false — the caller keeps the bytes path.
 *
 * [onFailed] is the caller's word for a download that failed while it waited, where nothing else on
 * its screen says so (the viewer's Save); the user's own Cancel is not a failure.
 */
internal suspend fun startDownload(
    downloads: DownloadCenter,
    media: AttachmentMedia,
    att: Attachment,
    platform: Platform,
    pointer: Boolean,
    onFailed: () -> Unit = {},
): Boolean {
    val key = media.downloadKey(att) ?: return false
    val name = att.name ?: att.file_id
    val claim = downloads.start(key, name, att.mime ?: "application/octet-stream", DownloadPurpose.File, AttachmentDownload(media, att))
    try {
        val file = claim.await()
        val item = downloads.items.value.firstOrNull { it.key == key }
        when {
            file == null -> if (item?.state is DownloadState.Failed) onFailed()
            item != null -> handOverAsTapped(platform, pointer, downloads, item)
        }
    } finally {
        claim.release()
    }
    return true
}

/**
 * [att] through its media, which can also stop it: the media cache runs the transfer on its own
 * scope, so cancelling the job alone would leave it running.
 */
private class AttachmentDownload(private val media: AttachmentMedia, private val att: Attachment) : DownloadSource {
    override suspend fun fetch(progress: (received: Long, total: Long?) -> Unit): DownloadedFile? = media.download(att, progress)

    override fun cancel() = media.cancelDownload(att)
}

/**
 * Put [bytes] somewhere the user can get at them, the way this host does it.
 *
 * Two behaviours, one helper, so the chip and the lightbox can never drift apart:
 *  - Pointer (desktop): "Save as…", then open the file the user CHOSE — never a second temp copy.
 *  - Touch (Android): straight to the system chooser; a phone has no browsable file system to save
 *    into, and the platform gesture there is "open with".
 */
internal suspend fun saveOrOpenAttachment(
    platform: Platform,
    pointer: Boolean,
    name: String,
    mime: String?,
    bytes: ByteArray,
) {
    // Give the saved file an extension the host's viewer can identify, or a PDF/log downloaded
    // under a bare `file_id` opens as an unrecognised blob.
    val safeName = previewFilename(fileNameOf(name), mime)
    val type = mime?.ifBlank { null } ?: platform.files.probeMime(safeName)
    if (pointer) {
        platform.files.saveAs(safeName, type, bytes)?.let { platform.files.openSaved(it) }
        return
    }
    if (!platform.files.openExternally(safeName, type, bytes)) {
        platform.notices.show("Couldn't open attachment")
    }
}
