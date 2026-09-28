package dev.supermux.editor.plugins.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndSelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.WidgetScope
import dev.supermux.editor.compose.codeTextInput
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.compose.keyChordOf
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Test tags of the panels' parts, for UI tests and device checks. */
object SearchPanelTags {
    const val FIND = "search.find"
    const val REPLACE = "search.replace"
    const val COUNT = "search.count"
    const val GOTO = "search.gotoLine"
}

/** Put the panels' content in [registry]: `panel:search` and `panel:goto-line`. */
fun Search.registerWidgets(registry: WidgetRegistry) {
    registry.register("panel:$PANEL") { SearchPanel(this) }
    registry.register("panel:$GOTO_PANEL") { GotoLinePanel(this) }
}

/** How long the field waits after a keystroke before it searches (incremental search). */
internal const val SEARCH_DEBOUNCE_MS = 50L

/** Touch-first platforms (phones, tablets) get 48 dp buttons; a mouse gets compact ones. */
internal expect val touchFirst: Boolean

private val targetSize: Dp get() = if (touchFirst) 48.dp else 30.dp

private fun chords(spec: String, mac: String? = null): KeyChord = KeyBinding(spec, Command { false }, mac).chord(isApplePlatform)

private val ESCAPE = chords("Escape")
private val ENTER = chords("Enter")
private val SHIFT_ENTER = chords("Shift-Enter")
private val NEXT = listOf(chords("F3"), chords("Mod-g"))
private val PREVIOUS = listOf(chords("Shift-F3"), chords("Mod-Shift-g"))
private val SELECT_ALL_MATCHES = chords("Alt-Enter", mac = "Mod-Alt-Enter")
private val FIND_AGAIN = chords("Mod-f")

/**
 * The search panel (CM6's, in supermux's look: `:ui`'s search field): the find field, the replace
 * row (toggled by the chevron), match case / whole word / regex toggles showing their state, the
 * count ("3 of 17"), previous / next, select all, replace, replace all, close. Its fields are plain
 * Compose text fields (the soft keyboard types into them; on iOS Smart Punctuation is off in them,
 * search strings being code). Typing searches after [SEARCH_DEBOUNCE_MS] and selects the first match
 * from the cursor, scrolled into view; Enter / Shift-Enter move between matches, Enter in the
 * replace field replaces, Escape closes and gives the focus back to the editor.
 */
@Composable
internal fun SearchPanel(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val s by remember(editor) { derivedStateOf { Search.state(editor.state) } }
    val find = rememberTextFieldState(s.query.search)
    val replace = rememberTextFieldState(s.query.replace)
    val findFocus = remember { FocusRequester() }
    var replaceFocused by remember { mutableStateOf(false) }

    // The query changed from outside (Mod-f with a new selection): the fields show it.
    LaunchedEffect(s.query.search) { if (find.text.toString() != s.query.search) find.setTextAndSelectAll(s.query.search) }
    LaunchedEffect(s.query.replace) { if (replace.text.toString() != s.query.replace) replace.setTextAndSelectAll(s.query.replace) }
    // Each open (or Mod-f again) takes the focus and selects the field's text.
    LaunchedEffect(s.focusRequest) {
        if (s.focusRequest > 0) {
            // The field is laid out (BoxWithConstraints subcomposes it in the layout pass) a frame on.
            withFrameNanos { }
            findFocus.requestFocus()
            find.setTextAndSelectAll(find.text.toString())
        }
    }
    // Incremental search, debounced.
    LaunchedEffect(find) {
        snapshotFlow { find.text.toString() }.collectLatest { text ->
            if (text == Search.query(editor.state).search) return@collectLatest
            delay(SEARCH_DEBOUNCE_MS)
            commitFind(editor, text)
        }
    }
    LaunchedEffect(replace) {
        snapshotFlow { replace.text.toString() }.collectLatest { text ->
            if (text == Search.query(editor.state).replace) return@collectLatest
            delay(SEARCH_DEBOUNCE_MS)
            Search.setQuery(editor, Search.query(editor.state).copy(replace = text))
        }
    }

    // What the fields hold, now (a button or key right after typing must not use the old query).
    fun flush() {
        commitFind(editor, find.text.toString())
        val q = Search.query(editor.state)
        if (replace.text.toString() != q.replace) Search.setQuery(editor, q.copy(replace = replace.text.toString()))
    }
    fun run(c: Command, focusEditor: Boolean = false) { flush(); c.run(editor); if (focusEditor) scope.focusEditor() }
    fun close() { Search.closeSearchPanel.run(editor); scope.focusEditor() }
    fun setFlags(q: SearchQuery) { flush(); Search.setQuery(editor, q.copy(search = find.text.toString(), replace = replace.text.toString())) }

    // Counted after the document, the query or the selection changed (a big file waits a moment).
    var info by remember(editor) { mutableStateOf<MatchInfo?>(null) }
    val st = editor.state
    LaunchedEffect(st.doc, s.query, st.selection) {
        if (st.doc.length > 1_000_000) delay(100)
        info = Search.matchInfo(editor.state)
    }

    val fg = theme.foreground
    val muted = theme.gutterForeground
    val text = TextStyle(color = fg, fontFamily = theme.fontFamily, fontSize = 13.sp)
    val small = TextStyle(color = muted, fontFamily = theme.fontFamily, fontSize = 12.sp)
    val target = targetSize

    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.gutterBackground)
            .drawBehind { drawLine(muted.copy(alpha = 0.35f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val chord = keyChordOf(e) ?: return@onPreviewKeyEvent false
                when {
                    chord == ESCAPE -> { close(); true }
                    chord == SELECT_ALL_MATCHES -> { run(Search.selectMatches, focusEditor = true); true }
                    chord == ENTER && replaceFocused -> { run(Search.replaceNext); true }
                    chord == ENTER || chord in NEXT -> { run(Search.findNext); true }
                    chord == SHIFT_ENTER || chord in PREVIOUS -> { run(Search.findPrevious); true }
                    chord == FIND_AGAIN -> { findFocus.requestFocus(); find.setTextAndSelectAll(find.text.toString()); true }
                    else -> false
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 560.dp
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChevronButton(s.replaceOpen, target, muted) { editor.dispatch(TransactionSpec(effects = listOf(Search.toggleReplace.of(!s.replaceOpen)))) }
                    PanelField(find, "Find", theme, text, Modifier.weight(1f).focusRequester(findFocus).testTag(SearchPanelTags.FIND), error = info?.error != null) { run(Search.findNext) }
                    if (wide) {
                        Toggles(s.query, theme, target, ::setFlags)
                        CountLabel(info, small, Modifier.padding(horizontal = 6.dp))
                    }
                    PanelButton("↑", "Previous match", theme, target) { run(Search.findPrevious) }
                    PanelButton("↓", "Next match", theme, target) { run(Search.findNext) }
                    if (wide) PanelButton("All", "Select all matches", theme, target) { run(Search.selectMatches, focusEditor = true) }
                    PanelButton("×", "Close", theme, target) { close() }
                }
                if (!wide) Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(target))
                    Toggles(s.query, theme, target, ::setFlags)
                    CountLabel(info, small, Modifier.weight(1f).padding(horizontal = 6.dp))
                    PanelButton("All", "Select all matches", theme, target) { run(Search.selectMatches, focusEditor = true) }
                }
                if (s.replaceOpen) Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(target))
                    PanelField(
                        replace, "Replace", theme, text,
                        Modifier.weight(1f).testTag(SearchPanelTags.REPLACE).onFocusChanged { replaceFocused = it.hasFocus },
                    ) { run(Search.replaceNext) }
                    PanelButton("Replace", "Replace", theme, target) { run(Search.replaceNext) }
                    PanelButton("All", "Replace all", theme, target) { run(Search.replaceAll) }
                }
            }
        }
    }
}

/**
 * The find field's text is the query: set it and select the first match at or after the main
 * selection's start (VS Code's incremental search; CM6 only marks), in one transaction.
 */
internal fun commitFind(editor: CommandTarget, text: String) {
    val st = editor.state
    val old = Search.query(st)
    if (text == old.search) return
    val q = old.copy(search = text)
    val main = st.selection.main
    val m = if (q.valid) q.nextMatch(st.doc, main.from, main.from) else null
    val moves = m != null && !(m.from == main.from && m.to == main.to && st.selection.ranges.size == 1)
    editor.dispatch(TransactionSpec(
        effects = listOf(Search.setQueryEffect.of(q)),
        selection = if (moves) EditorSelection.single(m!!.from, m.to) else null,
        scrollIntoView = moves,
        userEvent = if (moves) "select.search" else null,
    ))
}

@Composable
private fun Toggles(q: SearchQuery, theme: EditorTheme, target: Dp, set: (SearchQuery) -> Unit) {
    PanelToggle("Aa", "Match case", q.caseSensitive, theme, target) { set(q.copy(caseSensitive = it)) }
    PanelToggle("W", "Whole word", q.wholeWord, theme, target) { set(q.copy(wholeWord = it)) }
    PanelToggle(".*", "Regular expression", q.regexp, theme, target) { set(q.copy(regexp = it)) }
}

@Composable
private fun CountLabel(info: MatchInfo?, style: TextStyle, modifier: Modifier) {
    val label = info?.label.orEmpty()
    BasicText(
        label,
        modifier.testTag(SearchPanelTags.COUNT).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label },
        style = if (info?.error != null) style.copy(color = Color(0xFFE06C75)) else style,
        maxLines = 1,
    )
}

/** A panel text field: `:ui`'s search field look (a rounded, faint fill), code-style input. */
@Composable
private fun PanelField(
    state: TextFieldState,
    hint: String,
    theme: EditorTheme,
    style: TextStyle,
    modifier: Modifier,
    error: Boolean = false,
    onAction: () -> Unit,
) {
    BasicTextField(
        state = state,
        modifier = modifier
            .padding(vertical = 2.dp)
            .sizeIn(minHeight = if (touchFirst) 40.dp else 26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(theme.foreground.copy(alpha = 0.07f))
            .drawBehind { if (error) drawRect(Color(0xFFE06C75), style = Stroke(1.5f)) }
            .codeTextInput()
            .semantics { contentDescription = hint },
        textStyle = style,
        cursorBrush = SolidColor(theme.cursor),
        lineLimits = TextFieldLineLimits.SingleLine,
        // Code, not prose: no capitalization, no autocorrect; the keyboard's action key searches.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        onKeyboardAction = { onAction() },
        decorator = { inner ->
            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) BasicText(hint, style = style.copy(color = theme.gutterForeground))
                inner()
            }
        },
    )
}

/** A button that never takes the focus (the field keeps it, so a soft keyboard stays up). */
@Composable
private fun PanelButton(label: String, description: String, theme: EditorTheme, target: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minWidth = target, minHeight = target)
            .focusProperties { canFocus = false }
            .clip(RoundedCornerShape(6.dp))
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = 13.sp)) }
}

@Composable
private fun PanelToggle(label: String, description: String, on: Boolean, theme: EditorTheme, target: Dp, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .sizeIn(minWidth = target, minHeight = target)
            .focusProperties { canFocus = false }
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) theme.cursor.copy(alpha = 0.28f) else Color.Transparent)
            .toggleable(on, remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = TextStyle(color = if (on) theme.foreground else theme.gutterForeground, fontFamily = theme.fontFamily, fontSize = 13.sp)) }
}

/** The replace row's chevron: pointing right (closed) or down (open). */
@Composable
private fun ChevronButton(open: Boolean, target: Dp, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(target)
            .focusProperties { canFocus = false }
            .clip(RoundedCornerShape(6.dp))
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (open) "Hide replace" else "Show replace" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val w = size.width
            val p = Path().apply {
                if (open) { moveTo(0f, w * 0.3f); lineTo(w / 2, w * 0.75f); lineTo(w, w * 0.3f) }
                else { moveTo(w * 0.3f, 0f); lineTo(w * 0.75f, w / 2); lineTo(w * 0.3f, w) }
            }
            drawPath(p, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** CM6's go-to-line dialog as a panel: a line (`12`, `+3`, `50%`, `12:5`), Enter goes, Escape closes. */
@Composable
internal fun GotoLinePanel(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val s by remember(editor) { derivedStateOf { Search.state(editor.state) } }
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(s.gotoLineFocusRequest) {
        val st = editor.state
        field.setTextAndSelectAll(st.doc.lineAt(st.selection.main.head).number.toString())
        withFrameNanos { }
        focus.requestFocus()
    }
    val text = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = 13.sp)
    fun go() { Search.goToLine(editor, field.text.toString()); scope.focusEditor() }
    fun close() { Search.closeSearchPanel.run(editor); scope.focusEditor() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.gutterBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyChordOf(e)) { ESCAPE -> { close(); true }; ENTER -> { go(); true }; else -> false }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Go to line", style = text.copy(color = theme.gutterForeground), modifier = Modifier.padding(end = 8.dp))
        PanelField(field, "line[:column]", theme, text, Modifier.weight(1f).focusRequester(focus).testTag(SearchPanelTags.GOTO)) { go() }
        PanelButton("Go", "Go to line", theme, targetSize) { go() }
        PanelButton("×", "Close", theme, targetSize) { close() }
        Spacer(Modifier.height(targetSize))
    }
}
