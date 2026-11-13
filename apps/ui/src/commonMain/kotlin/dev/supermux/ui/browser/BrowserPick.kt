package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.browser_comment_remove
import dev.supermux.ui.resources.browser_comment_to
import dev.supermux.ui.resources.browser_comment_close
import dev.supermux.ui.resources.browser_comment_sent
import dev.supermux.ui.resources.browser_comment_seen
import dev.supermux.ui.resources.browser_comment_done
import dev.supermux.ui.resources.browser_comment_answered
import dev.supermux.ui.resources.browser_comment_dismissed
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
internal class Pick(val number: Int, element: PageElement) {
    /** The element it is on: on touch, tapping it again takes its container ([level] ancestors above what was tapped). */
    var element by mutableStateOf(element)
    var level = 0
    /** What the first tap named, so a second tap on it is "its container", not a new comment. */
    val tapped = element.selector
    var comment by mutableStateOf("")
    var box by mutableStateOf(element.box)

    /** Its id once sent and stored (#122 phase 4): then it's the agent's, and the pin shows what the agent did. */
    var commentId by mutableStateOf<String?>(null)

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

    /**
     * On touch, what a tap selected (shown as hover is on a desktop), before it's commented: what was tapped, and how
     * many containers up a second tap went.
     */
    var selectedTapped: String? = null
    var selectedLevel = 0

    /** On touch: comment the selection. */
    fun commentSelection() {
        val element = hover ?: return
        closeComment()
        editing = add(element)
        hover = null
        selectedTapped = null
    }
    private var next = 1

    /** The comments still to send: with words, not yet sent. */
    val commented: List<Pick> get() = picks.filter { it.comment.isNotBlank() && it.commentId == null }

    fun add(element: PageElement): Pick = Pick(next++, element).also { picks += it }

    fun remove(pick: Pick) {
        picks.remove(pick)
        if (editing === pick) editing = null
        if (picks.isEmpty() && removed == null) next = 1
    }

    /** The comment the trash took last, and where it was, for a few seconds' Undo. */
    var removed by mutableStateOf<Pair<Int, Pick>?>(null)

    fun removeWithUndo(pick: Pick) {
        val at = picks.indexOf(pick).takeIf { it >= 0 } ?: return
        removed = at to pick
        remove(pick)
    }

    fun undo() {
        val (at, pick) = removed ?: return
        removed = null
        picks.add(at.coerceAtMost(picks.size), pick)
    }

    /** Closes the open comment, keeping it if it says something. */
    fun closeComment() {
        val open = editing ?: return
        editing = null
        if (open.comment.isBlank()) remove(open)
    }

    fun clear() {
        removed = null
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
internal fun PickLayers(
    pick: PickState,
    navigator: WebNavigator,
    touch: Boolean = false,
    /** The sent comments' states by id (#122 phase 4), as the agent answers them. */
    states: Map<String, dev.supermux.net.BrowserCommentDto> = emptyMap(),
) {
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
                                    PointerEventType.Move, PointerEventType.Enter -> if (!touch) {
                                        // A new place is a new element: back to it, not its ancestor.
                                        if (last != at) pick.up = 0
                                        last = at
                                        moves.trySend(at)
                                    }
                                    PointerEventType.Press -> {
                                        event.changes.forEach { it.consume() }
                                        val (fx, fy) = fractions(at)
                                        scope.launch {
                                            val element = navigator.elementAt(fx, fy, if (touch) 0 else pick.up) ?: return@launch
                                            if (touch) {
                                                // A tap selects, as hover does on a desktop; the same element again is its
                                                // container (§5.2); "Add comment" comments it (Ahmet, 2026-11-12).
                                                if (pick.selectedTapped == element.selector && pick.hover != null) {
                                                    val container = navigator.elementAt(fx, fy, pick.selectedLevel + 1) ?: return@launch
                                                    pick.selectedLevel++
                                                    pick.hover = container
                                                } else {
                                                    pick.selectedTapped = element.selector
                                                    pick.selectedLevel = 0
                                                    pick.hover = element
                                                }
                                                return@launch
                                            }
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
                if (touch) {
                    // Under the selection (above it when it reaches the bottom): comment what's outlined.
                    Box(
                        Modifier
                            .offset {
                                val below = ((hover.box.y + hover.box.height) * h.toPx()) + 6.dp.toPx()
                                val y = if (below + 44.dp.toPx() < h.toPx()) below else ((hover.box.y * h.toPx()) - 72.dp.toPx()).coerceAtLeast(0f)
                                IntOffset((hover.box.x * w.toPx()).coerceIn(0f, (w - 140.dp).toPx().coerceAtLeast(0f)).roundToInt(), y.roundToInt())
                            }
                            .height(34.dp)
                            .webViewOverlay(RoundedCornerShape(8.dp))
                            // The kit's chip (the prototype's): our chrome, a hairline in the accent, our type; no
                            // elevation and no ripple (Ahmet: the first one looked Android).
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.chrome)
                            .border(1.dp, c.accent, RoundedCornerShape(8.dp))
                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { pick.commentSelection() }
                            .padding(horizontal = 12.dp)
                            .testTag("browser-pick-add"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.Icon(KitGlyphs.Plus, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
                            Text("Add comment", fontSize = 13.sp, color = c.text)
                        }
                    }
                }
            }
        }

        for (p in pick.picks) {
            val status = p.commentId?.let { states[it]?.status ?: "sent" }
            val tag = "browser-pin-${p.number}" + when {
                status != null -> "-$status"
                p.gone -> "-gone"
                else -> ""
            }
            Box(
                Modifier
                    // On its element's corner, but always whole on the page (an element at its edge: seen on the Fold).
                    .offset {
                        val x = ((p.box.x * w.toPx()) - 9.dp.toPx()).coerceIn(2.dp.toPx(), (w - PIN - 2.dp).toPx().coerceAtLeast(2.dp.toPx()))
                        val y = ((p.box.y * h.toPx()) - 9.dp.toPx()).coerceIn(2.dp.toPx(), (h - PIN - 2.dp).toPx().coerceAtLeast(2.dp.toPx()))
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .size(PIN)
                    .webViewOverlay(CircleShape)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        when (status) {
                            "done" -> c.ok
                            "dismissed" -> c.faint
                            null -> if (p.gone) c.faint else c.accent
                            else -> c.accent
                        },
                    )
                    .combinedClickable(
                        onClick = { pick.closeComment(); pick.editing = p },
                        // An invisible shortcut on touch: a long press deletes, as the trash does (§5.2).
                        onLongClick = { pick.removeWithUndo(p) },
                    )
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                // A done comment is a check; the others keep their number.
                Text(if (status == "done") "✓" else "${p.number}", fontSize = 11.sp, color = c.onAccent)
            }
        }

        pick.editing?.let { open ->
            val sent = open.commentId?.let { states[it] ?: dev.supermux.net.BrowserCommentDto(it, "", "", open.number, selector = open.element.selector, comment = open.comment, status = "sent", createdAt = "", updatedAt = "") }
            when {
                sent != null -> SentComment(open, sent, pick, w, h)
                touch -> CommentSheet(open, pick)
                else -> CommentPopover(open, pick, w, h)
            }
        }
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
            BarButton(KitGlyphs.Trash, "Delete this comment", "browser-pick-delete", { pick.removeWithUndo(open) }, size = 22.dp)
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

/** A status as the pin's card says it, in the app's language: "Done · padding fixed". */
@Composable
internal fun statusLine(c: dev.supermux.net.BrowserCommentDto): String {
    val word = localized(
        when (c.status) {
            "seen" -> Res.string.browser_comment_seen
            "done" -> Res.string.browser_comment_done
            "answered" -> Res.string.browser_comment_answered
            "dismissed" -> Res.string.browser_comment_dismissed
            else -> Res.string.browser_comment_sent
        },
    )
    return if (c.note.isNullOrBlank()) word else "$word · ${c.note}"
}

/** A sent comment's card under its element: its words and what the agent did with it; Remove drops the pin. */
@Composable
private fun SentComment(open: Pick, comment: dev.supermux.net.BrowserCommentDto, pick: PickState, w: Dp, h: Dp) {
    val c = kitColors()
    val shape = RoundedCornerShape(8.dp)
    val width = 280.dp
    Column(
        Modifier
            .offset {
                val x = (open.box.x * w.toPx()).coerceIn(0f, (w - width).toPx().coerceAtLeast(0f))
                val below = (open.box.y + open.box.height) * h.toPx() + 6.dp.toPx()
                val y = if (below + 90.dp.toPx() < h.toPx()) below else ((open.box.y * h.toPx()) - 96.dp.toPx()).coerceAtLeast(0f)
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .width(width)
            .webViewOverlay(shape)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(c.chrome)
            .border(1.dp, c.line, shape)
            .padding(10.dp)
            .testTag("browser-pick-sent"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("\u201C${open.comment}\u201D", fontSize = 13.sp, color = c.text)
        Text(statusLine(comment), fontSize = 12.sp, color = if (comment.status == "done") c.ok else c.dim)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KitTextAction(localized(Res.string.browser_comment_remove), { pick.remove(open) }, Modifier.testTag("browser-pick-remove"))
            Spacer(Modifier.width(8.dp))
            KitTextAction(localized(Res.string.browser_comment_close), { pick.editing = null })
        }
    }
}

/** While picking: what to do, across the top of the page. On touch: "Tap what you mean" and Done (no keys there). */
@Composable
internal fun PickStrip(touch: Boolean = false, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val c = kitColors()
    Row(
        modifier.fillMaxWidth().height(if (touch) 40.dp else 28.dp).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp).testTag("browser-pick-strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (touch) "Tap what you mean" else "Pick an element · ↑↓ parent / child · Esc to finish", fontSize = if (touch) 14.sp else 12.sp, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
        if (touch) KitTextAction("Done", onDone, Modifier.testTag("browser-pick-done"))
    }
}

/**
 * On touch, the comment in a small sheet from the bottom, in a window of its own so it rises above the keyboard (the
 * iPhone's covered Save, 2026-11-12): its name, the field, a trash and Save (§5.2). A tap outside keeps it, as Save.
 */
@Composable
private fun CommentSheet(open: Pick, pick: PickState) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { pick.closeComment() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { pick.closeComment() }
                .imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CommentPanel(open, pick, Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {})
        }
    }
}

@Composable
private fun CommentPanel(open: Pick, pick: PickState, modifier: Modifier = Modifier) {
    val c = kitColors()
    val focus = remember(open) { FocusRequester() }
    LaunchedEffect(open) { focus.requestFocus() }
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .webViewOverlay(shape)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(c.chrome)
            .padding(14.dp)
            .testTag("browser-pick-panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${open.number}", fontSize = 12.sp, color = c.onAccent, modifier = Modifier.clip(CircleShape).background(c.accent).padding(horizontal = 7.dp, vertical = 1.dp))
            Spacer(Modifier.width(8.dp))
            Text(open.element.label, fontSize = 13.sp, color = c.dim, maxLines = 1, modifier = Modifier.weight(1f))
            BarButton(KitGlyphs.Trash, "Delete this comment", "browser-pick-delete", { pick.removeWithUndo(open) }, size = 36.dp)
        }
        BasicTextField(
            value = open.comment,
            onValueChange = { open.comment = it },
            textStyle = TextStyle(color = c.text, fontSize = 15.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(c.wash).padding(10.dp)
                .focusRequester(focus).testTag("browser-pick-comment"),
            decorationBox = { inner ->
                Box {
                    if (open.comment.isEmpty()) Text("What should change here?", color = c.faint, fontSize = 15.sp)
                    inner()
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(c.accent).clickable { pick.closeComment() }
                    .padding(horizontal = 18.dp).testTag("browser-pick-save"),
                contentAlignment = Alignment.Center,
            ) { Text("Save", fontSize = 14.sp, color = c.onAccent) }
        }
    }
}

/**
 * While comments wait: how many and to whom, Send (posted at once, Q4), Add to message (left in the chat's composer)
 * and Clear. [onSend] is null where no chat can take them: then only the count and Clear.
 */
@Composable
internal fun PickSendBar(
    pick: PickState,
    recipient: String?,
    onSend: ((send: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    touch: Boolean = false,
    /** The workspace's chats (id, name) to choose from (#122 4a-3); one or none: no choice to make. */
    chats: List<Pair<String, String>> = emptyList(),
    onChooseChat: (String) -> Unit = {},
) {
    val c = kitColors()
    val n = pick.commented.size
    val count = if (n == 1) "1 comment" else "$n comments"
    Column(modifier.fillMaxWidth().testTag("browser-pick-sendbar")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(c.chrome).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val choose = onSend != null && n > 0 && chats.size > 1
            Text(
                when {
                    n == 0 -> "Comment deleted"
                    choose -> count
                    onSend != null && recipient != null -> "$count → $recipient"
                    else -> count
                },
                fontSize = 12.5.sp, color = c.text, maxLines = 1, modifier = if (choose) Modifier else Modifier.weight(1f),
            )
            // To whom (4a-3): the chat's name, a menu of the workspace's chats; the choice stays with the tab.
            if (choose) {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("browser-pick-recipient"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(localized(Res.string.browser_comment_to, recipient.orEmpty()), fontSize = 12.5.sp, color = c.accent, maxLines = 1)
                        androidx.compose.material3.Icon(KitGlyphs.ChevronDown, contentDescription = null, tint = c.dim, modifier = Modifier.size(12.dp))
                    }
                    dev.supermux.ui.widgets.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for ((id, name) in chats) {
                            dev.supermux.ui.widgets.DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { open = false; onChooseChat(id) },
                                modifier = Modifier.testTag("browser-pick-recipient-$id"),
                            )
                        }
                    }
                }
            }
            // Undo, for a few seconds after the trash.
            pick.removed?.let { removed ->
                LaunchedEffect(removed) { delay(UNDO_MILLIS); if (pick.removed === removed) pick.removed = null }
                KitTextAction("Undo", { pick.undo() }, Modifier.testTag("browser-pick-undo"))
            }
            // On touch one bar and one way: Send. Clear and Add to message are the desktop's.
            if (!touch && n > 0) KitTextAction("Clear", { pick.clear() }, Modifier.testTag("browser-pick-clear"))
            if (n > 0) onSend?.let { send ->
                if (!touch) KitTextAction("Add to message", { send(false) }, Modifier.testTag("browser-pick-compose"))
                SendButton { send(true) }
            }
        }
    }
}

/**
 * On a phone, the comments' send as a floating dark pill at the bottom of the page, above the home indicator and the
 * gesture bar (Ahmet, 2026-11-12: the bar ran into the home indicator): "Send 1 comment to <chat>"; Undo in it for a
 * few seconds after the trash. Without a chat to take them, it only counts them.
 */
@Composable
internal fun PickSendPill(pick: PickState, recipient: String?, onSend: ((send: Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val n = pick.commented.size
    val count = if (n == 1) "1 comment" else "$n comments"
    val shape = RoundedCornerShape(24.dp)
    val ink = Color.White
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(bottom = 12.dp)
            .heightIn(min = 48.dp)
            .webViewOverlay(shape)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Color(0xFF111214))
            .then(if (n > 0 && onSend != null) Modifier.clickable { onSend(true) } else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("browser-pick-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        pick.removed?.let { removed ->
            LaunchedEffect(removed) { delay(UNDO_MILLIS); if (pick.removed === removed) pick.removed = null }
            Text("Undo", fontSize = 15.sp, color = ink, modifier = Modifier.clickable { pick.undo() }.testTag("browser-pick-undo"))
        }
        if (n > 0) {
            Text(
                if (onSend != null && recipient != null) "Send $count to $recipient" else if (onSend != null) "Send $count" else count,
                fontSize = 15.sp, color = ink, maxLines = 1,
            )
        } else {
            Text("Comment deleted", fontSize = 15.sp, color = ink.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

/** The bar's one filled button: Send, in the accent. */
@Composable
private fun SendButton(onClick: () -> Unit) {
    val c = kitColors()
    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("browser-pick-send"),
        contentAlignment = Alignment.Center,
    ) {
        Text("Send", fontSize = 12.5.sp, color = c.onAccent)
    }
}

/** This modifier places and sizes a box at [box]'s viewport fractions of a [w] × [h] viewport. */
private fun Modifier.boxAt(box: PageBox, w: Dp, h: Dp): Modifier =
    this.offset(w * box.x, h * box.y).size(w * box.width, h * box.height)

private val PIN = 18.dp
private const val UNDO_MILLIS = 4_000L
private const val PIN_REFRESH_MILLIS = 300L
