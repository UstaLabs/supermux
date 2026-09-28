package dev.supermux.editor.plugins.autocomplete

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.WidgetScope
import dev.supermux.editor.compose.isTouchFirstPlatform

/** Test tags of the completion popup. */
object CompletionTags {
    const val LIST = "completion-list"
    const val INFO = "completion-info"
    fun option(i: Int) = "completion-option-$i"
}

/** Register the popup's content (`tooltip:completion`). */
fun Autocomplete.registerWidgets(registry: WidgetRegistry) {
    registry.register(TOOLTIP) { CompletionPopup(this) }
}

/** An icon letter and its theme token for a completion type (CM6's icons, as letters). */
private fun iconOf(type: String?): Pair<String, String>? = when (type) {
    null -> null
    "class" -> "C" to "tok-type"
    "interface" -> "I" to "tok-type"
    "enum" -> "E" to "tok-type"
    "type" -> "T" to "tok-type"
    "struct" -> "S" to "tok-type"
    "function" -> "ƒ" to "tok-function"
    "method" -> "m" to "tok-method"
    "constant" -> "K" to "tok-constant"
    "variable" -> "v" to "tok-variable"
    "property", "field" -> "p" to "tok-property"
    "keyword" -> "k" to "tok-keyword"
    "namespace", "module" -> "N" to "tok-namespace"
    "snippet" -> "⌘" to "tok-string-special"
    "text" -> "t" to "tok-comment"
    else -> "·" to "tok-punctuation"
}

/**
 * The list: every option, the selected one highlighted and kept in view, the typed characters bold
 * in each label, the detail after it, the selected option's documentation beside the list (below
 * it on a narrow editor). A tap on a row accepts it WITHOUT taking the focus (the soft keyboard
 * stays up: `detectTapGestures`, never `clickable`); the list scrolls with a finger or a wheel.
 */
@Composable
private fun CompletionPopup(scope: WidgetScope) {
    val theme = scope.theme
    val editor = scope.editor
    val s by remember(editor) { derivedStateOf { Autocomplete.state(editor.state) } }
    if (!s.open) return
    val rowHeight = if (isTouchFirstPlatform) 40.dp else maxOf(scope.lineHeight, 22.dp)
    val list = rememberLazyListState()
    LaunchedEffect(s.selected, s.options.size) {
        val i = s.selected
        if (i < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (i < first || i > last - 1 || info.visibleItemsInfo.isEmpty()) list.scrollToItem(maxOf(0, if (i < first) i else i - maxOf(0, last - first - 1)))
    }
    val shape = RoundedCornerShape(6.dp)
    val label = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = theme.fontSizeSp.sp)
    val dim = theme.gutterForeground
    val info = s.selectedOption?.completion?.let { c -> c.info ?: s.info?.takeIf { it.first == c }?.second }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.widthIn(min = 180.dp, max = 440.dp).heightIn(max = rowHeight * 9)
                .shadow(6.dp, shape).clip(shape).background(theme.background).border(1.dp, dim.copy(alpha = 0.35f), shape),
        ) {
            LazyColumn(Modifier.testTag(CompletionTags.LIST), state = list) {
                itemsIndexed(s.options, key = { i, _ -> i }) { i, o ->
                    val selected = i == s.selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = rowHeight)
                            .background(if (selected) theme.selection else Color.Transparent)
                            .pointerInput(i) { detectTapGestures { Autocomplete.accept(editor, i) } }
                            .semantics {
                                role = Role.Button
                                this.selected = selected
                                contentDescription = o.completion.displayLabel ?: o.completion.label
                                onClick { Autocomplete.accept(editor, i) }
                            }
                            .testTag(CompletionTags.option(i))
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = iconOf(o.completion.type)
                        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                            if (icon != null) BasicText(icon.first, style = label.merge(theme.styleOf(icon.second) ?: SpanStyle(color = dim)).copy(fontSize = (theme.fontSizeSp - 1).sp))
                        }
                        Spacer(Modifier.width(4.dp))
                        BasicText(highlighted(o, theme), style = label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        o.completion.detail?.let { d ->
                            Spacer(Modifier.width(10.dp))
                            BasicText(d, style = label.copy(color = dim, fontSize = (theme.fontSizeSp - 1).sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (!info.isNullOrBlank()) {
            Spacer(Modifier.size(4.dp))
            Column(
                Modifier.widthIn(max = 360.dp).heightIn(max = rowHeight * 9)
                    .shadow(6.dp, shape).clip(shape).background(theme.background).border(1.dp, dim.copy(alpha = 0.35f), shape)
                    .padding(8.dp).testTag(CompletionTags.INFO),
            ) {
                BasicText(info, style = label.copy(fontSize = (theme.fontSizeSp - 1).sp))
            }
        }
    }
}

/** The label with its matched characters bold (CM6's `cm-completionMatchedText`). */
private fun highlighted(o: Option, theme: EditorTheme): AnnotatedString {
    val text = o.completion.displayLabel ?: o.completion.label
    val m = o.matched
    if (m.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var i = 0
        while (i + 1 < m.size) {
            val a = m[i].coerceIn(0, text.length); val b = m[i + 1].coerceIn(a, text.length)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = theme.cursor), a, b)
            i += 2
        }
    }
}
