package dev.supermux.ui.work

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.theme.GeistFontFamily
import dev.supermux.ui.theme.GeistMonoFontFamily
import dev.supermux.ui.theme.Motion
import dev.supermux.work.Download
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadState
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/**
 * Every listed download (#95) and what can be done with it — design A, round 1's palette for the downloads alone: a
 * header ("Downloads · 5", and Clear finished once anything has ended), then a quiet row a download — our thin glyph,
 * the name, a dim line saying what became of it, and its actions as quiet words: Cancel while it runs, Retry once it
 * failed or was cancelled, and once done the host's own [actions] for a file (R3) or Install for an app update. Under
 * a running row a hairline fills.
 *
 * A phone's sheet draws two lines a row. A desktop's [popover] draws one, washes the selected row, and lists the keys
 * under the rows: ↑↓ to move, ↵ for the selected row's first word (esc is the popover's own).
 *
 * Pure: the caller passes the items, newest first as the center lists them, and the callbacks. What runs comes first.
 */
@Composable
fun ActivityList(
    items: List<Download>,
    actions: Set<FileAction>,
    onCancel: (Download) -> Unit,
    onRetry: (Download) -> Unit,
    onAction: (Download, FileAction) -> Unit,
    onInstall: (Download) -> Unit,
    modifier: Modifier = Modifier,
    onClearFinished: () -> Unit = {},
    popover: Boolean = false,
) {
    val rows = remember(items) {
        items.filter { it.state is DownloadState.Running } + items.filter { it.state !is DownloadState.Running }
    }
    val wordsOf: (Download) -> List<Word> = { wordsFor(it, actions, onCancel, onRetry, onAction, onInstall) }
    var selected by remember { mutableIntStateOf(0) }
    val current = selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    // The popover's keys: it takes the focus as it opens. Not on touch, where focus would only raise a keyboard.
    if (popover) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val keys = if (!popover) Modifier else Modifier
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionDown -> { selected = (current + 1).coerceAtMost(rows.lastIndex); true }
                Key.DirectionUp -> { selected = (current - 1).coerceAtLeast(0); true }
                Key.Enter, Key.NumPadEnter -> {
                    rows.getOrNull(current)?.let { primaryOf(it, wordsOf(it)) }?.act?.invoke()
                    true
                }
                else -> false
            }
        }
        .focusRequester(focus)
        .focusable()
    Column(modifier.testTag("activity_list").then(keys)) {
        Header(count = items.size, clearable = items.any { it.state !is DownloadState.Running }, onClearFinished, popover)
        // The rows scroll under a header (and over the hints) that stay.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (popover) 0.dp else ListTokens.SheetRowsInset),
        ) {
            rows.forEachIndexed { i, item ->
                key(item.key) {
                    if (popover) {
                        PopoverRow(item, wordsOf(item), selected = i == current, onHover = { selected = i })
                    } else {
                        SheetRow(item, wordsOf(item))
                    }
                }
            }
        }
        if (popover && LocalHardwareKeyboard.current) {
            Hints(verb = rows.getOrNull(current)?.let { primaryOf(it, wordsOf(it)) }?.label?.lowercase())
        }
    }
}

/** "Downloads · 5" and, once anything has ended, Clear finished on the right. */
@Composable
private fun Header(count: Int, clearable: Boolean, onClear: () -> Unit, popover: Boolean) {
    val cs = MaterialTheme.colorScheme
    val size = if (popover) 12.sp else 13.sp
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (popover) 34.dp else 44.dp)
            .padding(start = if (popover) 12.dp else 22.dp, end = if (popover) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Downloads", style = listText(size, FontWeight.Medium), color = cs.onSurface, modifier = Modifier.semantics { heading() })
        // Between the two, for the eye: nothing to read aloud.
        Text("·", style = listText(size), color = cs.faint, modifier = Modifier.padding(horizontal = 6.dp).clearAndSetSemantics {})
        Text("$count", style = listText(size), color = cs.onSurfaceVariant, modifier = Modifier.testTag("activity_count"))
        Spacer(Modifier.weight(1f))
        if (clearable) QuietWord("Clear finished", "activity_clear", if (popover) WordSize.Popover else WordSize.SheetHeader, onClear)
    }
}

/** A phone's row: the glyph; the name and its size or %; what became of it and the quiet words; the hairline. */
@Composable
private fun SheetRow(item: Download, words: List<Word>) {
    val cs = MaterialTheme.colorScheme
    val state = item.state
    val timeLeft = rememberTimeLeft(item.key, state as? DownloadState.Running)
    Box(
        Modifier
            .fillMaxWidth()
            .testTag("activity_item")
            // One stop that reads what it shows ("notes.pdf, 4.2 MB, Downloaded"); its words stay stops of their own.
            .semantics(mergeDescendants = true) { activityItemKey = item.key },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Icon(glyphOf(item), contentDescription = null, tint = cs.sheetTint(item), modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                    Name(item.name, listText(15.sp, FontWeight.Medium), cs.onSurface, Modifier.weight(1f).alignByBaseline())
                    sheetFigure(item)?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = listText(12.5.sp, mono = true), color = cs.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sheetDetail(item, timeLeft),
                        style = listText(13.5.sp),
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Words(words, WordSize.Sheet)
                }
            }
        }
        if (state is DownloadState.Running) {
            Hairline(state.fraction(), Modifier.align(Alignment.BottomStart).padding(start = 44.dp, end = 14.dp, bottom = 2.dp))
        }
    }
}

/** A desktop's row, one line: glyph · name · what became of it · % · the quiet words; washed while selected. */
@Composable
private fun PopoverRow(item: Download, words: List<Word>, selected: Boolean, onHover: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = item.state
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    // The one thing that eases: the glyph turning teal as the row is selected.
    val tint by animateColorAsState(cs.popoverTint(item, selected), Motion.stateChange(), label = "download-glyph")
    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
            .hoverable(source)
            .testTag("activity_item")
            .semantics(mergeDescendants = true) { activityItemKey = item.key },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(glyphOf(item), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Name(item.name, listText(13.5.sp, FontWeight.Medium), cs.onSurface, Modifier.weight(1f, fill = false).alignByBaseline())
                Spacer(Modifier.width(10.dp))
                Text(popoverDetail(item), style = listText(13.sp), color = cs.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            }
            popoverFigure(item)?.let {
                Spacer(Modifier.width(14.dp))
                Text(it, style = listText(11.5.sp, mono = true), color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(12.dp))
            Words(words, WordSize.Popover)
        }
        if (state is DownloadState.Running) {
            Hairline(state.fraction(), Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 12.dp, bottom = 3.dp))
        }
    }
}

/** The keys under the popover's rows, with what ↵ does on the selected one (nothing on a running row). */
@Composable
private fun Hints(verb: String?) {
    Text(
        listOfNotNull("↑↓ to move", verb?.let { "↵ to $it" }, "esc to close").joinToString("  ·  "),
        style = listText(11.sp),
        color = MaterialTheme.colorScheme.faint,
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 5.dp).testTag("activity_hints"),
    )
}

/** A running row's progress: a hairline track that fills in teal; just the track while the size is unknown. */
@Composable
private fun Hairline(fraction: Float?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val track = cs.onSurface.copy(alpha = 0.10f)
    val fill = cs.primary
    Canvas(
        modifier
            .fillMaxWidth()
            .height(ListTokens.Hairline)
            .testTag("activity_progress")
            .semantics { progressBarRangeInfo = fraction?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate },
    ) {
        drawRect(track)
        if (fraction != null && fraction > 0f) drawRect(fill, size = Size(size.width * fraction, size.height))
    }
}

/** A row's quiet words, a dim dot between two. */
@Composable
private fun Words(words: List<Word>, size: WordSize) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        words.forEachIndexed { i, w ->
            if (i > 0) {
                Text("·", style = listText(12.sp), color = MaterialTheme.colorScheme.faint, modifier = Modifier.padding(horizontal = 1.dp).clearAndSetSemantics {})
            }
            QuietWord(w.label, w.tag, size, w.act)
        }
    }
}

/** An action as a quiet word: dim, full ink under the pointer or the finger, a soft wash while pressed. Instant. */
@Composable
private fun QuietWord(label: String, tag: String, size: WordSize, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .height(size.height)
            .clip(RoundedCornerShape(size.radius))
            .background(if (pressed) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(tag)
            .padding(horizontal = size.pad),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = listText(size.font), color = if (hovered || pressed) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

/**
 * A file's name on one line. When there is no room it gives way in the middle, the extension staying in view
 * ("big-video-from-the-demo…day.mp4"), by our own measure: a desktop does not keep a Text's own middle ellipsis (B3's
 * render lost the percentage to it). It still reads as the whole name.
 */
@Composable
private fun Name(name: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val shown = remember(name, style, room) {
            if (room == Constraints.Infinity) {
                name
            } else {
                middleEllipsis(name) { measurer.measure(AnnotatedString(it), style, maxLines = 1, softWrap = false).size.width <= room }
            }
        }
        Text(
            shown,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(name) },
        )
    }
}

/**
 * [text] cut to what [fits]: in the middle, keeping the last three letters before its extension and the extension
 * ("big-video-from-the-demo…day.mp4"), as the prototype does; at the end when it has no extension to keep or is words
 * ("Supermux 1.2.0 re…"). The cut drops a dash, dot or space it would end on.
 */
internal fun middleEllipsis(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    val dot = text.lastIndexOf('.')
    val tail = if (dot >= 6 && text.none { it.isWhitespace() }) text.substring(dot - 3) else ""
    val head = text.substring(0, text.length - tail.length)
    fun cut(n: Int) = head.substring(0, n).trimEnd('-', '_', '.', ' ') + "…" + tail
    var lo = 0
    var hi = head.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (fits(cut(mid))) lo = mid else hi = mid - 1
    }
    return cut(lo)
}

/** One quiet word on a row: what it says, its test tag, and what it does. */
private class Word(val label: String, val tag: String, val act: () -> Unit)

private fun wordsFor(
    item: Download,
    actions: Set<FileAction>,
    onCancel: (Download) -> Unit,
    onRetry: (Download) -> Unit,
    onAction: (Download, FileAction) -> Unit,
    onInstall: (Download) -> Unit,
): List<Word> = when (item.state) {
    is DownloadState.Running -> listOf(Word("Cancel", "activity_cancel") { onCancel(item) })
    is DownloadState.Failed, DownloadState.Cancelled -> listOf(Word("Retry", "activity_retry") { onRetry(item) })
    is DownloadState.Done -> if (item.purpose == DownloadPurpose.Installer) {
        listOf(Word("Install", "activity_install") { onInstall(item) })
    } else {
        // One order everywhere, whatever order the host listed them in.
        FileAction.entries.filter { it in actions }.map { action ->
            Word(action.label, "activity_${action.name.lowercase()}") { onAction(item, action) }
        }
    }
}

/** What ↵ does on a row: its first word — never a running row's Cancel. */
private fun primaryOf(item: Download, words: List<Word>): Word? =
    if (item.state is DownloadState.Running) null else words.firstOrNull()

/** Our glyph for where an item is. */
private fun glyphOf(item: Download): ImageVector = when (item.state) {
    is DownloadState.Running -> DownloadGlyphs.Download
    is DownloadState.Done -> if (item.purpose == DownloadPurpose.Installer) DownloadGlyphs.Package else DownloadGlyphs.Done
    is DownloadState.Failed -> DownloadGlyphs.Failed
    DownloadState.Cancelled -> DownloadGlyphs.Cancelled
}

/** A phone row's glyph: teal while it runs, the alarm colour once it failed, dim otherwise (the user's Cancel included). */
private fun ColorScheme.sheetTint(item: Download): Color = when (item.state) {
    is DownloadState.Failed -> error
    is DownloadState.Running -> primary
    else -> onSurfaceVariant
}

/** A popover row's glyph: as a phone's, and teal on the selected row too. */
private fun ColorScheme.popoverTint(item: Download, selected: Boolean): Color = when {
    item.state is DownloadState.Failed -> error
    item.state is DownloadState.Running || selected -> primary
    else -> onSurfaceVariant
}

/** A phone row's figure after the name: how far it got, or the finished file's size. */
private fun sheetFigure(item: Download): String? = when (val s = item.state) {
    is DownloadState.Running -> s.percent()?.let { "$it%" }
    is DownloadState.Done -> s.file.size.takeIf { it > 0 }?.let(::sizeText)
    else -> null
}

/** A phone row's second line: what became of it, in words — "12 of 28 MB · about a minute left", "Downloaded". */
private fun sheetDetail(item: Download, timeLeft: String?): String = when (val s = item.state) {
    is DownloadState.Running -> s.total?.takeIf { it > 0 }?.let { total -> listOfNotNull(bytesSoFar(s.received, total), timeLeft).joinToString(" · ") }
        ?: "${sizeText(s.received)} so far"
    is DownloadState.Done -> if (item.purpose == DownloadPurpose.Installer) "Ready to install" else "Downloaded"
    is DownloadState.Failed -> failure("Failed", s.message)
    DownloadState.Cancelled -> "Cancelled"
}

/** A popover row's words after the name: "12 of 28 MB", "4.2 MB", "ready to install", "failed · connection lost". */
private fun popoverDetail(item: Download): String = when (val s = item.state) {
    is DownloadState.Running -> s.total?.takeIf { it > 0 }?.let { bytesSoFar(s.received, it) } ?: "${sizeText(s.received)} so far"
    is DownloadState.Done -> when {
        item.purpose == DownloadPurpose.Installer -> "ready to install"
        s.file.size > 0 -> sizeText(s.file.size)
        else -> "downloaded"
    }
    is DownloadState.Failed -> failure("failed", s.message)
    DownloadState.Cancelled -> "cancelled"
}

/** A popover row's figure, before its words: how far a running one got. */
private fun popoverFigure(item: Download): String? = (item.state as? DownloadState.Running)?.percent()?.let { "$it%" }

/** "Failed · connection lost". The center's own generic words add nothing to "Failed". */
private fun failure(word: String, message: String): String =
    if (message.isBlank() || message == GENERIC_FAILURE) word else "$word · $message"

/** What the center says when a source gave no reason. */
private const val GENERIC_FAILURE = "Download failed"

/** "12 of 28 MB": both in the total's unit, as the prototype writes them. */
internal fun bytesSoFar(received: Long, total: Long): String {
    val unit = SizeUnit.of(total)
    return "${unit.amount(received)} of ${unit.amount(total)} ${unit.label}"
}

/** "4.2 MB", "96 MB", "512 KB". */
internal fun sizeText(bytes: Long): String {
    val unit = SizeUnit.of(bytes)
    return "${unit.amount(bytes)} ${unit.label}"
}

private enum class SizeUnit(val bytes: Long, val label: String) {
    B(1, "B"),
    KB(1L shl 10, "KB"),
    MB(1L shl 20, "MB"),
    GB(1L shl 30, "GB"),
    ;

    /** Whole from 10 up, else one decimal without a trailing ".0": 12, 4.2, 96, 1. */
    fun amount(n: Long): String {
        if (this == B) return n.toString()
        val v = n.toDouble() / bytes
        if (v >= 10) return v.roundToLong().toString()
        val tenths = (v * 10).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    companion object {
        fun of(n: Long): SizeUnit = entries.lastOrNull { n >= it.bytes } ?: B
    }
}

/** How long is left, in the prototype's words: "about 3 min left", "about a minute left", "15 s left". */
internal fun etaText(remaining: Long, bytesPerSecond: Double): String {
    val s = remaining / bytesPerSecond
    return when {
        s >= 90 -> "about ${(s / 60).roundToInt()} min left"
        s >= 50 -> "about a minute left"
        else -> "${maxOf(5, (ceil(s / 5) * 5).toInt())} s left"
    }
}

/**
 * How fast a download has been arriving: bytes per second over the last few seconds of samples. Null until a second
 * of progress is in; a download that starts again from less is measured afresh.
 */
internal class RateSamples(private val windowMs: Long = 8_000, private val minSpanMs: Long = 1_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(atMs: Long, received: Long): Double? {
        if (samples.isNotEmpty() && received < samples.last().second) samples.clear()
        samples.addLast(atMs to received)
        while (samples.size > 2 && atMs - samples[1].first >= windowMs) samples.removeFirst()
        val (since, from) = samples.first()
        val span = atMs - since
        return if (span >= minSpanMs && received > from) (received - from) * 1000.0 / span else null
    }
}

/** "about a minute left" for a running download, from how fast it has arrived while the list was open; null until known. */
@Composable
private fun rememberTimeLeft(key: String, running: DownloadState.Running?): String? {
    val samples = remember(key) { RateSamples() }
    val clock = remember { TimeSource.Monotonic.markNow() }
    var rate by remember(key) { mutableStateOf<Double?>(null) }
    val received = running?.received
    LaunchedEffect(key, received) {
        if (received != null) rate = samples.add(clock.elapsedNow().inWholeMilliseconds, received)
    }
    if (running == null) return null
    val total = running.total ?: return null
    val perSecond = rate?.takeIf { it > 0 } ?: return null
    return etaText((total - running.received).coerceAtLeast(0), perSecond)
}

/** Whole percent, counted in bytes so 42 of 100 reads 42 (a float would make it 41). Null without a total. */
internal fun DownloadState.Running.percent(): Long? = total?.takeIf { it > 0 }?.let { (received * 100 / it).coerceIn(0, 100) }

internal fun DownloadState.Running.fraction(): Float? =
    total?.takeIf { it > 0 }?.let { (received.toFloat() / it.toFloat()).coerceIn(0f, 1f) }

private val FileAction.label: String
    get() = when (this) {
        FileAction.Open -> "Open"
        FileAction.Save -> "Save"
        FileAction.Share -> "Share"
    }

/** The hover/press wash of #91's palette: a breath of ink, a little more on a dark surface. */
internal val ColorScheme.washAlpha: Float get() = if (surface.luminance() < 0.5f) 0.06f else 0.045f

/** The prototype's faint ink (dots, hints): the dim ink at 70%. */
internal val ColorScheme.faint: Color get() = onSurfaceVariant.copy(alpha = 0.7f)

/** The list's type: Geist (Geist Mono for figures) at the prototype's sizes. */
@Composable
private fun listText(size: TextUnit, weight: FontWeight = FontWeight.Normal, mono: Boolean = false) =
    TextStyle(fontFamily = if (mono) GeistMonoFontFamily else GeistFontFamily, fontSize = size, fontWeight = weight)

/** A quiet word's size: the popover's, a phone row's, the phone header's Clear finished. */
private enum class WordSize(val height: Dp, val pad: Dp, val radius: Dp, val font: TextUnit) {
    Popover(26.dp, 6.dp, 7.dp, 12.5.sp),
    Sheet(40.dp, 9.dp, 9.dp, 14.sp),
    SheetHeader(44.dp, 10.dp, 10.dp, 13.5.sp),
}

private object ListTokens {
    /** A phone's rows sit this far in from the sheet's edges; each row adds its own 12 dp. */
    val SheetRowsInset = 10.dp

    /** A running row's progress line. */
    val Hairline = 1.5.dp
}

/**
 * Which download a list row is (its key), for tests and automation to find it by. A property of its own, not the
 * content description: TalkBack and VoiceOver read that aloud, and "att:host/0123…" is no words.
 */
internal val ActivityItemKey = SemanticsPropertyKey<String>("ActivityItemKey")
private var SemanticsPropertyReceiver.activityItemKey by ActivityItemKey
