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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.ToolStatus
import dev.supermux.chat.chatTimeLabel
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
import dev.supermux.ui.resolveBashParts
import dev.supermux.ui.resolveEditParts
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
) {
    Column(Modifier.fillMaxWidth()) {
        // SelectionContainer makes the prose selectable/copyable; links inside stay clickable.
        SelectionContainer {
            MarkdownBody(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                onOpenFile = onOpenFile,
                linkify = true,
                onOpenUrl = onOpenUrl,
                loadImage = loadImage,
            )
        }
        MessageMetaRow(text = text, ts = ts)
    }
}

/** Compact time + copy + read-aloud under an agent reply. */
@Composable
private fun MessageMetaRow(text: String, ts: String?) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
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
                clipboard.setText(AnnotatedString(text))
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
                SelectionContainer {
                    Text(
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
            Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.sm + 2.dp, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!command.isNullOrBlank()) {
                Row {
                    Text("$ ", color = Color(0xFF4ADE80).copy(alpha = 0.9f), fontFamily = MonoFontFamily, fontSize = 12.sp)
                    Text(command, color = Color(0xFFF4F4F5), fontFamily = MonoFontFamily, fontSize = 12.sp)
                }
            }
            if (!output.isNullOrBlank()) {
                Text(
                    text = output + if (truncated) " …" else "",
                    color = if (status == ToolStatus.ERROR) Color(0xFFFCA5A5) else Color(0xFFD4D4D8),
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            } else if (status == ToolStatus.RUNNING && command.isNullOrBlank()) {
                Text("Running…", color = Color(0xFF71717A), fontSize = 11.sp)
            }
        }
    }
}

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
    val rendered = diff ?: content?.lineSequence()?.joinToString("\n") { "+$it" }.orEmpty()
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(Color(0xFF0C0C0E))
            .border(0.5.dp, cs.outline.copy(alpha = 0.35f), RoundedCornerShape(Radii.sm))
            .testTag("tool_diff"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = Space.sm + 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = path,
                color = Color(0xFFF4F4F5),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = Color(0xFFA1A1AA),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Space.xs),
                )
            }
            Spacer(Modifier.width(Space.xs))
            Text(modeLabel, color = Color(0xFF71717A), fontSize = 10.sp)
            Spacer(Modifier.width(Space.xs))
            Text(statusLabel, color = statusColor, fontSize = 10.sp)
        }
        if (rendered.isNotBlank()) {
            InlineDiff(rendered + if (truncated) "\n… truncated" else "")
        } else {
            Text(
                text = if (status == ToolStatus.RUNNING) "Preparing edit…" else "No diff content",
                color = Color(0xFF71717A),
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
    loadBytes: suspend (String) -> ByteArray? = { null },
    onOpenFile: (FilePathRef) -> Unit = {},
    highDetail: Boolean = false,
    onOpenWalkthrough: () -> Unit = {},
) {
    when (item) {
        is TimelineItem.Msg -> {
            val text = item.entry.text
            val atts = item.entry.attachments
            val isUser = item.entry.direction == "inbound"
            if (!text.isNullOrBlank() || !atts.isNullOrEmpty()) {
                Column(
                    // The row container carries the tag so the text and any attachments are INSIDE
                    // it: on Compose-for-Web the tag becomes this element's DOM `id`, and a journey
                    // asserts `[id^="chat-message:outbound:"]` has the reply's text within it.
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.sm)
                        .testTag(TestIds.chatMessage(item.entry.direction, item.entry.id)),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    if (!text.isNullOrBlank()) {
                        if (isUser) {
                            UserMessage(text)
                        } else if (text.startsWith("📖 Walkthrough ready")) {
                            WalkthroughReadyCard(text, onOpenWalkthrough)
                        } else {
                            AssistantMessage(text, onOpenFile = onOpenFile, ts = item.entry.ts)
                        }
                    }
                    if (!atts.isNullOrEmpty()) {
                        AttachmentList(atts, alignEnd = isUser, loadBytes = loadBytes)
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
    }
}

@Composable
fun ActivityKindCard(event: ActivityEvent) {
    when (event.kind) {
        "reasoning" -> ReasoningCard(event)
        "plan" -> PlanCard(event)
        "task" -> TaskCard(event)
        else -> {}
    }
}

@Composable
private fun ReasoningCard(event: ActivityEvent) {
    val cs = MaterialTheme.colorScheme
    val redacted = event.redacted == true || event.title?.contains("redacted", ignoreCase = true) == true
    var expanded by remember { mutableStateOf(false) }
    val canExpand = !redacted && !event.detail.isNullOrBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("activity-reasoning")
            .clickable(enabled = canExpand) { expanded = !expanded }
            .padding(vertical = 2.dp),
    ) {
        Text(
            if (redacted) "Thinking (redacted)" else "Thinking…",
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

/** Renders a message's attachments below its text, aligned to the sender side. */
@Composable
fun AttachmentList(
    attachments: List<Attachment>,
    alignEnd: Boolean,
    loadBytes: suspend (String) -> ByteArray?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        for (att in attachments) AttachmentItem(att, alignEnd, loadBytes)
    }
}

@Composable
private fun AttachmentItem(
    att: Attachment,
    alignEnd: Boolean,
    loadBytes: suspend (String) -> ByteArray?,
) {
    val mime = att.mime ?: ""
    val isImage = att.kind == "photo" || att.kind == "image" || mime.startsWith("image/")
    val isVideo = att.kind == "video" || att.kind == "video_note" || mime.startsWith("video/")
    val isAudio = att.kind == "voice" || att.kind == "audio" || mime.startsWith("audio/")
    if (isImage) {
        InlineImageAttachment(att, alignEnd, loadBytes)
        return
    }
    if (isVideo) {
        InlineVideo(att, loadBytes)
        return
    }
    val label = when {
        isAudio -> att.name ?: "voice message"
        else -> att.name ?: att.file_id
    }
    val icon = when {
        isAudio -> Icons.AutoMirrored.Filled.VolumeUp
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    AttachmentChip(icon, label, att, loadBytes)
}

/**
 * Inline preview for image attachments. Bytes come from the [loadBytes] seam (the broker file
 * fetch) and decode through Coil, so the same path runs on every host.
 *
 * Painted at natural size, shrunk-only to fit the column and [MdImageDimens.MaxHeight] — the same
 * rule as markdown images, so a 32×32 icon is not blown up to a 280 dp blob. A tap opens the
 * fullscreen [ImageLightbox] (pinch-zoom, pan, save) on BOTH hosts — desktop's old
 * open-in-OS-viewer became the lightbox's secondary action. A load or decode failure falls back to
 * the ordinary [AttachmentChip] so the user keeps the retry + save path.
 */
@Composable
private fun InlineImageAttachment(
    att: Attachment,
    alignEnd: Boolean,
    loadBytes: suspend (String) -> ByteArray?,
) {
    var bytes by remember(att.file_id) { mutableStateOf<ByteArray?>(null) }
    var fetchFailed by remember(att.file_id) { mutableStateOf(false) }
    var showLightbox by remember(att.file_id) { mutableStateOf(false) }
    LaunchedEffect(att.file_id) {
        val loaded = runCatching { loadBytes(att.file_id) }.getOrNull()
        if (loaded == null) fetchFailed = true else bytes = loaded
    }
    if (fetchFailed) {
        AttachmentChip(Icons.Filled.Image, att.name ?: "image", att, loadBytes)
        return
    }
    val data = bytes
    if (data == null) {
        MdImageLoadingBox("attachment_image_loading", Radii.md)
        return
    }
    when (val decoded = rememberDecodedImage(data)) {
        DecodedImage.Loading -> MdImageLoadingBox("attachment_image_loading", Radii.md)
        DecodedImage.Failed -> AttachmentChip(Icons.Filled.Image, att.name ?: "image", att, loadBytes)
        is DecodedImage.Ready -> {
            ShrinkOnlyImage(
                width = decoded.width,
                height = decoded.height,
                tag = "attachment_image",
                radius = Radii.md,
                alignEnd = alignEnd,
                onClick = { showLightbox = true },
            ) { modifier ->
                Image(
                    painter = decoded.painter,
                    contentDescription = att.name ?: "image",
                    contentScale = ContentScale.Fit,
                    modifier = modifier,
                )
            }
            if (showLightbox) {
                ImageLightbox(
                    painter = decoded.painter,
                    name = att.name ?: att.file_id,
                    mime = att.mime,
                    bytes = data,
                    onDismiss = { showLightbox = false },
                )
            }
        }
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

    fun zoomBy(factor: Float) = scaleTo(scale * factor)

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

    private fun clamp(candidate: Offset): Offset {
        val maxX = (viewport.width * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (viewport.height * (scale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
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
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val dy = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                            if (dy == 0f) continue
                            // Scrolling DOWN (positive) zooms out, as every image viewer does.
                            transform.zoomBy(if (dy < 0f) LightboxTransform.STEP else 1f / LightboxTransform.STEP)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
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
                    icon = Icons.Filled.Remove,
                    label = "Zoom out",
                    tag = "image_lightbox_zoom_out",
                    onClick = { transform.zoomBy(1f / LightboxTransform.STEP) },
                )
                LightboxButton(
                    icon = Icons.Filled.Add,
                    label = "Zoom in",
                    tag = "image_lightbox_zoom_in",
                    onClick = { transform.zoomBy(LightboxTransform.STEP) },
                )
                if (bytes != null) {
                    LightboxButton(
                        icon = Icons.Filled.Download,
                        label = "Download",
                        tag = "image_lightbox_download",
                        onClick = { scope.launch { saveOrOpenAttachment(platform, pointer, name, mime, bytes) } },
                    )
                }
                LightboxButton(
                    icon = Icons.Filled.Close,
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
 * Bytes are fetched only once the user opts into playback — a transcript must never eagerly
 * download every clip — then staged through `Platform.files.stageTemp` so the native backend can
 * open them by URI. A failed download, or a backend error, falls back to the ordinary
 * [AttachmentChip] so save + the OS player stay reachable.
 *
 * [renderPlayer] is the mount seam: tests inject a stub so the suite never loads native media
 * libraries in a headless worker.
 */
@Composable
internal fun InlineVideo(
    att: Attachment,
    loadBytes: suspend (String) -> ByteArray?,
    renderPlayer: @Composable (uri: String, onExternal: () -> Unit, onError: () -> Unit) -> Unit =
        { uri, onExternal, onError -> InlineVideoPlayer(uri, onExternal, onError) },
) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val scope = rememberCoroutineScope()
    var playing by remember(att.file_id) { mutableStateOf(false) }
    var uri by remember(att.file_id) { mutableStateOf<String?>(null) }
    var staged by remember(att.file_id) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(att.file_id) { mutableStateOf(false) }

    LaunchedEffect(att.file_id, playing) {
        if (!playing || uri != null || failed) return@LaunchedEffect
        val bytes = runCatching { loadBytes(att.file_id) }.getOrNull()
        if (bytes == null) {
            failed = true
            return@LaunchedEffect
        }
        // Name by the unique file_id so two clips never collide in the staging dir, and keep the
        // original extension — the native backends pick their demuxer from it.
        val name = attachmentTempName("video_${att.file_id.substringAfterLast('/')}", att.name, "mp4")
        val located = runCatching { platform.files.stageTemp(name, bytes) }.getOrNull()
        if (located != null) {
            staged = bytes
            uri = located
        } else {
            failed = true
        }
    }

    val located = uri
    val surface = Modifier
        .fillMaxWidth(Media.inlineVideoWidthFraction)
        .heightIn(max = Media.inlineVideoMaxHeight)
    when {
        failed -> AttachmentChip(Icons.Filled.Movie, att.name ?: "video", att, loadBytes)
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
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = att.name ?: "Play video",
                tint = cs.onSurface,
                modifier = Modifier.size(Sizes.videoPlayGlyph),
            )
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
                    val body = staged
                    if (body != null) {
                        scope.launch { saveOrOpenAttachment(platform, pointer, att.name ?: "video", att.mime, body) }
                    }
                },
                { failed = true },
            )
        }
    }
}

/**
 * Name a staged attachment file. [base] names it (so an OS viewer's title bar is meaningful, and so
 * two clips keyed by file_id cannot collide) and the extension comes from [extensionFrom], falling
 * back to [defaultExt] — both OS viewers and the video backends pick their decoder from the suffix.
 */
internal fun attachmentTempName(base: String, extensionFrom: String?, defaultExt: String): String {
    val safeBase = base.substringAfterLast('/').substringBeforeLast('.', base).take(64).ifBlank { "file" }
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
}

/**
 * The mounted player: the video surface with its controls overlaid.
 *
 * Autoplays, because the user already opted in by clicking the poster. A backend error is reported
 * through [onError] so [InlineVideo] can fall back to the download chip rather than leave a black
 * rectangle in the transcript.
 */
@Composable
private fun InlineVideoPlayer(uri: String, onExternal: () -> Unit, onError: () -> Unit) {
    val player = rememberVideoPlayerState()
    // ⚠️ openUri MUST NOT run on the Compose/AWT main thread. Compose Media Player's macOS backend
    // reports failures through `setPlayerError`, which is `runBlocking { withContext(Main) }` — on
    // the main thread that parks the EDT waiting for the EDT and freezes the entire app. A
    // LaunchedEffect body runs on the main dispatcher, so hop off it.
    //
    // runCatching, not try/catch-in-composition, because a Linux box with no GStreamer throws
    // UnsatisfiedLinkError (an Error — runCatching catches Throwable) the first time the JNI shim
    // is touched. Either way we degrade to the download chip instead of taking the timeline down.
    LaunchedEffect(uri) {
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { player.openUri(uri) } }
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

            override fun openExternally() = onExternal()
        }
    }
    VideoPlayerFrame(transport) { modifier ->
        VideoPlayerSurface(playerState = player, modifier = modifier)
    }
}

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
            .fillMaxWidth()
            .height(Media.inlineVideoMaxHeight)
            .clip(RoundedCornerShape(Radii.md))
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
        surface(Modifier.fillMaxWidth())

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
                    imageVector = Icons.Filled.PlayArrow,
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
                onScrubbingChange = { scrubbing = it },
            )
        }
    }
}

/** Bottom control bar: play/pause · elapsed · scrubber · total · mute · open externally. */
@Composable
private fun VideoTransportBar(
    transport: VideoTransport,
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
            icon = if (transport.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
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
            colors = SliderDefaults.colors(
                thumbColor = VideoControls.Foreground,
                activeTrackColor = VideoControls.Foreground,
                inactiveTrackColor = VideoControls.Foreground.copy(alpha = VideoControls.TrackAlpha),
            ),
            modifier = Modifier.weight(1f).height(Sizes.iconButton).testTag("attachment_video_scrubber"),
        )
        VideoTimeLabel(transport.durationText, "attachment_video_duration")
        VideoControlButton(
            icon = if (transport.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            label = if (transport.muted) "Unmute" else "Mute",
            tag = "attachment_video_mute",
            onClick = transport::toggleMute,
        )
        VideoControlButton(
            icon = Icons.Filled.OpenInNew,
            label = "Open in system player",
            tag = "attachment_video_external",
            onClick = transport::openExternally,
        )
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
}

/**
 * Compact chip: kind glyph + name + download.
 *
 * Two behaviours, one composable, keyed on whether the host has a pointer:
 *  - Pointer (desktop): download, "Save as…", then open the file the user chose.
 *  - Touch (Android): download and hand straight to the system chooser — a phone has no browsable
 *    file system to save into, and the platform gesture is "open with".
 *
 * A failed DOWNLOAD flips the chip to "Download failed — tap to retry"; a cancelled save does not
 * (cancel is not an error, and a red chip on cancel would be worse than a silent one).
 */
@Composable
private fun AttachmentChip(
    icon: ImageVector,
    label: String,
    att: Attachment,
    loadBytes: suspend (String) -> ByteArray?,
) {
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.sm)
    val scope = rememberCoroutineScope()
    var busy by remember(att.file_id) { mutableStateOf(false) }
    var failed by remember(att.file_id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, shape)
            .clickable(enabled = !busy) {
                busy = true
                failed = false
                scope.launch {
                    val bytes = runCatching { loadBytes(att.file_id) }.getOrNull()
                    busy = false
                    if (bytes == null) {
                        failed = true
                        return@launch
                    }
                    saveOrOpenAttachment(platform, pointer, att.name ?: att.file_id, att.mime, bytes)
                }
            }
            .padding(horizontal = Space.sm, vertical = Space.xs)
            .testTag("attachment_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                color = cs.onSurfaceVariant,
                strokeWidth = 1.5.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = if (failed) "Download failed — tap to retry" else label,
            color = if (failed) cs.error else cs.onSurface,
            fontSize = 13.sp,
            fontFamily = MonoFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = "Download",
            tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
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
    val safeName = previewFilename(name.substringAfterLast('/'), mime)
    val type = mime?.ifBlank { null } ?: platform.files.probeMime(safeName)
    if (pointer) {
        platform.files.saveAs(safeName, type, bytes)?.let { platform.files.openSaved(it) }
        return
    }
    if (!platform.files.openExternally(safeName, type, bytes)) {
        platform.notices.show("Couldn't open attachment")
    }
}
