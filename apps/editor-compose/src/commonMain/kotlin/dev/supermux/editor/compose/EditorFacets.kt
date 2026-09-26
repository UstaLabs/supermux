package dev.supermux.editor.compose

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
