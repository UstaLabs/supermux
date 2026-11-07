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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
 * Pure: the caller passes the items, newest first as the center lists them, and the callbacks.
 */
@Composable
fun ActivityBar(items: List<Download>, onOpen: () -> Unit, onClearFinished: () -> Unit, modifier: Modifier = Modifier) {
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
            .background(cs.surfaceContainerHigh)
            .clickable(onClickLabel = "Show downloads", role = Role.Button, onClick = onOpen)
            // The ✕ brings its own padding to the end.
            .padding(start = Space.md, end = if (clearable) Space.xs else Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.size(IconSize.lg), contentAlignment = Alignment.Center) {
            val state = shown.state
            if (state is DownloadState.Running) ProgressRing(state) else StateGlyph(state)
        }
        Text(
            activityLine(shown),
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            // The extension and the percentage stay in view: "very-long-rep…final.pdf · 42%".
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
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
    val cs = MaterialTheme.colorScheme
    val state = item.state
    Column(
        Modifier
            .testTag("activity_item")
            .semantics { contentDescription = item.key }
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.sm, top = Space.sm),
    ) {
        Row(
            Modifier.padding(end = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            StateGlyph(state)
            Text(
                activityLine(item),
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
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

/** The one line an item reads as, in the bar and in the list. */
internal fun activityLine(item: Download): String = when (val state = item.state) {
    is DownloadState.Running -> "${item.name} · ${state.percent()?.let { "$it%" } ?: formatFileSize(state.received)}"
    is DownloadState.Done ->
        if (item.purpose == DownloadPurpose.Installer) "${item.name} is ready to install" else "${item.name} downloaded"
    is DownloadState.Failed -> "${item.name} — download failed"
    DownloadState.Cancelled -> "${item.name} — cancelled"
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

private object ActivityBarTokens {
    /** On a phone the whole bar is one button: Material's touch target. */
    val HeightTouch = 48.dp

    /** In the sidebar's footer, as tall as the update card's buttons. */
    val HeightPointer = 36.dp
}
