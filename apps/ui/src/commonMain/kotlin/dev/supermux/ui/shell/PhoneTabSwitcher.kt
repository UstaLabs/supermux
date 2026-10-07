// The phone workspace's tab switcher — Chrome-on-Android's shape.
//
// A phone has no room for a strip of tabs: past three they scroll out of sight and each one is a
// thumb-width target. So the strip is gone, and the workspace's views live behind ONE count button
// (`[3]`) in the header. Tapping it opens a full-screen grid of cards — kind icon, title, close, and
// a snapshot of what the view last looked like — where a tap opens a view, a sideways swipe or the
// × closes it, a long-press opens the strip's old menu, and + adds one.
//
// Snapshots, not live previews: only the last three visited views stay composed (a WebView or a PTY
// is too heavy to pin more), so a card shows the frame its view last drew. A view that never drew
// on this device shows its kind icon instead.
package dev.supermux.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.proto.ViewDto
import dev.supermux.proto.stateString
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the header's count button needs: how many views, whether one has news, and how to open. */
data class PhoneTabsButton(val count: Int, val unread: Boolean, val onOpen: () -> Unit)

/**
 * Provided by the phone workspace only. A chat pane's own header draws the count button beside its
 * overflow when this is set; everywhere else (tablet, desktop) it is null and nothing changes.
 */
val LocalPhoneTabsButton = compositionLocalOf<PhoneTabsButton?> { null }

/** Chrome's tab-count square: the number in a rounded outline, with the unread dot on its corner. */
@Composable
fun PhoneTabCountButton(button: PhoneTabsButton, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = button.onOpen)
            .semantics { contentDescription = "${button.count} tabs" }
            .testTag("phone_tabs_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(19.dp).border(1.5.dp, cs.onSurface, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (button.count > 99) ":D" else button.count.toString(),
                color = cs.onSurface,
                fontSize = if (button.count > 9) 9.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (button.unread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(7.dp)
                    .background(LocalSemantics.current.success, CircleShape)
                    .testTag("phone_tabs_button_dot"),
            )
        }
    }
}

/**
 * Records everything this node draws into [layer] (and draws it as usual), so the last frame can
 * be turned into a card thumbnail with `layer.toImageBitmap()`. A hidden view stops drawing, so the
 * layer keeps the frame it showed before it was hidden — exactly the one the card wants.
 */
fun Modifier.recordInto(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/** A card's kind icon: what the view IS, read the same way [ViewHost] picks its body. */
internal fun ViewDto?.switcherIcon(): ImageVector = when (this?.kind) {
    "chat" -> Icons.AutoMirrored.Filled.Chat
    "terminal" -> Icons.Filled.Terminal
    "display" -> Icons.Filled.Monitor
    "editor" -> when (stateString("mode")) {
        "file" -> Icons.Filled.Code
        "diff" -> Icons.Filled.Difference
        else -> Icons.Filled.FolderOpen
    }
    else -> Icons.Filled.Code
}

/**
 * The full-screen grid. [cardMenu] wraps each card so the caller can hang its long-press menu on
 * it (the strip's Close Others / to the Right / Move to New Window).
 */
@Composable
internal fun PhoneTabSwitcher(
    viewIds: List<String>,
    selectedId: String?,
    viewFor: (String) -> ViewDto?,
    titleFor: (String) -> String,
    unread: (String) -> Boolean,
    thumbnails: Map<String, ImageBitmap>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    cardMenu: @Composable (id: String, content: @Composable () -> Unit) -> Unit,
    /** Where each card's centre sits, as a fraction of the switcher — the zoom in/out pivots on it. */
    onCardPlaced: (id: String, origin: TransformOrigin) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = viewIds.indexOf(selectedId).coerceAtLeast(0))
    Column(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainer)
            .onGloballyPositioned { root = it }
            .statusBarsPadding()
            .testTag("phone_tab_switcher"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.primary)
                    .clickable(onClick = onAdd)
                    .testTag("phone_add_view"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add view", tint = cs.onPrimary, modifier = Modifier.size(22.dp))
            }
            Text(
                if (viewIds.size == 1) "1 tab" else "${viewIds.size} tabs",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("phone_tabs_done")) { Text("Done") }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
        ) {
            items(viewIds, key = { it }) { id ->
                cardMenu(id) {
                    TabCard(
                        id = id,
                        title = titleFor(id),
                        icon = viewFor(id).switcherIcon(),
                        selected = id == selectedId,
                        unread = unread(id),
                        thumbnail = thumbnails[id],
                        onOpen = { onSelect(id) },
                        onClose = { onClose(id) },
                        modifier = Modifier.animateItem().onGloballyPositioned { c ->
                            val r = root ?: return@onGloballyPositioned
                            if (!r.isAttached || r.size.width == 0 || r.size.height == 0) return@onGloballyPositioned
                            val centre = r.localPositionOf(c, Offset(c.size.width / 2f, c.size.height / 2f))
                            onCardPlaced(id, TransformOrigin(centre.x / r.size.width, centre.y / r.size.height))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    id: String,
    title: String,
    icon: ImageVector,
    selected: Boolean,
    unread: Boolean,
    thumbnail: ImageBitmap?,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val swipe = remember(id) { Animatable(0f) }
    val density = LocalDensity.current
    val frame = if (selected) cs.primary else cs.surfaceContainerHigh
    // Chrome's press: the card gives a little under the thumb.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    val onFrame = if (selected) cs.onPrimary else cs.onSurface
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(0.74f)) {
        val widthPx = with(density) { maxWidth.toPx() }
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(swipe.value.roundToInt(), 0) }
                .graphicsLayer {
                    alpha = 1f - (abs(swipe.value) / widthPx).coerceIn(0f, 0.8f)
                    scaleX = pressScale
                    scaleY = pressScale
                }
                // Swipe sideways to close, as in Chrome. Past a third of the card it goes; short of
                // that it springs back. It also springs back after a close the caller had to ASK
                // about (a running agent, unsaved edits) — the card stays if the answer is no.
                .pointerInputHorizontalSwipe(
                    key = id,
                    onDrag = { dx -> scope.launch { swipe.snapTo(swipe.value + dx) } },
                    onEnd = {
                        scope.launch {
                            if (abs(swipe.value) > widthPx / 3) {
                                swipe.animateTo(if (swipe.value > 0) widthPx * 1.2f else -widthPx * 1.2f)
                                onClose()
                            }
                            swipe.animateTo(0f)
                        }
                    },
                )
                .clip(RoundedCornerShape(16.dp))
                .background(frame)
                .clickable(interactionSource = press, indication = null, onClick = onOpen)
                .testTag("tab-card-$id"),
        ) {
            Row(
                Modifier.fillMaxWidth().height(34.dp).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = onFrame, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                if (unread) {
                    Box(Modifier.size(6.dp).background(LocalSemantics.current.success, CircleShape).testTag("tab-dot-$id"))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    color = onFrame,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clickable(onClick = onClose).testTag("tab-close-$id"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close $title", tint = onFrame, modifier = Modifier.size(15.dp))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 3.dp, end = 3.dp, bottom = 3.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(cs.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().testTag("tab-thumb-$id"),
                    )
                } else {
                    Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

private fun Modifier.pointerInputHorizontalSwipe(key: Any, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    then(
        Modifier.pointerInput(key) {
            detectHorizontalDragGestures(
                onDragEnd = onEnd,
                onDragCancel = onEnd,
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    onDrag(dx)
                },
            )
        },
    )
