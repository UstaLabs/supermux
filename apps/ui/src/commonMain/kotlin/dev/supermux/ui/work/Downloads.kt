package dev.supermux.ui.work

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
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
 * The sidebar's bottom bar with the downloads in it (#95, design A) — the same bar on a phone's Home and on a desktop.
 * [bar] lays the bar out and puts the `ring` it is handed among its icons: the downloads' ring, or null outside a
 * fleet and while nothing is listed. The list the ring opens stands on the whole bar — the update card's place
 * included — as a popover along its start edge where there is a pointer on a wide window; else as our sheet.
 *
 * Every action starts on the fleet's scope, `CoroutineStart.UNDISPATCHED` (a browser only honours a download click
 * inside the gesture), so a Save the user started still finishes when the list or the screen goes away meanwhile.
 */
@Composable
fun DownloadsBar(bar: @Composable (ring: (@Composable () -> Unit)?) -> Unit) {
    val downloads = LocalDownloads.current
    if (downloads == null) {
        bar(null)
        return
    }
    val items by downloads.items.collectAsState()
    val scope = rememberRetainedScope()
    var open by rememberSaveable { mutableStateOf(false) }
    // Gone with the last item (Clear finished): the next download does not find its list already open.
    if (items.isEmpty() && open) SideEffect { open = false }
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) { OnTheBar(gap = PanelTokens.Gap.roundToPx(), inset = PanelTokens.BarInset.roundToPx()) }
    }
    val ring: (@Composable () -> Unit)? = if (items.isEmpty()) {
        null
    } else {
        { DownloadsButton(items, open, onClick = { open = true }) }
    }
    Box {
        bar(ring)
        if (open && items.isNotEmpty()) DownloadsPanel(downloads, items, scope, position, onDismiss = { open = false })
    }
}

/**
 * The collapsed sidebar's downloads (#95, design A): the bottom bar's ring as a rail tile, on the rail's own tone just
 * above its bottom sheet, while anything is listed. It opens the same list, beside the rail. Nothing otherwise.
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
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) {
            BesideTheTile(gap = PanelTokens.Gap.roundToPx(), drop = PanelTokens.RailDrop.roundToPx(), margin = PanelTokens.Margin.roundToPx())
        }
    }
    // The air the rail keeps between its tiles, between the ring and the bottom sheet under it.
    Box(modifier.padding(bottom = PanelTokens.RailGap)) {
        Box {
            RailTile(onClick = { open = true }, tag = "rail_downloads", label = "Downloads") {
                Box(
                    Modifier
                        .size(RingTokens.RailTile)
                        // Washed while its list is open, as the bottom bar's ring is.
                        .background(if (open) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
                        .downloadsSemantics(items),
                ) {
                    DownloadsRing(
                        items,
                        rest = 16.dp,
                        tint = cs.onSurfaceVariant,
                        surface = cs.surfaceContainerHigh,
                        dotInset = 6.dp,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            if (open) DownloadsPanel(downloads, items, scope, position, onDismiss = { open = false })
        }
    }
}

/** Every listed item with its words, in the container this host shows a list in. */
@Composable
private fun DownloadsPanel(
    downloads: DownloadCenter,
    items: List<Download>,
    scope: CoroutineScope,
    position: PopupPositionProvider,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatform.current
    // A popover with a pointer, as the Usage card is; a wide touch-only host (a tablet, an unfolded Fold) gets the sheet.
    val popover = LocalWindowWidthClass.current != WindowWidthClass.Compact && LocalPointerAvailable.current
    val list: @Composable (Modifier) -> Unit = { modifier ->
        ActivityList(
            items = items,
            actions = platform.files.fileActions,
            onCancel = { downloads.cancel(it.key) },
            onRetry = { downloads.retry(it.key) },
            onAction = { item, action ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) { handOver(platform, downloads, item, action) }
            },
            onInstall = { item -> scope.launch(start = CoroutineStart.UNDISPATCHED) { install(platform, item) } },
            modifier = modifier,
            onClearFinished = { downloads.clearFinished() },
            popover = popover,
        )
    }
    if (popover) DownloadsPopover(position, onDismiss, list) else DownloadsSheet(onDismiss, list)
}

/** The list's Install: an app update's installer, handed back to the updater that fetched it. */
private suspend fun install(platform: Platform, item: Download) {
    val installer = (item.state as? DownloadState.Done)?.file?.handle as? DownloadedInstaller ?: return
    platform.updates.install(installer)
}

/**
 * A phone's list: our sheet, not Material's look — the sidebar's own tone, 18 dp corners, our grab handle, a scrim and
 * no shadow. Material's sheet still carries it: the drag down, the back gesture, the system bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsSheet(onDismiss: () -> Unit, list: @Composable (Modifier) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("activity_sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = PanelTokens.SheetCorner, topEnd = PanelTokens.SheetCorner),
        containerColor = cs.surfaceContainerHigh,
        contentColor = cs.onSurface,
        tonalElevation = 0.dp,
        scrimColor = cs.scrim.copy(alpha = if (dark) 0.46f else 0.30f),
        dragHandle = { GrabHandle() },
    ) {
        list(Modifier.heightIn(max = window * PanelTokens.SheetMaxShare))
        Spacer(Modifier.height(PanelTokens.SheetBottom))
    }
}

/** Our grab handle: a short, soft bar. */
@Composable
private fun GrabHandle() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.faint.copy(alpha = 0.42f)),
    )
}

/**
 * A desktop's list: a popover in the app's own chrome — the sidebar's tone, a hairline edge, a soft shadow — where
 * [position] stands it. Esc, a click outside or Back closes it.
 */
@Composable
private fun DownloadsPopover(position: PopupPositionProvider, onDismiss: () -> Unit, list: @Composable (Modifier) -> Unit) {
    ModalHost {}
    // Read here, in the window: inside the popup it would be the popup's own (Android's is a window of its own).
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.let { DpSize(it.width.toDp(), it.height.toDp()) } }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val cs = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier
                .width(min(PanelTokens.PopoverWidth, window.width - PanelTokens.Margin * 2))
                .heightIn(max = min(PanelTokens.PopoverMaxHeight, window.height - PanelTokens.Margin * 2))
                .testTag("activity_popover")
                // The list holds the focus (its arrows and ↵); Esc passes through here on the way.
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            shape = RoundedCornerShape(PanelTokens.PopoverCorner),
            color = cs.surfaceContainerHigh,
            contentColor = cs.onSurface,
            border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.85f)),
            shadowElevation = 12.dp,
        ) {
            list(Modifier.padding(PanelTokens.PopoverPadding))
        }
    }
}

/**
 * Stands the popover on the bottom bar: [inset] in from the bar's start edge (where its sheet starts), [gap] above its
 * top — over the update card's place too. Under the top, clamped to the window, when there is no room above.
 */
internal class OnTheBar(private val gap: Int, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left + inset
        } else {
            anchorBounds.right - inset - popupContentSize.width
        }
        val x = start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above >= 0) {
            above
        } else {
            (anchorBounds.top + gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}

/**
 * Beside the collapsed rail: [gap] past the tile's end edge (the rail's), growing up from [drop] below the tile's
 * bottom, at least [margin] from the window's top and bottom.
 */
internal class BesideTheTile(private val gap: Int, private val drop: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right + gap
        } else {
            anchorBounds.left - gap - popupContentSize.width
        }
        val x = start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val lowest = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        val y = (anchorBounds.bottom + drop - popupContentSize.height).coerceIn(margin, lowest)
        return IntOffset(x, y)
    }
}

private object PanelTokens {
    /** Between the bar (or the rail's tile) and the popover standing on it. */
    val Gap = 6.dp

    /** The bar's sheet starts this far in from the sidebar's edge; the popover lines up with it. */
    val BarInset = 6.dp

    /** Below the rail tile's bottom, where the popover beside it ends. */
    val RailDrop = 4.dp

    /** Between the rail's ring and the bottom sheet under it. */
    val RailGap = 4.dp

    /** The least room a popover leaves to the window's edges. */
    val Margin = 8.dp

    /** The prototype's popover. */
    val PopoverWidth = 470.dp
    val PopoverMaxHeight = 560.dp
    val PopoverCorner = 12.dp
    val PopoverPadding = 6.dp

    /** The prototype's sheet: its corners, the most of the window it takes, and the air under its last row. */
    val SheetCorner = 18.dp
    const val SheetMaxShare = 0.78f
    val SheetBottom = 16.dp
}
