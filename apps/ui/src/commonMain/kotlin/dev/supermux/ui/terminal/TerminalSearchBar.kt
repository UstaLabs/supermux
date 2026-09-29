package dev.supermux.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.terminal.compose.TerminalSearchState
import dev.supermux.ui.theme.MonoFontFamily

/**
 * The find field over a terminal pane: query, "3/17", up/down, case, close.
 *
 * It is drawn by the HOST, next to the grid rather than inside it: the renderer's key handler sits
 * on the grid's box and previews every key that reaches a descendant, so a field inside that box
 * would have its typing sent to the shell. A sibling is outside that path.
 *
 * Keys: Enter / ↑ = older match, Shift+Enter / ↓ = newer, Esc = close (and the terminal gets the
 * keyboard back). Every Cmd+F while it is open re-focuses the field and selects the query.
 */
@Composable
internal fun TerminalSearchBar(search: TerminalSearchState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(search.query)) }
    LaunchedEffect(search.focusRequests) {
        field = field.copy(selection = TextRange(0, field.text.length))
        runCatching { focus.requestFocus() }
    }
    Surface(
        modifier = modifier.testTag(TERMINAL_SEARCH_TAG),
        shape = RoundedCornerShape(8.dp),
        color = cs.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.width(170.dp), contentAlignment = Alignment.CenterStart) {
                if (field.text.isEmpty()) {
                    Text("Find", color = cs.onSurfaceVariant, fontSize = 13.sp, fontFamily = MonoFontFamily)
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        search.updateQuery(it.text)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp, fontFamily = MonoFontFamily),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // A soft keyboard's Search key: the same as Enter.
                    keyboardActions = KeyboardActions(onSearch = { search.previous() }),
                    modifier = Modifier
                        .testTag(TERMINAL_SEARCH_FIELD_TAG)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    if (event.isShiftPressed) search.next() else search.previous()
                                    true
                                }
                                Key.DirectionUp -> {
                                    search.previous()
                                    true
                                }
                                Key.DirectionDown -> {
                                    search.next()
                                    true
                                }
                                Key.Escape -> {
                                    search.close()
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
            Text(
                text = searchCountLabel(search),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = MonoFontFamily,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 48.dp).padding(horizontal = 4.dp).testTag(TERMINAL_SEARCH_COUNT_TAG),
            )
            CaseToggle(on = !search.ignoreCase) { search.ignoreCase = !search.ignoreCase }
            BarIcon(Icons.Filled.KeyboardArrowUp, "Previous match", "terminal_search_prev") { search.previous() }
            BarIcon(Icons.Filled.KeyboardArrowDown, "Next match", "terminal_search_next") { search.next() }
            BarIcon(Icons.Filled.Close, "Close find", "terminal_search_close") { search.close() }
        }
    }
}

/** "3/17", "No results", "…" while a search runs, "3/10000+" when it hit its cap. */
internal fun searchCountLabel(search: TerminalSearchState): String = when {
    search.query.isEmpty() -> ""
    search.searching && search.matches.isEmpty() -> "…"
    search.matches.isEmpty() -> "No results"
    else -> {
        val position = if (search.current >= 0) "${search.current + 1}" else "–"
        "$position/${search.matches.size}${if (search.truncated) "+" else ""}"
    }
}

@Composable
private fun CaseToggle(on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .testTag("terminal_search_case"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Aa",
            color = if (on) cs.primary else cs.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

internal const val TERMINAL_SEARCH_TAG = "terminal_search"
internal const val TERMINAL_SEARCH_FIELD_TAG = "terminal_search_field"
internal const val TERMINAL_SEARCH_COUNT_TAG = "terminal_search_count"
