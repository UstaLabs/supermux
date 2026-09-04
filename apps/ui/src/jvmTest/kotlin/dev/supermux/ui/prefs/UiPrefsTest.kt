package dev.supermux.ui.prefs

import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.ChatDetailLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Copied from `:shared` jvmTest `state/TestDeps.kt` — `:ui` cannot see another module's tests. */
class FakeSettingsStore : SettingsStore {
    val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}

class UiPrefsTest {

    private fun prefs(store: FakeSettingsStore = FakeSettingsStore()) = store to UiPrefs(store)

    @Test
    fun defaults_when_nothing_is_stored() = runTest {
        val (_, p) = prefs()
        assertEquals(true, p.editorLineWrap.first())
        assertEquals(13, p.editorFontSize.first())
        assertEquals(true, p.editorDiffTreeView.first())
        assertEquals(ChatDetailLevel.MEDIUM, p.chatDetailLevel.first())
    }

    @Test
    fun editor_line_wrap_round_trips() = runTest {
        val (_, p) = prefs()
        p.putEditorLineWrap(false)
        assertEquals(false, p.editorLineWrap.first())
        p.putEditorLineWrap(true)
        assertEquals(true, p.editorLineWrap.first())
    }

    @Test
    fun editor_font_size_round_trips() = runTest {
        val (_, p) = prefs()
        p.putEditorFontSize(18)
        assertEquals(18, p.editorFontSize.first())
    }

    @Test
    fun editor_font_size_is_clamped_on_write_and_on_read() = runTest {
        val (store, p) = prefs()
        p.putEditorFontSize(999)
        assertEquals(EDITOR_FONT_MAX, p.editorFontSize.first())
        p.putEditorFontSize(1)
        assertEquals(EDITOR_FONT_MIN, p.editorFontSize.first())
        // A hand-edited / drifted stored value is clamped on the way out too.
        store.putString(SettingsKeys.EDITOR_FONT_SIZE, "400")
        assertEquals(EDITOR_FONT_MAX, p.editorFontSize.first())
    }

    @Test
    fun garbage_stored_values_fall_back_to_the_defaults() = runTest {
        val (store, p) = prefs()
        store.putString(SettingsKeys.EDITOR_FONT_SIZE, "not-a-number")
        store.putString(SettingsKeys.EDITOR_LINE_WRAP, "yes")
        store.putString(SettingsKeys.EDITOR_DIFF_TREE_VIEW, "")
        store.putString(SettingsKeys.CHAT_DETAIL_LEVEL, "enormous")
        assertEquals(13, p.editorFontSize.first())
        assertEquals(true, p.editorLineWrap.first())
        assertEquals(true, p.editorDiffTreeView.first())
        assertEquals(ChatDetailLevel.MEDIUM, p.chatDetailLevel.first())
    }

    @Test
    fun diff_tree_view_round_trips() = runTest {
        val (_, p) = prefs()
        p.putEditorDiffTreeView(false)
        assertEquals(false, p.editorDiffTreeView.first())
    }

    @Test
    fun chat_detail_level_round_trips_every_level_as_the_wire_string() = runTest {
        val (store, p) = prefs()
        for (level in ChatDetailLevel.entries) {
            p.putChatDetailLevel(level)
            assertEquals(level, p.chatDetailLevel.first())
            assertEquals(level.wire, store.map.value[SettingsKeys.CHAT_DETAIL_LEVEL])
        }
    }

    @Test
    fun writes_land_on_the_documented_keys() = runTest {
        val (store, p) = prefs()
        p.putEditorLineWrap(false)
        p.putEditorFontSize(16)
        p.putEditorDiffTreeView(false)
        p.putChatDetailLevel(ChatDetailLevel.HIGH)
        assertEquals(
            mapOf(
                SettingsKeys.EDITOR_LINE_WRAP to "false",
                SettingsKeys.EDITOR_FONT_SIZE to "16",
                SettingsKeys.EDITOR_DIFF_TREE_VIEW to "false",
                SettingsKeys.CHAT_DETAIL_LEVEL to "high",
            ),
            store.map.value,
        )
    }

    @Test
    fun in_memory_store_backs_a_working_UiPrefs() = runTest {
        val p = UiPrefs(InMemorySettingsStore())
        assertEquals(13, p.editorFontSize.first())
        p.putEditorFontSize(20)
        assertEquals(20, p.editorFontSize.first())
    }
}
