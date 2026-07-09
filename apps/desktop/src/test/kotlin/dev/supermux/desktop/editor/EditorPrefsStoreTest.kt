package dev.supermux.desktop.editor

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-trip + clamp + first-run-default coverage for the editor settings store (M3-T4). */
class EditorPrefsStoreTest {

    private fun tempStore(): EditorPrefsStore {
        val dir = Files.createTempDirectory("editor-prefs-test")
        return EditorPrefsStore(dir.resolve("editor-settings.json"))
    }

    @Test fun load_returns_defaults_when_the_file_is_missing() {
        val store = tempStore()
        assertTrue(!store.path.exists())
        val prefs = store.load()
        assertEquals(EditorPrefs(lineWrap = true, fontSize = EDITOR_FONT_DEFAULT), prefs)
    }

    @Test fun save_then_load_round_trips_line_wrap_and_font_size() {
        val store = tempStore()
        store.save(EditorPrefs(lineWrap = false, fontSize = 18))
        val loaded = store.load()
        assertEquals(false, loaded.lineWrap)
        assertEquals(18, loaded.fontSize)
    }

    @Test fun save_clamps_an_out_of_range_font_size_to_the_bundle_range() {
        val store = tempStore()
        store.save(EditorPrefs(fontSize = 999))
        assertEquals(EDITOR_FONT_MAX, store.load().fontSize)

        store.save(EditorPrefs(fontSize = 1))
        assertEquals(EDITOR_FONT_MIN, store.load().fontSize)
    }

    @Test fun load_clamps_a_hand_edited_out_of_range_font_size() {
        val store = tempStore()
        Files.writeString(store.path, """{"lineWrap":true,"fontSize":40}""")
        assertEquals(EDITOR_FONT_MAX, store.load().fontSize)
    }

    @Test fun load_falls_back_to_defaults_on_a_corrupt_file() {
        val store = tempStore()
        Files.writeString(store.path, "{ not json")
        assertEquals(EditorPrefs(), store.load())
    }
}
