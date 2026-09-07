package dev.supermux.ui.prefs

import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    // ── appearance (cluster E7) ───────────────────────────────────────────────────────────────

    @Test
    fun appearance_round_trips_and_keeps_never_chosen_distinguishable() = runTest {
        val (_, p) = prefs()
        assertNull(p.appearanceMode.first())
        // Two hosts, two fallbacks, one stored value.
        assertEquals(AppearanceMode.SYSTEM, p.appearance(AppearanceMode.SYSTEM).first())
        assertEquals(AppearanceMode.DARK, p.appearance(AppearanceMode.DARK).first())
        p.putAppearance(AppearanceMode.LIGHT)
        assertEquals(AppearanceMode.LIGHT, p.appearance(AppearanceMode.DARK).first())
    }

    @Test
    fun an_unrecognised_stored_mode_reads_as_never_chosen() = runTest {
        val (store, p) = prefs()
        store.map.value = mapOf(SettingsKeys.APPEARANCE to "PLAID")
        assertNull(p.appearanceMode.first())
        assertEquals(AppearanceMode.DARK, p.appearance(AppearanceMode.DARK).first())
    }

    @Test
    fun text_scale_round_trips_and_clamps_both_ways() = runTest {
        val (store, p) = prefs()
        assertEquals(1f, p.textScale.first())
        p.putTextScale(5f)
        assertEquals(1.3f, p.textScale.first())
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "0.1")
        assertEquals(0.9f, p.textScale.first())
    }

    // `seedAppearance` is what desktop's `Main.kt` reads synchronously before its first frame —
    // `ShellUiState.appearance` defaults to DARK, so resolving this in an effect would show a LIGHT
    // user one dark composition on every launch.

    @Test
    fun seed_migrates_the_hosts_legacy_value_when_nothing_is_stored() = runTest {
        val (store, p) = prefs()
        assertEquals(
            AppearanceMode.LIGHT,
            p.seedAppearance(default = AppearanceMode.DARK, legacy = AppearanceMode.LIGHT),
        )
        // ...and writes it through, so the old file is never consulted again.
        assertEquals("LIGHT", store.map.value[SettingsKeys.APPEARANCE])
    }

    @Test
    fun seed_prefers_the_stored_value_over_the_legacy_one() = runTest {
        val (store, p) = prefs()
        p.putAppearance(AppearanceMode.SYSTEM)
        assertEquals(
            AppearanceMode.SYSTEM,
            p.seedAppearance(default = AppearanceMode.DARK, legacy = AppearanceMode.LIGHT),
        )
        assertEquals("SYSTEM", store.map.value[SettingsKeys.APPEARANCE])
    }

    @Test
    fun seed_falls_back_to_the_host_default_and_writes_nothing() = runTest {
        val (store, p) = prefs()
        assertEquals(
            AppearanceMode.DARK,
            p.seedAppearance(default = AppearanceMode.DARK, legacy = null),
        )
        assertEquals(emptyMap(), store.map.value)
    }

    // ── the first-run intro's seen flag (cluster G6) ──────────────────────────────────────────
    // Read SYNCHRONOUSLY by both entry points before the first frame, so these are the exact
    // calls `Main.kt` and `MainActivity` make. Desktop's legacy value is the contents of the
    // `intro-seen` marker file; Android never had one and always passes null.

    @Test
    fun a_fresh_install_has_not_seen_the_intro_and_nothing_is_written() = runTest {
        val (store, p) = prefs()
        assertEquals(false, p.seedIntroSeen(legacyVersion = null, introVersion = 1))
        assertEquals(emptyMap(), store.map.value)
    }

    @Test
    fun the_desktop_marker_file_is_drained_once_into_the_shared_store() = runTest {
        val (store, p) = prefs()
        assertEquals(true, p.seedIntroSeen(legacyVersion = 1, introVersion = 1))
        // Written through, so the old marker file is never consulted again.
        assertEquals("1", store.map.value[SettingsKeys.INTRO_SEEN])
    }

    @Test
    fun a_stored_version_wins_over_the_legacy_marker() = runTest {
        val (store, p) = prefs()
        p.putIntroSeen(2)
        assertEquals(true, p.seedIntroSeen(legacyVersion = 1, introVersion = 2))
        assertEquals("2", store.map.value[SettingsKeys.INTRO_SEEN])
    }

    @Test
    fun a_bumped_intro_version_replays_for_someone_who_saw_the_old_one() = runTest {
        val (_, p) = prefs()
        p.putIntroSeen(1)
        assertEquals(false, p.seedIntroSeen(legacyVersion = null, introVersion = 2))
        assertEquals(1, p.introSeenVersion.first())
    }

    @Test
    fun seeding_the_intro_flag_is_idempotent() = runTest {
        val (store, p) = prefs()
        assertEquals(true, p.seedIntroSeen(legacyVersion = 1, introVersion = 1))
        assertEquals(true, p.seedIntroSeen(legacyVersion = 1, introVersion = 1))
        assertEquals("1", store.map.value[SettingsKeys.INTRO_SEEN])
    }

    @Test
    fun a_missing_or_garbage_marker_reads_as_never_seen() = runTest {
        val (store, p) = prefs()
        store.map.value = mapOf(SettingsKeys.INTRO_SEEN to "not-a-number")
        assertEquals(0, p.introSeenVersion.first())
        assertEquals(false, p.seedIntroSeen(legacyVersion = 0, introVersion = 1))
    }
}
