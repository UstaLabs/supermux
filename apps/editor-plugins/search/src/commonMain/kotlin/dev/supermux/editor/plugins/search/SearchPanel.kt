package dev.supermux.editor.plugins.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.platform.LocalFocusManager
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
    const val GOTO_ERROR = "search.gotoLine.error"
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
private val TAB = chords("Tab")
private val SHIFT_TAB = chords("Shift-Tab")
private val NEXT = listOf(chords("F3"), chords("Mod-g"))
private val PREVIOUS = listOf(chords("Shift-F3"), chords("Mod-Shift-g"))
private val SELECT_ALL_MATCHES = chords("Alt-Enter", mac = "Mod-Alt-Enter")
private val FIND_AGAIN = chords("Mod-f")
// VS Code's find widget toggles (CM6 has none): Alt-C / Alt-W / Alt-R, Apple Cmd-Alt-C / W / R.
private val TOGGLE_CASE = chords("Alt-c", mac = "Mod-Alt-c")
private val TOGGLE_WORD = chords("Alt-w", mac = "Mod-Alt-w")
private val TOGGLE_REGEX = chords("Alt-r", mac = "Mod-Alt-r")

/** Test support: how many times the panel's body composed. */
internal object SearchPanelDebug {
    var compositions = 0
}

/**
 * The search panel (CM6's, in supermux's look: `:ui`'s search field): the find field, the replace
 * row (toggled by the chevron), match case / whole word / regex toggles showing their state, the
 * count ("3 of 17"), previous / next, select all, replace, replace all, close. Its fields are plain
 * Compose text fields (the soft keyboard types into them; on iOS Smart Punctuation is off in them,
 * search strings being code). Typing searches after [SEARCH_DEBOUNCE_MS] and selects the first match
 * from the cursor, scrolled into view; Enter / Shift-Enter move between matches, Enter in the
 * replace field replaces, Escape closes and gives the focus back to the editor. Tab moves through
 * the fields, toggles and buttons. The work runs in a [SearchRunner] (sliced on a big document).
 *
 * It reads only the plugin's state (derived: an edit or a caret move recomposes nothing here), and
 * the runner's count in its own small scope.
 */
@Composable
internal fun SearchPanel(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val s by remember(editor) { derivedStateOf { Search.state(editor.state) } }
    val coroutines = rememberCoroutineScope()
    val runner = remember(editor) { SearchRunner(editor, coroutines) }
    DisposableEffect(runner) { runner.attach(); onDispose { runner.close() } }
    SideEffect { SearchPanelDebug.compositions++ }
    val find = rememberTextFieldState(s.query.search)
    val replace = rememberTextFieldState(s.query.replace)
    val findFocus = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(0) } // 1 find, 2 replace, 0 neither
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Closed (or gone) while it held the focus: the focus goes back to the editor.
    val latestHasFocus by rememberUpdatedState(hasFocus)
    DisposableEffect(Unit) { onDispose { if (latestHasFocus) scope.focusEditor() } }

    // The query changed from OUTSIDE (Mod-f with a new selection), not by what this panel committed:
    // only then do the fields show it (comparing with the field's text would drop a keystroke typed
    // just before the debounce fired).
    LaunchedEffect(s.query.search) {
        if (s.query.search != runner.committedSearch) {
            runner.committedSearch = s.query.search
            find.setTextAndSelectAll(s.query.search)
        }
    }
    LaunchedEffect(s.query.replace) {
        if (s.query.replace != runner.committedReplace) {
            runner.committedReplace = s.query.replace
            replace.setTextAndSelectAll(s.query.replace)
        }
    }
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
    LaunchedEffect(find, runner) {
        snapshotFlow { find.text.toString() }.collectLatest { text ->
            if (text == runner.committedSearch) return@collectLatest
            delay(SEARCH_DEBOUNCE_MS)
            runner.commitFind(text)
        }
    }
    LaunchedEffect(replace, runner) {
        snapshotFlow { replace.text.toString() }.collectLatest { text ->
            if (text == runner.committedReplace) return@collectLatest
            delay(SEARCH_DEBOUNCE_MS)
            runner.setReplace(text)
        }
    }

    // What the fields hold, now (a button or key right after typing must not use the old query).
    fun flush() {
        runner.setSearch(find.text.toString())
        runner.setReplace(replace.text.toString())
    }
    fun run(c: Command, focusEditor: Boolean = false) { flush(); c.run(editor); if (focusEditor) scope.focusEditor() }
    fun go(dir: Int) { flush(); runner.find(dir) }
    fun close() { Search.closeSearchPanel.run(editor); scope.focusEditor() }
    fun setFlags(q: SearchQuery) { flush(); Search.setQuery(editor, q.copy(search = find.text.toString(), replace = replace.text.toString())) }

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
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val chord = keyChordOf(e) ?: return@onPreviewKeyEvent false
                val q = Search.query(editor.state)
                when {
                    chord == ESCAPE -> { close(); true }
                    chord == TAB -> focusManager.moveFocus(FocusDirection.Next)
                    chord == SHIFT_TAB -> focusManager.moveFocus(FocusDirection.Previous)
                    chord == TOGGLE_CASE -> { setFlags(q.copy(caseSensitive = !q.caseSensitive)); true }
                    chord == TOGGLE_WORD -> { setFlags(q.copy(wholeWord = !q.wholeWord)); true }
                    chord == TOGGLE_REGEX -> { setFlags(q.copy(regexp = !q.regexp)); true }
                    chord == SELECT_ALL_MATCHES -> { run(Search.selectMatches, focusEditor = true); true }
                    chord in NEXT -> { go(1); true }
                    chord in PREVIOUS -> { go(-1); true }
                    chord == FIND_AGAIN -> { findFocus.requestFocus(); find.setTextAndSelectAll(find.text.toString()); true }
                    // Enter in a field; on a focused button or toggle, Enter is the button's.
                    chord == ENTER && fieldFocused == 2 -> { run(Search.replaceNext); true }
                    chord == ENTER && fieldFocused == 1 -> { go(1); true }
                    chord == SHIFT_ENTER && fieldFocused != 0 -> { go(-1); true }
                    else -> false
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 560.dp
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChevronButton(s.replaceOpen, target, muted) { editor.dispatch(TransactionSpec(effects = listOf(Search.toggleReplace.of(!s.replaceOpen)))) }
                    PanelField(
                        find, "Find", theme, text,
                        Modifier.weight(1f).focusRequester(findFocus).testTag(SearchPanelTags.FIND).onFocusChanged { if (it.hasFocus) fieldFocused = 1 else if (fieldFocused == 1) fieldFocused = 0 },
                        error = s.query.error != null,
                    ) { go(1) }
                    if (wide) {
                        Toggles(s.query, theme, target, ::setFlags)
                        CountLabel(runner, s.query, small, Modifier.padding(horizontal = 6.dp))
                    }
                    PanelButton("↑", "Previous match", theme, target) { go(-1) }
                    PanelButton("↓", "Next match", theme, target) { go(1) }
                    if (wide) PanelButton("All", "Select all matches", theme, target) { run(Search.selectMatches, focusEditor = true) }
                    PanelButton("×", "Close", theme, target) { close() }
                }
                if (!wide) Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(target))
                    Toggles(s.query, theme, target, ::setFlags)
                    CountLabel(runner, s.query, small, Modifier.weight(1f).padding(horizontal = 6.dp))
                    PanelButton("All", "Select all matches", theme, target) { run(Search.selectMatches, focusEditor = true) }
                }
                if (s.replaceOpen) Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(target))
                    PanelField(
                        replace, "Replace", theme, text,
                        Modifier.weight(1f).testTag(SearchPanelTags.REPLACE).onFocusChanged { if (it.hasFocus) fieldFocused = 2 else if (fieldFocused == 2) fieldFocused = 0 },
                    ) { run(Search.replaceNext) }
                    PanelButton("Replace", "Replace", theme, target) { run(Search.replaceNext) }
                    PanelButton("All", "Replace all", theme, target) { run(Search.replaceAll) }
                }
            }
        }
    }
}

@Composable
private fun Toggles(q: SearchQuery, theme: EditorTheme, target: Dp, set: (SearchQuery) -> Unit) {
    PanelToggle("Aa", "Match case", q.caseSensitive, theme, target) { set(q.copy(caseSensitive = it)) }
    PanelToggle("W", "Whole word", q.wholeWord, theme, target) { set(q.copy(wholeWord = it)) }
    PanelToggle(".*", "Regular expression", q.regexp, theme, target) { set(q.copy(regexp = it)) }
}

/** The count, in its own scope: the runner's changes recompose only this. */
@Composable
private fun CountLabel(runner: SearchRunner, query: SearchQuery, style: TextStyle, modifier: Modifier) {
    val info = runner.info
    val label = when {
        query.error != null -> "Invalid regex: ${query.error}"
        runner.searching -> "searching…"
        info == null -> "…"
        else -> info.label
    }
    BasicText(
        label,
        modifier.testTag(SearchPanelTags.COUNT).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label },
        style = if (query.error != null) style.copy(color = Color(0xFFE06C75)) else style,
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

/**
 * A button: keyboard-focusable (Tab reaches it; Enter or Space presses it), but a click or a tap
 * never moves the focus (the field keeps it, so a soft keyboard stays up).
 */
@Composable
private fun PanelButton(label: String, description: String, theme: EditorTheme, target: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minWidth = target, minHeight = target)
            .clip(RoundedCornerShape(6.dp))
            .panelPress(Role.Button, onClick)
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
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) theme.cursor.copy(alpha = 0.28f) else Color.Transparent)
            .panelPress(Role.Checkbox, { onChange(!on) }, toggled = on)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = TextStyle(color = if (on) theme.foreground else theme.gutterForeground, fontFamily = theme.fontFamily, fontSize = 13.sp)) }
}

/**
 * A panel button's input: focusable, so Tab reaches it, and Enter or Space presses it; a tap or a
 * click presses it WITHOUT taking the focus (Compose's `clickable` focuses on a desktop click, which
 * took the focus out of the find field). A ring shows the keyboard focus. Semantics: a button (or a
 * checkbox showing [toggled]) with a click action.
 */
@Composable
private fun Modifier.panelPress(role: Role, onClick: () -> Unit, toggled: Boolean? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onClick)
    return this
        .drawBehind { if (focused) drawRoundRect(Color(0x994BBAA7), style = Stroke(2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())) }
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)) { latest(); true } else false
        }
        .pointerInput(Unit) { detectTapGestures(onTap = { latest() }) }
        .semantics {
            this.role = role
            if (toggled != null) toggleableState = androidx.compose.ui.state.ToggleableState(toggled)
            onClick { latest(); true }
        }
}

/** The replace row's chevron: pointing right (closed) or down (open). */
@Composable
private fun ChevronButton(open: Boolean, target: Dp, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(target)
            .clip(RoundedCornerShape(6.dp))
            .panelPress(Role.Button, onClick)
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

/**
 * CM6's go-to-line dialog as a panel: a line (`12`, `+3`, `50%`, `12:5`), Enter goes and closes,
 * Escape closes; input that is no line keeps it open with an error.
 */
@Composable
internal fun GotoLinePanel(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val s by remember(editor) { derivedStateOf { Search.state(editor.state) } }
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val latestHasFocus by rememberUpdatedState(hasFocus)
    DisposableEffect(Unit) { onDispose { if (latestHasFocus) scope.focusEditor() } }
    LaunchedEffect(s.gotoLineFocusRequest) {
        val st = editor.state
        field.setTextAndSelectAll(st.doc.lineAt(st.selection.main.head).number.toString())
        withFrameNanos { }
        focus.requestFocus()
    }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collectLatest { error = false } }
    val text = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = 13.sp)
    fun go() { if (Search.goToLine(editor, field.text.toString())) scope.focusEditor() else error = true }
    fun close() { Search.closeSearchPanel.run(editor); scope.focusEditor() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.gutterBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyChordOf(e)) { ESCAPE -> { close(); true }; ENTER -> { go(); true }; else -> false }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Go to line", style = text.copy(color = theme.gutterForeground), modifier = Modifier.padding(end = 8.dp))
        PanelField(field, "line[:column]", theme, text, Modifier.weight(1f).focusRequester(focus).testTag(SearchPanelTags.GOTO), error = error) { go() }
        if (error) BasicText(
            "Not a line: use 12, +3, -2, 50% or 12:5",
            Modifier.padding(horizontal = 6.dp).testTag(SearchPanelTags.GOTO_ERROR).semantics { liveRegion = LiveRegionMode.Polite },
            style = text.copy(color = Color(0xFFE06C75), fontSize = 12.sp),
            maxLines = 1,
        )
        PanelButton("Go", "Go to line", theme, targetSize) { go() }
        PanelButton("×", "Close", theme, targetSize) { close() }
        Spacer(Modifier.height(targetSize))
    }
}
