package dev.supermux.desktop.editor

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * M3-T5 "zoom persistence confirmation" — pins the two guarantees at the seam level (no KCEF):
 *
 *  1. The writeback path (DesktopEditorPanel's `onFontSize` — EditorPrefsStore.save) round-trips
 *     into a BRAND-NEW panel/engine's init push: a fresh [EditorPushPlanner] built from the persisted
 *     [EditorPrefs] carries the persisted size in its `onReady()` push, not the bundle default
 *     (EDITOR_FONT_DEFAULT). This is the "survives reopen/relaunch" half of the plan item.
 *  2. A zoom change on an ALREADY-ready engine never re-sends `cmSetContent`/`cmSetLanguage` (i.e.
 *     never reloads the file) — only `cmSetFontSize` — which is also what keeps
 *     [dev.supermux.desktop.editor.EditorSurface]'s `remember(kcefReady)` (WebCodeEditor.kt) safe to
 *     leave un-rekeyed on a font-size prop change: the engine instance is never rebuilt for a zoom.
 */
class EditorZoomPersistenceTest {

    private fun tempStore() =
        EditorPrefsStore(Files.createTempDirectory("zoom-persist-test").resolve("editor-settings.json"))

    @Test
    fun writeback_persists_and_a_brand_new_engine_init_pushes_the_persisted_size() {
        val store = tempStore()
        assertEquals(EDITOR_FONT_DEFAULT, store.load().fontSize) // first run: bundle default

        // Simulate DesktopEditorPanel.onFontSize (SessionDetail.kt): the engine already applied the
        // zoom live (EditorSurface.onFontSize callback) — this is JUST the persistence writeback.
        var prefs = store.load()
        val onFontSize: (Int) -> Unit = { px -> prefs = prefs.copy(fontSize = px).clamped(); store.save(prefs) }
        onFontSize(19)
        assertEquals(19, store.load().fontSize)

        // A brand-new panel/engine (session reopened, or the app relaunched) loads the persisted
        // prefs and seeds ITS planner from them — the init push must carry 19, not the default 13.
        val freshPrefs = store.load()
        val planner = EditorPushPlanner(freshPrefs.lineWrap, freshPrefs.fontSize)
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
