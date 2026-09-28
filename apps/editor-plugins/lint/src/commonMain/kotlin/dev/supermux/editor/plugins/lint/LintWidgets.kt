package dev.supermux.editor.plugins.lint

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.Hover
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.WidgetScope
import dev.supermux.editor.compose.isTouchFirstPlatform
import dev.supermux.editor.core.TransactionSpec

/** Test tags of the lint tooltip and panel. */
object LintTags {
    const val TOOLTIP = "lint-tooltip"
    const val PANEL = "lint-panel"
    fun row(i: Int) = "lint-row-$i"
    fun action(name: String) = "lint-action-$name"
}

/** Register the tooltip (`tooltip:lint`) and the panel (`panel:lint`). */
fun Lint.registerWidgets(registry: WidgetRegistry) {
    registry.register(TOOLTIP) { LintTooltip(this) }
    registry.register("panel:$PANEL") { LintPanel(this) }
}

internal fun severityColor(theme: EditorTheme, s: Severity): Color =
    theme.squiggles[s.cls]?.color ?: when (s) { Severity.ERROR -> Color(0xFFE06C75); Severity.WARNING -> Color(0xFFE5C07B); else -> theme.gutterForeground }

/** A press that never takes the focus (a tooltip's or panel's button: the editor keeps it, a soft keyboard stays up). */
private fun Modifier.press(label: String, onPress: () -> Unit): Modifier =
    pointerInput(label) { detectTapGestures { onPress() } }.semantics { role = Role.Button; contentDescription = label; onClick { onPress(); true } }

@Composable
private fun LintTooltip(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val shown by remember(editor) { derivedStateOf { Hover.shown(editor.state, Lint.HOVER_ID) } }
    val range = shown ?: return
    val ds by remember(editor, range) { derivedStateOf { Lint.at(editor.state, range.from, range.to) } }
    if (ds.isEmpty()) return
    val shape = RoundedCornerShape(6.dp)
    val style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = (theme.fontSizeSp - 0.5f).sp)
    val target = if (isTouchFirstPlatform) 40.dp else 24.dp
    Column(
        Modifier.widthIn(max = 460.dp).shadow(6.dp, shape).clip(shape).background(theme.background)
            .border(1.dp, theme.gutterForeground.copy(alpha = 0.35f), shape).padding(8.dp).testTag(LintTags.TOOLTIP),
    ) {
        ds.forEachIndexed { i, d ->
            if (i > 0) Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(severityColor(theme, d.severity)))
                Spacer(Modifier.width(6.dp))
                Column {
                    BasicText(d.message, style = style)
                    d.source?.let { BasicText(it, style = style.copy(color = theme.gutterForeground, fontSize = (theme.fontSizeSp - 1.5f).sp)) }
                    if (d.actions.isNotEmpty()) Row(Modifier.padding(top = 4.dp)) {
                        d.actions.forEach { a ->
                            Box(
                                Modifier.padding(end = 6.dp).heightIn(min = target).clip(RoundedCornerShape(4.dp))
                                    .background(theme.selection).press(a.name) {
                                        Lint.runAction(editor, d, a)
                                        editor.dispatch(TransactionSpec(effects = listOf(Hover.set.of(Lint.HOVER_ID to null))))
                                    }
                                    .testTag(LintTags.action(a.name)).padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) { BasicText(a.name, style = style) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The diagnostics panel (CM6's lint panel): every diagnostic in document order, its severity, line
 * and column, message and source. Opening it gives it the focus; `ArrowUp` / `ArrowDown` move,
 * `Enter` (or a tap, a click) goes to the diagnostic (selected and scrolled into view: a fold
 * holding it opens) and hands the focus back to the editor, `Escape` closes it.
 */
@Composable
private fun LintPanel(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val s by remember(editor) { derivedStateOf { Lint.state(editor.state) } }
    val ds = s.diagnostics
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(s.panelFocus) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(s.selected) { if (s.selected >= 0) list.scrollToItem(s.selected) }
    val style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = (theme.fontSizeSp - 0.5f).sp)
    val dim = style.copy(color = theme.gutterForeground)
    val row = if (isTouchFirstPlatform) 44.dp else 24.dp
    fun go(i: Int) {
        val d = ds.getOrNull(i) ?: return
        editor.dispatch(TransactionSpec(effects = listOf(Lint.selectInPanel.of(i))))
        Lint.goTo(editor, d)
        scope.focusEditor()
    }
    Column(
        Modifier.fillMaxWidth().background(theme.gutterBackground).testTag(LintTags.PANEL)
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { editor.dispatch(TransactionSpec(effects = listOf(Lint.selectInPanel.of((s.selected + 1).coerceAtMost(ds.size - 1))))); true }
                    Key.DirectionUp -> { editor.dispatch(TransactionSpec(effects = listOf(Lint.selectInPanel.of((s.selected - 1).coerceAtLeast(0))))); true }
                    Key.Enter, Key.NumPadEnter -> { go(maxOf(0, s.selected)); true }
                    Key.Escape -> { Lint.closeLintPanel.run(editor); scope.focusEditor(); true }
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (ds.isEmpty()) "No problems" else "Problems (${ds.size})", style = dim, modifier = Modifier.weight(1f))
            Box(Modifier.size(row).press("Close diagnostics") { Lint.closeLintPanel.run(editor); scope.focusEditor() }, contentAlignment = Alignment.Center) { BasicText("×", style = style) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = row * 7), state = list) {
            itemsIndexed(ds) { i, d ->
                val line = editor.state.doc.lineAt(d.from.coerceIn(0, editor.state.doc.length))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = row)
                        .background(if (i == s.selected) theme.selection else Color.Transparent)
                        .press("${d.severity.name.lowercase()} line ${line.number}: ${d.message}") { go(i) }
                        .semantics { selected = i == s.selected }
                        .testTag(LintTags.row(i)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(severityColor(theme, d.severity)))
                    Spacer(Modifier.width(8.dp))
                    BasicText("${line.number}:${d.from - line.from + 1}", style = dim)
                    Spacer(Modifier.width(8.dp))
                    BasicText(d.message, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    d.source?.let { Spacer(Modifier.width(8.dp)); BasicText(it, style = dim, maxLines = 1) }
                }
            }
        }
    }
}
