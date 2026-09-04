// The scroll seam between an editor panel and the engine it does not own. Pure Kotlin — no browser,
// no Compose — so the tab-switch capture rule is unit-testable directly.
package dev.supermux.ui.editor.engine

import dev.supermux.ui.editor.EditorState

/**
 * Seam letting the panel read the live editor's scroll offset without owning the engine (which is
 * encapsulated in the editor surface): the surface installs the real reader once its engine exists;
 * the default fires 0 so callers degrade to "no capture" before then. Used via
 * [captureOutgoingScroll] right before a tab switch / reveal.
 */
class EditorScrollReader {
    var read: ((Int) -> Unit) -> Unit = { it(0) }
    operator fun invoke(cb: (Int) -> Unit) = read(cb)
}

/**
 * Capture the CURRENT (outgoing) tab's scroll before a tab switch/reveal. DELIBERATE divergence from
 * Android's `engine.readScrollTop { editor.captureActiveScroll(it) }`: the read is async, so by the
 * time its callback lands `selectTab` has already flipped `activeTab` and captureActiveScroll would
 * mis-attribute the offset to the INCOMING tab. Snapshotting the outgoing tab at call time makes the
 * late callback always land on the right tab.
 */
fun captureOutgoingScroll(editor: EditorState, reader: EditorScrollReader) {
    val outgoing = editor.activeTab ?: return
    reader { scroll -> outgoing.scrollTop = scroll }
}
