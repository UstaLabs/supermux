package dev.supermux.ui.editor

import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Zoom persistence: the file pane's `onFontSize` writeback ([UiPrefs.putEditorFontSize]) is what a
 * brand-new pane (a reopened session, a relaunch) reads, so a zoom survives. The live half (a zoom
 * on a view persists a whole px and never reloads the document) is `NativeEditorPaneTest`'s.
 */
class EditorZoomPersistenceTest {

    @Test
    fun writeback_persists_and_a_brand_new_pane_reads_the_persisted_size() = runTest {
        val store = InMemorySettingsStore()
        val prefs = UiPrefs(store)
        assertEquals(EDITOR_FONT_DEFAULT, prefs.editorFontSize.first())

        prefs.putEditorFontSize(19)
        assertEquals(19, prefs.editorFontSize.first())

        // A brand-new pane reads the persisted prefs, not the default.
        assertEquals(19, UiPrefs(store).editorFontSize.first())
    }
}
