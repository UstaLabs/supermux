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

/** Annotations the surface puts on the transactions it makes, for plugins (history, M4) to read. */
object EditorAnnotations {
    /**
     * On a composition step (`input.ime`): the transaction just before this one was the SAME
     * composition's first character, dispatched as plain `input` because Compose tells the
     * surface a composition started only after that first edit was applied. A history groups the
     * two (and treats the first as IME input).
     */
    val imeJoinPrevious: dev.supermux.editor.core.AnnotationType<Boolean> = dev.supermux.editor.core.AnnotationType("imeJoinPrevious")
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
}
