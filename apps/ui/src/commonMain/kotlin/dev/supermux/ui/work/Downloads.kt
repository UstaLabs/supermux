package dev.supermux.ui.work

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.DownloadedInstaller
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.shell.RailTile
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.ModalHost
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The fleet's [DownloadCenter] (#95), provided wherever a fleet is. Null outside one (a test, a preview). */
val LocalDownloads = staticCompositionLocalOf<DownloadCenter?> { null }

/**
 * [LocalDownloads], or — outside a fleet — a center that lives as long as this composition (today's lifetime): a
 * test or a preview still gets downloads that work, and they end with the screen that holds them.
 */
@Composable
fun rememberDownloads(): DownloadCenter {
    val fleets = LocalDownloads.current
    val scope = rememberCoroutineScope()
    return fleets ?: remember(scope) { DownloadCenter(scope) }
}

/**
 * The bar plus its panel (#95): a `ModalBottomSheet` on a Compact width, a popover anchored to the bar when wide —
 * with a pointer, as the Usage card is; a wide touch-only host (a tablet, an unfolded Fold) gets the sheet. Reads
 * [LocalDownloads] and `LocalPlatform` (for `fileActions`, `updates.install`), wires every callback to the center
 * and to [handOver]; bar actions start their coroutine with `CoroutineStart.UNDISPATCHED` (a browser only honours a
 * download click inside the gesture), on the fleet's scope, so a Save the user started still finishes when the
 * panel or the screen goes away meanwhile.
 *
 * Draws nothing outside a fleet, or while nothing is listed. [containerColor] is the bar's ([ActivityBar]).
 */
@Composable
fun ActivityBarWithPanel(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val downloads = LocalDownloads.current ?: return
    val items by downloads.items.collectAsState()
    val scope = rememberRetainedScope()
    var open by rememberSaveable { mutableStateOf(false) }
    if (items.isEmpty()) {
        // Gone with the last item (the ✕): the next download does not find its list already open.
        if (open) SideEffect { open = false }
        return
    }
    Box(modifier) {
        ActivityBar(items, onOpen = { open = true }, onClearFinished = { downloads.clearFinished() }, containerColor = containerColor)
        if (open) ActivityPanel(downloads, items, scope, onDismiss = { open = false })
    }
}

/**
 * [ActivityBarWithPanel] only on a Compact width — what goes under each phone top bar (wide hosts have it in the
 * sidebar footer).
 */
@Composable
fun PhoneActivityBar(modifier: Modifier = Modifier) {
    if (LocalWindowWidthClass.current != WindowWidthClass.Compact) return
    ActivityBarWithPanel(modifier)
}

/**
 * The collapsed sidebar's rail: while anything is listed, a tile with the most relevant item's ring (the newest
 * running one, else the newest's state), opening the same list as the bar. Nothing otherwise.
 */
@Composable
fun ActivityRailIcon(modifier: Modifier = Modifier) {
    val downloads = LocalDownloads.current ?: return
    val items by downloads.items.collectAsState()
    val scope = rememberRetainedScope()
    var open by rememberSaveable { mutableStateOf(false) }
    if (items.isEmpty()) {
        if (open) SideEffect { open = false }
        return
    }
    val shown = items.firstOrNull { it.state is DownloadState.Running } ?: items.first()
    Box(modifier) {
        RailTile(onClick = { open = true }, tag = "activity_rail", label = "Downloads") {
            // A tile has no line to read: it says what the bar would.
            Box(Modifier.semantics { contentDescription = "Downloads: ${activityLine(shown)}" }) { ActivityGlyph(shown.state) }
        }
        if (open) ActivityPanel(downloads, items, scope, onDismiss = { open = false })
    }
}

/** Every listed item with its controls, in the container this host shows a list in. */
@Composable
private fun ActivityPanel(downloads: DownloadCenter, items: List<Download>, scope: CoroutineScope, onDismiss: () -> Unit) {
    val platform = LocalPlatform.current
    val popover = LocalWindowWidthClass.current != WindowWidthClass.Compact && LocalPointerAvailable.current
    val body: @Composable () -> Unit = {
        Text(
            "Downloads",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm).semantics { heading() },
        )
        ActivityList(
            items = items,
            actions = platform.files.fileActions,
            onCancel = { downloads.cancel(it.key) },
            onRetry = { downloads.retry(it.key) },
            onAction = { item, action ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) { handOver(platform, downloads, item, action) }
            },
            onInstall = { item -> scope.launch(start = CoroutineStart.UNDISPATCHED) { install(platform, item) } },
        )
    }
    if (popover) ActivityPopover(onDismiss, body) else ActivitySheet(onDismiss, body)
}

/** The bar's Install: an app update's installer, handed back to the updater that fetched it. */
private suspend fun install(platform: Platform, item: Download) {
    val installer = (item.state as? DownloadState.Done)?.file?.handle as? DownloadedInstaller ?: return
    platform.updates.install(installer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivitySheet(onDismiss: () -> Unit, body: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("activity_sheet")) {
        body()
        Spacer(Modifier.height(Space.lg))
    }
}

/** Anchored to the bar (or the rail's tile) it was opened from, as the Usage card is to its icon. */
@Composable
private fun ActivityPopover(onDismiss: () -> Unit, body: @Composable () -> Unit) {
    ModalHost {}
    val gap = with(LocalDensity.current) { Space.sm.roundToPx() }
    val position = remember(gap) { AboveTheAnchor(gap) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Surface(
            modifier = Modifier
                .width(ActivityPanelTokens.PopoverWidth)
                .heightIn(max = ActivityPanelTokens.PopoverMaxHeight)
                .testTag("activity_popover")
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = Space.sm, bottom = Space.xs)) { body() }
        }
    }
}

/**
 * Above the anchor, along its start edge — over the sidebar's footer, or beside the collapsed rail — or below it
 * when there is no room above.
 */
private class AboveTheAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val x = start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= 0) above else (anchorBounds.bottom + gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

private object ActivityPanelTokens {
    val PopoverWidth = 360.dp
    val PopoverMaxHeight = 440.dp
}
