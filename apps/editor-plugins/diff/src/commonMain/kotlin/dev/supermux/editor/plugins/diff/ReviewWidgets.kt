package dev.supermux.editor.plugins.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.WidgetScope
import dev.supermux.editor.core.WidgetKey

/** The review threads' widget content: register it in the `Editor`'s registry (with [Diff.registerWidgets]). */
fun Review.registerWidgets(registry: WidgetRegistry) {
    registry.register(THREAD) { key -> ThreadBlock(key) }
    registry.register(COMPOSER) { key -> ComposerBlock(key) }
}

/** Test tags of the review widgets. */
object ReviewTags {
    const val THREAD = "review-thread"
    const val COLLAPSED = "review-collapsed"
    const val REPLY_FIELD = "review-reply"
    const val REPLY = "review-reply-send"
    const val RESOLVE = "review-resolve"
    const val COMPOSER_FIELD = "review-composer"
    const val SUBMIT = "review-submit"
    const val CANCEL = "review-cancel"
}

private class Palette(val ink: Color, val dim: Color, val card: Color, val edge: Color, val accent: Color, val field: Color, val prose: TextStyle, val small: TextStyle)

@Composable
private fun WidgetScope.palette(): Palette = remember(theme) {
    val t = theme
    val ink = t.foreground
    val size = t.fontSizeSp
    // Prose, not code: the platform's face (and, on iOS, its Smart Punctuation: nothing turns it off here).
    val prose = TextStyle(color = ink, fontSize = size.sp, fontFamily = FontFamily.Default, lineHeight = (size * 1.4f).sp)
    Palette(
        ink = ink, dim = ink.copy(alpha = 0.6f), card = mix(t.background, ink, 0.06f), edge = ink.copy(alpha = 0.18f),
        accent = t.cursor, field = mix(t.background, ink, 0.02f), prose = prose, small = prose.copy(color = ink.copy(alpha = 0.6f), fontSize = (size * 0.85f).sp),
    )
}

private fun mix(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)

private fun authorLabel(a: String) = when (a) { "agent" -> "Agent"; "user", "" -> "You"; else -> a }

@Composable
private fun Card(p: Palette, tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(p.card).border(1.dp, p.edge, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Button(p: Palette, label: String, tag: String, primary: Boolean = false, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (primary) p.accent.copy(alpha = 0.85f) else p.edge.copy(alpha = 0.35f))
            .press(label, onClick).padding(horizontal = 12.dp, vertical = 5.dp).testTag(tag),
        style = p.small.copy(color = if (primary) Color.White else p.ink),
    )
}

/** A multi-line prose field: Cmd/Ctrl-Enter [onSend], Escape [onEscape]. */
@Composable
private fun ProseField(p: Palette, state: TextFieldState, placeholder: String, tag: String, minLines: Int, modifier: Modifier, onSend: () -> Unit, onEscape: () -> Unit, fieldModifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(p.field).border(1.dp, p.edge, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (state.text.isEmpty()) BasicText(placeholder, style = p.prose.copy(color = p.dim))
        BasicTextField(
            state,
            fieldModifier.fillMaxWidth().testTag(tag).semantics { contentDescription = placeholder }.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    (e.key == Key.Enter || e.key == Key.NumPadEnter) && (e.isMetaPressed || e.isCtrlPressed) -> { onSend(); true }
                    e.key == Key.Escape -> { onEscape(); true }
                    else -> false
                }
            },
            textStyle = p.prose,
            cursorBrush = SolidColor(p.accent),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines),
        )
    }
}

/** A thread under its line: its comments, reply and resolve; a resolved one is one line until tapped. */
@Composable
private fun WidgetScope.ThreadBlock(key: WidgetKey) {
    val st = editor.state
    val t = Review.thread(st, key.id) ?: return
    val expanded = key.id in (Review.state(st)?.expanded ?: emptySet())
    val p = palette()
    if (t.resolved && !expanded) {
        val replies = maxOf(0, t.comments.size - 1)
        Card(p, ReviewTags.THREAD) {
            BasicText(
                "✓ resolved · $replies ${if (replies == 1) "reply" else "replies"}",
                Modifier.fillMaxWidth().press("Show the resolved thread") { Review.toggleExpanded(editor, t.id) }.testTag(ReviewTags.COLLAPSED),
                style = p.small.copy(color = p.accent),
            )
        }
        return
    }
    Card(p, ReviewTags.THREAD) {
        if (t.resolved) BasicText("✓ resolved · hide", Modifier.fillMaxWidth().press("Hide the resolved thread") { Review.toggleExpanded(editor, t.id) }.testTag(ReviewTags.COLLAPSED), style = p.small.copy(color = p.accent))
        for (c in t.comments) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                BasicText(authorLabel(c.author), style = p.small.copy(color = if (c.author == "agent") Color(0xFFD2A8FF) else p.accent, fontWeight = FontWeight.SemiBold))
                if (c.createdAt.isNotEmpty()) BasicText(c.createdAt, style = p.small)
            }
            BasicText(c.body, style = p.prose)
        }
        if (!t.resolved) {
            val reply = rememberTextFieldState()
            fun send() { if (Review.reply(editor, t.id, reply.text.toString())) reply.clearText() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                ProseField(p, reply, "Reply…", ReviewTags.REPLY_FIELD, 1, Modifier.weight(1f), onSend = ::send, onEscape = { focusEditor() })
                Button(p, "Reply", ReviewTags.REPLY, primary = true, onClick = ::send)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(p, "Resolve", ReviewTags.RESOLVE) { Review.resolve(editor, t.id) }
            }
        }
    }
}

/** The composer: a prose field (its draft goes to the host as it is typed), Cancel and Comment. */
@Composable
private fun WidgetScope.ComposerBlock(key: WidgetKey) {
    val st = editor.state
    val c = Review.state(st)?.composer ?: return
    if (key.id != "c${c.gen}") return
    val p = palette()
    // One field per composer (a new gen is a new composer): its draft is the state's, so a widget
    // scrolled far away and back, or a host restoring a draft, starts from it.
    val text = remember(key.id) { TextFieldState(c.draft) }
    val focus = remember(key.id) { FocusRequester() }
    LaunchedEffect(key.id) {
        // Inside a layout-pass subcomposition: the requester is attached a frame later.
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(text) {
        snapshotFlow { text.text.toString() }.collect { Review.typed(editor, it) }
    }
    fun submit() { if (Review.submit(editor, text.text.toString())) focusEditor() }
    fun cancel() { Review.cancel(editor); focusEditor() }
    Card(p, "review-composer-card") {
        ProseField(p, text, "Leave a comment…", ReviewTags.COMPOSER_FIELD, 3, Modifier.fillMaxWidth().heightIn(min = 48.dp), onSend = ::submit, onEscape = ::cancel, fieldModifier = Modifier.focusRequester(focus))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Button(p, "Cancel", ReviewTags.CANCEL, onClick = ::cancel)
            Button(p, "Comment", ReviewTags.SUBMIT, primary = true, onClick = ::submit)
        }
    }
}
