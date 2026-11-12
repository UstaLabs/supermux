package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.webview.PageBox
import dev.supermux.webview.PageElement
import dev.supermux.webview.WebNavigator
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One pinned element and what the person said about it (#122 pick and comment). Its [box] follows the element. */
internal class Pick(val number: Int, val element: PageElement) {
    var comment by mutableStateOf("")
    var box by mutableStateOf(element.box)

    /** The element no longer resolves (the page changed): the pin greys out and stays. */
    var gone by mutableStateOf(false)
}

/** A Browser tab's pick mode and its pins: per tab, per client, until sent or cleared. */
internal class PickState {
    var on by mutableStateOf(false)
    var hover by mutableStateOf<PageElement?>(null)

    /** How many ancestors above the element under the pointer (↑ parent, ↓ back). */
    var up by mutableIntStateOf(0)
    val picks = mutableStateListOf<Pick>()
    var editing by mutableStateOf<Pick?>(null)
    private var next = 1

    val commented: List<Pick> get() = picks.filter { it.comment.isNotBlank() }

    fun add(element: PageElement): Pick = Pick(next++, element).also { picks += it }

    fun remove(pick: Pick) {
        picks.remove(pick)
        if (editing === pick) editing = null
        if (picks.isEmpty()) next = 1
    }

    /** Closes the open comment, keeping it if it says something. */
    fun closeComment() {
        val open = editing ?: return
        editing = null
        if (open.comment.isBlank()) remove(open)
    }

    fun clear() {
        picks.clear()
        editing = null
        hover = null
        next = 1
    }

    /** Esc: the open comment first, then pick mode. True if it did either. */
    fun escape(): Boolean = when {
        editing != null -> { closeComment(); true }
        on -> { on = false; hover = null; true }
        else -> false
    }
}

/**
 * Over the page (its viewport: device mode's too): while picking, the layer that takes the pointer (marked as an
 * overlay, so a macOS page doesn't), the hover box and its label; always, the pins and the open comment.
 */
@Composable
internal fun PickLayers(pick: PickState, navigator: WebNavigator) {
    val c = kitColors()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val density = LocalDensity.current
        fun fractions(at: Offset): Pair<Float, Float> = with(density) { at.x / w.toPx() to at.y / h.toPx() }

        // Pins follow their elements while there are any: the page scrolls, reflows, changes.
        LaunchedEffect(pick.picks.size) {
            while (pick.picks.isNotEmpty()) {
                val now = pick.picks.toList()
                val boxes = navigator.boxesOf(now.map { it.element.selector })
                now.forEachIndexed { i, p ->
                    val box = boxes.getOrNull(i)
                    p.gone = box == null
                    if (box != null) p.box = box
                }
                delay(PIN_REFRESH_MILLIS)
            }
        }

        if (pick.on) {
            val moves = remember { Channel<Offset>(Channel.CONFLATED) }
            LaunchedEffect(Unit) {
                for (at in moves) {
                    val (fx, fy) = fractions(at)
                    pick.hover = navigator.elementAt(fx, fy, pick.up)
                }
            }
            val focus = remember { FocusRequester() }
            // The layer has the keys (↑↓, Esc) whenever no comment is open: at the start and after each one closes.
            LaunchedEffect(pick.editing) { if (pick.editing == null) focus.requestFocus() }
            var last by remember { mutableStateOf<Offset?>(null) }
            Box(
                Modifier
                    .fillMaxSize()
                    .webViewOverlay()
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Escape -> pick.escape()
                            Key.DirectionUp -> { pick.up++; last?.let(moves::trySend); true }
                            Key.DirectionDown -> { if (pick.up > 0) pick.up--; last?.let(moves::trySend); true }
                            else -> false
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val at = event.changes.firstOrNull()?.position ?: continue
                                when (event.type) {
                                    PointerEventType.Move, PointerEventType.Enter -> {
                                        // A new place is a new element: back to it, not its ancestor.
                                        if (last != at) pick.up = 0
                                        last = at
                                        moves.trySend(at)
                                    }
                                    PointerEventType.Press -> {
                                        event.changes.forEach { it.consume() }
                                        val (fx, fy) = fractions(at)
                                        scope.launch {
                                            val element = navigator.elementAt(fx, fy, pick.up) ?: return@launch
                                            pick.closeComment()
                                            pick.editing = pick.add(element)
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                    .testTag("browser-pick-layer"),
            )
            pick.hover?.let { hover ->
                Box(
                    Modifier
                        .boxAt(hover.box, w, h)
                        .border(2.dp, c.accent, RoundedCornerShape(2.dp))
                        .background(c.accent.copy(alpha = 0.10f))
                        .testTag("browser-pick-hover"),
                )
                Text(
                    hover.label,
                    fontSize = 11.sp,
                    color = c.onAccent,
                    maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset((hover.box.x * w.toPx()).roundToInt(), ((hover.box.y * h.toPx()) - 22.dp.toPx()).coerceAtLeast(0f).roundToInt()) }
                        .clip(RoundedCornerShape(4.dp))
                        .background(c.accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        for (p in pick.picks) {
            val tag = "browser-pin-${p.number}" + if (p.gone) "-gone" else ""
            Box(
                Modifier
                    .offset { IntOffset(((p.box.x * w.toPx()) - 9.dp.toPx()).roundToInt(), ((p.box.y * h.toPx()) - 9.dp.toPx()).roundToInt()) }
                    .size(PIN)
                    .webViewOverlay(CircleShape)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (p.gone) c.faint else c.accent)
                    .clickable { pick.closeComment(); pick.editing = p }
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text("${p.number}", fontSize = 11.sp, color = c.onAccent)
            }
        }

        pick.editing?.let { open -> CommentPopover(open, pick, w, h) }
    }
}

/** The comment field under its element: Return keeps it, Shift+Return a new line, Esc closes it; the trash drops the pin. */
@Composable
private fun CommentPopover(open: Pick, pick: PickState, w: Dp, h: Dp) {
    val c = kitColors()
    val focus = remember(open) { FocusRequester() }
    LaunchedEffect(open) { focus.requestFocus() }
    val shape = RoundedCornerShape(8.dp)
    val width = 280.dp
    Column(
        Modifier
            .offset {
                val x = (open.box.x * w.toPx()).coerceIn(0f, (w - width).toPx().coerceAtLeast(0f))
                val below = (open.box.y + open.box.height) * h.toPx() + 6.dp.toPx()
                val y = if (below + 110.dp.toPx() < h.toPx()) below else ((open.box.y * h.toPx()) - 116.dp.toPx()).coerceAtLeast(0f)
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .width(width)
            .webViewOverlay(shape)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(c.chrome)
            .border(1.dp, c.line, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${open.number}", fontSize = 11.sp, color = c.onAccent, modifier = Modifier.clip(CircleShape).background(c.accent).padding(horizontal = 6.dp))
            Spacer(Modifier.width(6.dp))
            Text(open.element.label, fontSize = 11.5.sp, color = c.dim, maxLines = 1, modifier = Modifier.weight(1f))
            BarButton(KitGlyphs.Trash, "Delete this comment", "browser-pick-delete", { pick.remove(open) }, size = 22.dp)
        }
        BasicTextField(
            value = open.comment,
            onValueChange = { open.comment = it },
            textStyle = TextStyle(color = c.text, fontSize = 13.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(c.wash)
                .padding(8.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> { pick.closeComment(); true }
                        e.key == Key.Escape -> { pick.closeComment(); true }
                        else -> false
                    }
                }
                .testTag("browser-pick-comment"),
            decorationBox = { inner ->
                Box {
                    if (open.comment.isEmpty()) Text("What should change here?", color = c.faint, fontSize = 13.sp)
                    inner()
                }
            },
        )
    }
}

/** While picking: what to do, across the top of the page. */
@Composable
internal fun PickStrip(modifier: Modifier = Modifier) {
    val c = kitColors()
    Row(
        modifier.fillMaxWidth().height(28.dp).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp).testTag("browser-pick-strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Pick an element · ↑↓ parent / child · Esc to finish", fontSize = 12.sp, color = c.text, maxLines = 1)
    }
}

/** While comments wait: how many, and Clear. (Sending them to an agent is the next car.) */
@Composable
internal fun PickSendBar(pick: PickState, modifier: Modifier = Modifier) {
    val c = kitColors()
    val n = pick.commented.size
    Column(modifier.fillMaxWidth().testTag("browser-pick-sendbar")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(c.chrome).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (n == 1) "1 comment" else "$n comments", fontSize = 12.5.sp, color = c.text, modifier = Modifier.weight(1f))
            KitTextAction("Clear", { pick.clear() }, Modifier.testTag("browser-pick-clear"))
        }
    }
}

/** This modifier places and sizes a box at [box]'s viewport fractions of a [w] × [h] viewport. */
private fun Modifier.boxAt(box: PageBox, w: Dp, h: Dp): Modifier =
    this.offset(w * box.x, h * box.y).size(w * box.width, h * box.height)

private val PIN = 18.dp
private const val PIN_REFRESH_MILLIS = 300L
