package dev.supermux.editor.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Facet

/** Columns per tab stop, for drawing tabs and for [insertTab] (default 4). */
val tabSizeFacet: Facet<Int, Int> = Facet.first("tabSize", 4)

/** What one level of indentation inserts: spaces (the default, 4) or "\t". */
val indentUnitFacet: Facet<String, String> = Facet.first("indentUnit", "    ")

/**
 * Show the line-number gutter. When a plugin (basics' `lineNumbers(enabled)`, the view settings)
 * provides it, it overrides `Editor(showLineNumbers = …)`, and a reconfigure changes it at run time.
 */
val lineNumbersFacet: Facet<Boolean, Boolean?> = Facet.define("lineNumbers") { it.firstOrNull() }

/**
 * Wrap long lines. When a plugin (the view settings) provides it, it overrides
 * `Editor(lineWrap = …)`, and a reconfigure changes it at run time.
 */
val lineWrappingFacet: Facet<Boolean, Boolean?> = Facet.define("lineWrapping") { it.firstOrNull() }

/**
 * The font size (sp) a host's settings chose: shown unless the user zoomed since it was set (a
 * reconfigure to a new size replaces the zoom). `Mod 0` still goes back to the theme's size
 * ([EditorZoom.DEFAULT]), as today's editor does.
 */
val fontSizeFacet: Facet<Float, Float?> = Facet.define("fontSize") { it.firstOrNull() }

/** Which of the packaged palettes to use ([EditorTheme.light] / [EditorTheme.dark]). */
enum class EditorThemeMode { LIGHT, DARK, SYSTEM }

/**
 * The theme a host's settings chose (SYSTEM: after the platform's dark mode). When set, the surface
 * paints with `Editor(lightTheme / darkTheme)` when the host gave them, else with `Editor(theme)`'s
 * palette swapped for [EditorTheme.light] / [EditorTheme.dark] ([EditorTheme.withPalette]: the
 * host's own classes, fonts and gutter columns are kept). Unset: `Editor(theme)` as it is.
 */
val themeModeFacet: Facet<EditorThemeMode, EditorThemeMode?> = Facet.define("themeMode") { it.firstOrNull() }

/**
 * A plugin's effects for the surface's viewport transaction (the syntax plugin's `setViewport`):
 * given the UTF-16 range the surface lays out, the effects to dispatch. They go out TOGETHER with
 * [EditorViewport.set], in one transaction, only when the laid-out lines leave the current window.
 */
val viewportEffectsFacet: Facet<(IntRange) -> List<dev.supermux.editor.core.StateEffect<*>>, List<(IntRange) -> List<dev.supermux.editor.core.StateEffect<*>>>> = Facet.list("viewportEffects")

/**
 * The surface's viewport as STATE, for plugins that decorate only what is on screen (selection
 * matches, fold arrows): CM6's `view.visibleRanges`. A plugin includes [extension]; the surface then
 * dispatches [set] with a WINDOW: the lines it lays out plus as many again above and below (about a
 * screen of margin each side), so an ordinary scroll finds its decorations already there (no frame
 * of lag); only when the laid-out lines leave the window does it dispatch a new one, after the frame,
 * in ONE transaction with every [viewportEffectsFacet] effect (no userEvent: history ignores it). A
 * fling past a whole screen in one frame shows the new lines' decorations a frame late. Between two
 * updates the range is mapped through edits. Empty until the first paint: a plugin then falls back
 * to a window around the selection.
 */
object EditorViewport {
    val set: dev.supermux.editor.core.StateEffectType<IntRange> = dev.supermux.editor.core.StateEffectType("editor.viewport")

    val field: dev.supermux.editor.core.StateField<IntRange> = dev.supermux.editor.core.StateField(
        "editor.viewport",
        { IntRange.EMPTY },
        { v, tr ->
            var r = v
            if (tr.docChanged && !r.isEmpty()) {
                val c = tr.changes
                val a = c.mapPos(minOf(r.first, c.lengthBefore), -1)
                r = a until maxOf(a, c.mapPos(minOf(r.last + 1, c.lengthBefore), 1))
            }
            for (e in tr.effects) e.valueIf(set)?.let { r = it }
            r
        },
    )

    /** What a plugin includes (a field; several plugins including it share one). */
    val extension: dev.supermux.editor.core.Extension get() = EditorViewport.field

    /** The window for [laid] (the laid-out range): as many lines again above and below it. */
    internal fun around(doc: dev.supermux.editor.core.Rope, laid: IntRange): IntRange {
        val l1 = doc.lineIndexAt(minOf(laid.first, doc.length))
        val l2 = doc.lineIndexAt(minOf(maxOf(laid.first, laid.last), doc.length))
        val n = l2 - l1 + 1
        val a = doc.lineStart(maxOf(0, l1 - n))
        val last = minOf(doc.lineCount - 1, l2 + n)
        val b = if (last + 1 < doc.lineCount) doc.lineStart(last + 1) - 1 else doc.length
        return a until b
    }

    /** The viewport in [state], or null when no plugin asked for it. Empty before the first paint. */
    fun of(state: dev.supermux.editor.core.EditorState): IntRange? = state.fieldOrNull(field)

    /**
     * The range to decorate in [state]: the viewport, else (before the first paint, or with no
     * surface) [fallbackLines] lines around the main cursor.
     */
    fun rangeOf(state: dev.supermux.editor.core.EditorState, fallbackLines: Int = 100): IntRange {
        val v = of(state)
        if (v != null && !v.isEmpty()) return v.first..minOf(v.last, state.doc.length)
        val doc = state.doc
        val line = doc.lineIndexAt(state.selection.main.head)
        val a = doc.lineStart(maxOf(0, line - fallbackLines))
        val lastLine = minOf(doc.lineCount - 1, line + fallbackLines)
        val b = if (lastLine + 1 < doc.lineCount) doc.lineStart(lastLine + 1) - 1 else doc.length
        return a..b
    }
}

/**
 * A plugin's say over typed text (closeBrackets, auto-indent triggers), like CM6's inputHandler:
 * given the main range [from, to) about to be replaced by [text], dispatch something else and
 * return true, or return false to let the text be typed. Called for plain typing only (`input`:
 * the hidden field, the web's key path, [EditorView.typeText]); never while an IME composes, never
 * for a paste.
 */
fun interface InputHandler {
    fun handle(target: CommandTarget, from: Int, to: Int, text: String): Boolean
}

/** Every plugin's [InputHandler], highest precedence first; the first to return true wins. */
val inputHandlerFacet: Facet<InputHandler, List<InputHandler>> = Facet.list("inputHandler")

/**
 * A plugin's say over a click or tap on a gutter marker column (the fold arrows, a comment bubble):
 * [line] is 0-based, [marker] the highest-precedence marker of [column] there (null: an empty cell).
 * Return true to take it; the host's [EditorView.onGutterClick] hears only the clicks no plugin took.
 */
fun interface GutterClickHandler {
    fun click(target: CommandTarget, column: String, line: Int, marker: dev.supermux.editor.core.GutterMarker?): Boolean
}

/** Every plugin's [GutterClickHandler], highest precedence first; the first to return true wins. */
val gutterClickFacet: Facet<GutterClickHandler, List<GutterClickHandler>> = Facet.list("gutterClick")

/**
 * A plugin's say over a click or tap on a drawn placeholder chip: a fold's "⋯" (a `Replace` whose
 * widget type has no registered content), an inline widget without content. [from, to) is the
 * decoration's range (the hidden text). Return true to take it (the fold plugin unfolds); the
 * host's [EditorView.onWidgetClick] hears the rest. Widgets with registered content take their own
 * pointer input.
 */
fun interface WidgetClickHandler {
    fun click(target: CommandTarget, key: dev.supermux.editor.core.WidgetKey, from: Int, to: Int): Boolean
}

/** Every plugin's [WidgetClickHandler], highest precedence first; the first to return true wins. */
val widgetClickFacet: Facet<WidgetClickHandler, List<WidgetClickHandler>> = Facet.list("widgetClick")

/**
 * The policy for a user edit that reaches INTO an atomic range (a fold, a range of
 * `atomicRangesFacet`): a Backspace at its end, a Delete at its start, a soft keyboard deleting its
 * placeholder, an autocorrect across its edge. The edit itself is never applied (it would take a
 * piece of text nobody can see). The first handler returning true took it: [AtomicDelete.deleteWhole]
 * deletes the range with the edit (CM6's behaviour, for a fold plugin that has undo). With none, the
 * editor's default is **unfold first** (JetBrains): the [revealFacet] handlers are asked to show the
 * range (the fold plugin unfolds), and the next Backspace deletes normally. [spec] is the edit.
 */
fun interface AtomicDeleteHandler {
    fun deleteInto(target: CommandTarget, from: Int, to: Int, spec: dev.supermux.editor.core.TransactionSpec): Boolean
}

/** Every plugin's [AtomicDeleteHandler], highest precedence first. */
val atomicDeleteFacet: Facet<AtomicDeleteHandler, List<AtomicDeleteHandler>> = Facet.list("atomicDelete")

/**
 * Show hidden range [from, to): its owner (the fold plugin) unfolds it, through its own state
 * effect, and returns true. Asked when a user edit reaches into an atomic range (the default
 * [AtomicDeleteHandler] policy), and when a transaction that scrolls into view puts the selection
 * inside a replaced range (a search match, a go-to-definition in a fold): without a handler that
 * shows it, the selection is moved out to the range's edge instead.
 */
fun interface RevealHandler {
    fun reveal(target: CommandTarget, from: Int, to: Int): Boolean
}

/** Every plugin's [RevealHandler], highest precedence first; the first to return true wins. */
val revealFacet: Facet<RevealHandler, List<RevealHandler>> = Facet.list("reveal")

/** Ready-made [AtomicDeleteHandler]s. */
object AtomicDelete {
    /**
     * CM6's policy: the edit goes ahead, grown to take the whole atomic range it reached into (a
     * Backspace at a fold's end deletes the fold). For a fold plugin with undo.
     */
    val deleteWhole = AtomicDeleteHandler { t, from, to, spec ->
        val st = t.state
        val cs = spec.changeSet ?: dev.supermux.editor.core.ChangeSet.of(st.doc.length, spec.changes)
        val grown = cs.iterChanges().map { c ->
            val a = if (c.fromA < to && c.toA > from) minOf(c.fromA, from) else c.fromA
            val b = if (c.fromA < to && c.toA > from) maxOf(c.toA, to) else c.toA
            dev.supermux.editor.core.ChangeSpec(a, b, c.inserted)
        }
        val changes = dev.supermux.editor.core.ChangeSet.of(st.doc.length, grown)
        t.dispatch(dev.supermux.editor.core.TransactionSpec(
            changeSet = changes,
            selection = st.selection.map(changes, 1),
            scrollIntoView = true,
            userEvent = spec.userEvent,
            annotations = listOf(EditorAnnotations.atomicWhole.of(true)),
        ))
        true
    }
}

/** Annotations the surface puts on the transactions it makes, for plugins (history) to read. */
object EditorAnnotations {
    /**
     * On a composition step (`input.ime`): the transaction just before this one was the SAME
     * composition's first character, dispatched as plain `input` because Compose tells the
     * surface a composition started only after that first edit was applied. A history groups the
     * two (and treats the first as IME input).
     */
    val imeJoinPrevious: dev.supermux.editor.core.AnnotationType<Boolean> = dev.supermux.editor.core.AnnotationType("imeJoinPrevious")

    /**
     * On a transaction from ANOTHER participant (a collaborator's edit, an agent's), whatever its
     * userEvent: the editor's local-input rules (atomic ranges) leave it alone. For M4/M5's sync.
     */
    val remote: dev.supermux.editor.core.AnnotationType<Boolean> = dev.supermux.editor.core.AnnotationType("remote")

    /** On the hidden field's own edits (the view checks they never insert its U+FFFC placeholder). */
    internal val fieldInput: dev.supermux.editor.core.AnnotationType<Boolean> = dev.supermux.editor.core.AnnotationType("fieldInput")

    /** On an edit that deletes atomic ranges whole on purpose ([AtomicDelete.deleteWhole]): not asked again. */
    val atomicWhole: dev.supermux.editor.core.AnnotationType<Boolean> = dev.supermux.editor.core.AnnotationType("atomicWhole")
}

/**
 * What the platform integration found at run time, for device checks and a host's debug screen.
 * [smartPunctuation]: iOS only (elsewhere "n/a"): "off" once the focused input view answers `.no`
 * for all three Smart Punctuation traits; a message saying what is wrong otherwise (see the
 * README: it depends on Compose Multiplatform's internal iOS input view classes).
 *
 * Debug API: for device checks and debug screens. The fields and their strings may change; do not
 * branch on them in production code.
 */
object EditorDiagnostics {
    var smartPunctuation: String by androidx.compose.runtime.mutableStateOf("n/a")
        internal set

    /** iOS only (elsewhere "n/a"): "mapped" once the space-bar trackpad moves the editor's caret. */
    var floatingCursor: String by androidx.compose.runtime.mutableStateOf("n/a")
        internal set

    /**
     * Debug assertion: how many edits a key-bound command dispatched WITHOUT a userEvent (a plugin
     * bug: history cannot group it and a reader cannot tell what it was). Each is also logged once
     * per process, with its changes. It is still policed as local input.
     */
    var unlabeledCommandEdits: Int = 0
        private set

    /**
     * How many times a plugin's input handler threw (a bad change plan): the keystroke was typed
     * plainly instead. The first is logged with its exception.
     */
    var pluginFailures: Int = 0
        private set

    internal fun reportPluginFailure(what: String, e: Throwable) {
        pluginFailures++
        if (pluginFailures == 1) println("editor-compose: a plugin's $what failed; typed plainly instead: $e")
    }

    internal fun reportUnlabeledCommandEdit(changes: String) {
        unlabeledCommandEdits++
        if (unlabeledCommandEdits == 1) println("editor-compose: a key-bound command dispatched an edit without a userEvent ($changes); give it one (\"input.*\", \"delete.*\", ...)")
    }
}

/**
 * Which editor owns a process-wide input setting (iOS Smart Punctuation off, the floating-cursor
 * bridge): the one that took the focus last. Focus moving between two editors reports the new one's
 * gain and the old one's loss in either order; only the OWNER's loss releases the setting.
 */
internal class FocusOwner<T : Any> {
    var owner: T? = null
        private set

    /** [c] gained or lost the focus; returns whether some editor owns the setting now. */
    fun changed(c: T, focused: Boolean): Boolean {
        if (focused) owner = c else if (owner === c) owner = null
        return owner != null
    }
}
