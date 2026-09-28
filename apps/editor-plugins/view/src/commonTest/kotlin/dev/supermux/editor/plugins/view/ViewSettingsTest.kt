package dev.supermux.editor.plugins.view

import dev.supermux.editor.compose.EditorThemeMode
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.fontSizeFacet
import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.compose.lineNumbersFacet
import dev.supermux.editor.compose.lineWrappingFacet
import dev.supermux.editor.compose.tabSizeFacet
import dev.supermux.editor.compose.themeModeFacet
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.fold.Fold
import dev.supermux.editor.plugins.fold.fold
import dev.supermux.editor.plugins.history.History
import dev.supermux.editor.plugins.history.history
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewSettingsTest {
    private val settings = EditorSettings(fontSize = 15f, lineWrap = false, tabSize = 2, indentUnit = "\t", showLineNumbers = false, theme = EditorThemeMode.DARK)

    @Test fun theSettingsAreTheEditorsFacets() {
        val st = EditorState.create("x", extensions = viewSettings(settings))
        assertEquals(15f, st.facet(fontSizeFacet))
        assertEquals(false, st.facet(lineWrappingFacet))
        assertEquals(2, st.facet(tabSizeFacet))
        assertEquals("\t", st.facet(indentUnitFacet))
        assertEquals(false, st.facet(lineNumbersFacet))
        assertEquals(EditorThemeMode.DARK, st.facet(themeModeFacet))
        assertEquals(settings, ViewSettings.current(st))
        // The defaults are today's editor's: 13 px, wrap on.
        val d = EditorState.create("x", extensions = viewSettings())
        assertEquals(13f, d.facet(fontSizeFacet))
        assertEquals(true, d.facet(lineWrappingFacet))
    }

    @Test fun aReconfigureAtRunTimeKeepsTheDocumentTheSelectionTheHistoryAndTheFolds() {
        val text = "fun a() {\n    one\n}\nend\n"
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(text.length), extensionOf(viewSettings(), history(), fold())))
        view.typeText("x")
        view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(2), userEvent = "select"))
        Fold.foldCode.run(view)
        val before = view.state
        ViewSettings.apply(view, settings)
        val st = view.state
        assertEquals(before.doc, st.doc)
        assertEquals(before.selection, st.selection)
        assertEquals(1, Fold.folded(st).size, "the fold was lost")
        assertEquals(1, History.undoDepth(st), "the undo history was lost")
        assertEquals(2, st.facet(tabSizeFacet))
        assertEquals(false, st.facet(lineWrappingFacet))
        assertEquals(EditorThemeMode.DARK, st.facet(themeModeFacet))
        History.undo.run(view)
        assertEquals(text, view.state.doc.toString())
        // One setting at a time.
        ViewSettings.update(view) { it.copy(lineWrap = true) }
        assertEquals(true, view.state.facet(lineWrappingFacet))
        assertEquals(2, view.state.facet(tabSizeFacet))
    }

    @Test fun onlyWhatChangedIsReconfigured() {
        val st = EditorState.create("x", extensions = viewSettings(settings))
        assertTrue(ViewSettings.reconfigure(st, settings).isEmpty())
        assertEquals(2, ViewSettings.reconfigure(st, settings.copy(tabSize = 8)).size, "the tab size and the settings data")
    }

    @Test fun sizesAreWholePxBetween10And24() {
        assertEquals(10, ViewSettings.clampFontSize(3f))
        assertEquals(24, ViewSettings.clampFontSize(40f))
        assertEquals(14, ViewSettings.clampFontSize(13.6f))
        assertEquals(13, ViewSettings.clampFontSize(Float.NaN))
    }

    @Test fun aZoomIsPersistedThroughTheHostCallbackAndKeptInTheSettings() {
        val view = EditorView(EditorState.create("x", extensions = viewSettings()))
        val saved = ArrayList<Int>()
        val report = ViewSettings.fontSizeReporter(view) { saved += it }
        report(14f)
        report(14.2f) // the same whole px: nothing new to keep
        report(30f)
        assertEquals(listOf(14, 24), saved)
        assertEquals(24f, ViewSettings.current(view.state)!!.fontSize)
        assertEquals(24f, view.state.facet(fontSizeFacet))
    }
}
