package dev.supermux.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.editor.formatFileSize
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.Sizes
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.work.Download
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadState

/**
 * The activity bar (#95): one slim line while anything is listed — running, failed, cancelled or finished (R2) —
 * under a phone's top bar and in the wide sidebar's footer. It reads as the most relevant item (the newest
 * running one, else the newest), counts the rest ("+N"), and offers ✕ to clear what has ended once nothing runs;
 * while something runs there is no ✕, only each item's Cancel in the list. A tap opens the list ([onOpen]).
 *
 * Pure: the caller passes the items, newest first as the center lists them, and the callbacks. [containerColor] is
 * the caller's too: under a phone's top bar the bar takes that bar's tone, in the sidebar's footer a sheet's.
 */
@Composable
fun ActivityBar(
    items: List<Download>,
    onOpen: () -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val pointer = LocalPointerAvailable.current
    val shown = items.firstOrNull { it.state is DownloadState.Running } ?: items.first()
    val clearable = items.none { it.state is DownloadState.Running }
    Row(
        modifier
            .testTag("activity_bar")
            .fillMaxWidth()
            .heightIn(min = if (pointer) ActivityBarTokens.HeightPointer else ActivityBarTokens.HeightTouch)
            .background(containerColor)
            .clickable(onClickLabel = "Show downloads", role = Role.Button, onClick = onOpen)
            // The ✕ brings its own padding to the end.
            .padding(start = Space.md, end = if (clearable) Space.xs else Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        ActivityGlyph(shown.state)
        ActivityLine(shown, Modifier.weight(1f))
        if (items.size > 1) {
            Text(
                "+${items.size - 1}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.testTag("activity_bar_more"),
            )
        }
        if (clearable) {
            IconButton(
                onClick = onClearFinished,
                // A pointer gets the sidebar's compact button; touch keeps Material's 48 dp target.
                modifier = (if (pointer) Modifier.size(Sizes.iconButton) else Modifier).testTag("activity_bar_clear"),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear finished downloads",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.iconSm),
                )
            }
        }
    }
}

/**
 * Every listed download (#95) with what can be done with it: Cancel while it runs, Retry once it failed or was
 * cancelled, and once done the host's own [actions] for a file (R3) or Install for an app update. What the phone's
 * sheet and the wide popover show; pure, like [ActivityBar].
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
) {
    Column(modifier.testTag("activity_list").verticalScroll(rememberScrollState())) {
        items.forEach { item ->
            key(item.key) {
                ActivityRow(item) {
                    when (item.state) {
                        is DownloadState.Running -> RowButton("Cancel", "activity_cancel") { onCancel(item) }
                        is DownloadState.Failed, DownloadState.Cancelled -> RowButton("Retry", "activity_retry") { onRetry(item) }
                        is DownloadState.Done -> if (item.purpose == DownloadPurpose.Installer) {
                            RowButton("Install", "activity_install") { onInstall(item) }
                        } else {
                            // One order everywhere, whatever order the host listed them in.
                            FileAction.entries.filter { it in actions }.forEach { action ->
                                RowButton(action.label, "activity_${action.name.lowercase()}") { onAction(item, action) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One item of the list: its line, its progress while it runs, and [buttons] under them at the end. */
@Composable
private fun ActivityRow(item: Download, buttons: @Composable RowScope.() -> Unit) {
    val state = item.state
    Column(
        Modifier
            .testTag("activity_item")
            // One stop that reads what it shows ("notes.pdf downloaded"); its buttons stay stops of their own.
            .semantics(mergeDescendants = true) { activityItemKey = item.key }
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.sm, top = Space.sm),
    ) {
        Row(
            Modifier.padding(end = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            StateGlyph(state)
            ActivityLine(item, Modifier.weight(1f))
        }
        if (state is DownloadState.Running) {
            // Under the line, not under the glyph.
            val track = Modifier.fillMaxWidth().padding(start = Sizes.iconSm + Space.md, end = Space.sm, top = Space.sm)
            val fraction = state.fraction()
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = track) else LinearProgressIndicator(track)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
    }
}

@Composable
private fun RowButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
}

/** What the bar leads with, and the collapsed sidebar's rail shows: a ring while it runs, else its state's glyph. */
@Composable
internal fun ActivityGlyph(state: DownloadState) {
    Box(Modifier.size(IconSize.lg), contentAlignment = Alignment.Center) {
        if (state is DownloadState.Running) ProgressRing(state) else StateGlyph(state)
    }
}

/** The bar's ring: determinate once the total is known, else turning. */
@Composable
private fun ProgressRing(state: DownloadState.Running) {
    val fraction = state.fraction()
    if (fraction != null) {
        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(Sizes.iconSm), strokeWidth = Stroke.thin)
    } else {
        CircularProgressIndicator(modifier = Modifier.size(Sizes.iconSm), strokeWidth = Stroke.thin)
    }
}

/** What state an item is in, at a glance; its line says it in words, so the glyph is not announced. */
@Composable
private fun StateGlyph(state: DownloadState) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when (state) {
        is DownloadState.Running -> Icons.Outlined.FileDownload to cs.onSurfaceVariant
        is DownloadState.Done -> Icons.Outlined.DownloadDone to cs.primary
        is DownloadState.Failed -> Icons.Outlined.ErrorOutline to cs.error
        // The user's own Cancel is no error: the glyph, without the alarm colour.
        DownloadState.Cancelled -> Icons.Outlined.ErrorOutline to cs.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.iconSm))
}

/**
 * The line an item reads as, in the bar and in the list: its name, then what became of it — "big-video.mp4 · 42%",
 * "notes.pdf downloaded". The name gives way when there is no room ("big-video-from-the-dem… · 42%"); the status
 * never does. One Text with a middle ellipsis does not hold on a desktop: in the sidebar's footer it drew
 * "big-video-from-the-demo-day.mp4 · …", the percentage lost.
 */
@Composable
private fun ActivityLine(item: Download, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyMedium
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ActivityBarTokens.WordGap)) {
        Text(
            item.name,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // No wider than the name, so the status follows it; never so wide that the status has no room.
            modifier = Modifier.weight(1f, fill = false),
        )
        activitySeparator(item.state)?.let {
            // Between the two, for the eye: nothing to read aloud.
            Text(it, color = color, style = style, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(activityStatus(item), color = color, style = style, maxLines = 1, softWrap = false)
    }
}

/** The one line an item reads as, in words: what the collapsed rail's tile says for its ring. */
internal fun activityLine(item: Download): String =
    listOfNotNull(item.name, activitySeparator(item.state), activityStatus(item)).joinToString(" ")

/** What became of an item, after its name: how far it got, or how it ended. */
private fun activityStatus(item: Download): String = when (val state = item.state) {
    is DownloadState.Running -> state.percent()?.let { "$it%" } ?: formatFileSize(state.received)
    is DownloadState.Done -> if (item.purpose == DownloadPurpose.Installer) "is ready to install" else "downloaded"
    is DownloadState.Failed -> "download failed"
    DownloadState.Cancelled -> "cancelled"
}

/** A dot before how far it got, a dash before a failure or a cancel; a finished one reads on: "notes.pdf downloaded". */
private fun activitySeparator(state: DownloadState): String? = when (state) {
    is DownloadState.Running -> "·"
    is DownloadState.Failed, DownloadState.Cancelled -> "—"
    is DownloadState.Done -> null
}

/** Whole percent, counted in bytes so 42 of 100 reads 42 (a float would make it 41). Null without a total. */
private fun DownloadState.Running.percent(): Long? = total?.takeIf { it > 0 }?.let { (received * 100 / it).coerceIn(0, 100) }

private fun DownloadState.Running.fraction(): Float? =
    total?.takeIf { it > 0 }?.let { (received.toFloat() / it.toFloat()).coerceIn(0f, 1f) }

private val FileAction.label: String
    get() = when (this) {
        FileAction.Open -> "Open"
        FileAction.Save -> "Save"
        FileAction.Share -> "Share"
    }

/**
 * Which download a list row is (its key), for tests and automation to find it by. A property of its own, not the
 * content description: TalkBack and VoiceOver read that aloud, and "att:host/0123…" is no words.
 */
internal val ActivityItemKey = SemanticsPropertyKey<String>("ActivityItemKey")
private var SemanticsPropertyReceiver.activityItemKey by ActivityItemKey

private object ActivityBarTokens {
    /** On a phone the whole bar is one button: Material's touch target. */
    val HeightTouch = 48.dp

    /** In the sidebar's footer, as tall as the update card's buttons. */
    val HeightPointer = 36.dp

    /** Between the words of a line drawn in pieces (name · status): a space's width. */
    val WordGap = 4.dp
}
