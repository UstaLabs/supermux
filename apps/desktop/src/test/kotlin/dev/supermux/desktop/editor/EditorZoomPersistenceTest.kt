package dev.supermux.desktop.editor

import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import dev.supermux.ui.editor.engine.EditorPushPlanner

/**
 * M3-T5 "zoom persistence confirmation" — pins the two guarantees at the seam level (no JCEF):
 *
 *  1. The writeback path (DesktopEditorPanel's `onFontSize` — [UiPrefs.putEditorFontSize]) round-trips
 *     into a BRAND-NEW panel/engine's init push: a fresh [EditorPushPlanner] built from the persisted
 *     prefs carries the persisted size in its `onReady()` push, not the bundle default
 *     (EDITOR_FONT_DEFAULT). This is the "survives reopen/relaunch" half of the plan item.
 *  2. A zoom change on an ALREADY-ready engine never re-sends `cmSetContent`/`cmSetLanguage` (i.e.
 *     never reloads the file) — only `cmSetFontSize` — which is also what keeps
 *     [dev.supermux.desktop.editor.EditorSurface]'s `remember(jcefReady)` (WebCodeEditor.kt) safe to
 *     leave un-rekeyed on a font-size prop change: the engine instance is never rebuilt for a zoom.
 */
class EditorZoomPersistenceTest {

    @Test
    fun writeback_persists_and_a_brand_new_engine_init_pushes_the_persisted_size() = runTest {
        val store = InMemorySettingsStore()
        val prefs = UiPrefs(store)
        assertEquals(EDITOR_FONT_DEFAULT, prefs.editorFontSize.first()) // first run: bundle default

        // Simulate DesktopEditorPanel.onFontSize (SessionDetail.kt): the engine already applied the
        // zoom live (EditorSurface.onFontSize callback) — this is JUST the persistence writeback.
        prefs.putEditorFontSize(19)
        assertEquals(19, prefs.editorFontSize.first())

        // A brand-new panel/engine (session reopened, or the app relaunched) reads the persisted
        // prefs and seeds ITS planner from them — the init push must carry 19, not the default 13.
        val fresh = UiPrefs(store)
        val planner = EditorPushPlanner(fresh.editorLineWrap.first(), fresh.editorFontSize.first())
        planner.setDocument("hello", "a.kt")
        val js = planner.onReady()
        assertEquals(
            listOf(
                "cmSetContent(\"hello\")",
                "cmSetLanguage(\"a.kt\")",
                "cmSetLineWrap(true)",
                "cmSetFontSize(19)",
                "cmSetScrollTop(0)",
            ),
            js,
        )
    }

    @Test
    fun a_zoom_change_on_a_ready_engine_never_resends_content_or_language() {
        val p = EditorPushPlanner(lineWrap = true, fontSize = 13)
        p.setDocument("body", "a.kt")
        p.onReady()

        val js = p.setFontSize(20)

        assertEquals(listOf("cmSetFontSize(20)"), js) // ONLY the zoom push
        assertFalse(js.any { it.startsWith("cmSetContent") || it.startsWith("cmSetLanguage") })
        // The document itself is untouched — a later same-content push is a no-op, proving no
        // "reload" occurred as a side effect of the zoom (a real reload would have re-recorded it).
        assertEquals(emptyList(), p.setDocument("body", "a.kt"))
    }

    @Test
    fun repeated_zoom_changes_never_touch_content_even_across_many_steps() {
        val p = EditorPushPlanner(lineWrap = false, fontSize = 13)
        p.setDocument("unchanged", "b.py")
        p.onReady()

        val allPushes = (14..24).flatMap { p.setFontSize(it) }

        assertEquals((14..24).map { "cmSetFontSize($it)" }, allPushes)
        assertFalse(allPushes.any { it.startsWith("cmSetContent") })
        assertEquals(emptyList(), p.setDocument("unchanged", "b.py")) // still the same document
    }
}
