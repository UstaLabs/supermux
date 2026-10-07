package dev.supermux.android.settings

import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one-time drain of Android's `cmux-editor-settings` appearance values into the shared
 * [SettingsStore] (cluster E7). Nobody should lose their theme or text size to the move, and
 * running it on every launch — which MainActivity does — must not undo a later change.
 */
class AppearancePrefsMigrationTest {

    private class MemStore : SettingsStore {
        val map = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun string(key: String): Flow<String?> = map.map { it[key] }
        override suspend fun putString(key: String, value: String?) {
            map.value = if (value == null) map.value - key else map.value + (key to value)
        }
    }

    @Test fun the_stored_android_values_survive_the_move() = runTest {
        val store = MemStore()
        migrateAppearancePrefs(
            store,
            LegacyAppearancePrefs(appearance = "LIGHT", dynamicColor = true, textScale = 1.15f),
        )
        assertEquals("LIGHT", store.string(SettingsKeys.APPEARANCE).first())
        assertEquals("true", store.string(SettingsKeys.DYNAMIC_COLOR).first())
        assertEquals(1.15f, store.string(SettingsKeys.TEXT_SCALE).first()?.toFloat())
    }

    @Test fun nothing_stored_in_the_old_file_writes_nothing() = runTest {
        val store = MemStore()
        migrateAppearancePrefs(store, LegacyAppearancePrefs())
        assertEquals(emptyMap(), store.map.value)
    }

    @Test fun a_value_already_in_the_shared_store_is_never_overwritten() = runTest {
        val store = MemStore()
        store.putString(SettingsKeys.APPEARANCE, "DARK")
        migrateAppearancePrefs(store, LegacyAppearancePrefs(appearance = "LIGHT", textScale = 1.2f))
        // The post-upgrade choice wins; the untouched key still migrates.
        assertEquals("DARK", store.string(SettingsKeys.APPEARANCE).first())
        assertEquals(1.2f, store.string(SettingsKeys.TEXT_SCALE).first()?.toFloat())
    }

    @Test fun running_it_twice_changes_nothing_the_second_time() = runTest {
        val store = MemStore()
        val legacy = LegacyAppearancePrefs(appearance = "LIGHT", dynamicColor = false, textScale = 1.1f)
        migrateAppearancePrefs(store, legacy)
        store.putString(SettingsKeys.APPEARANCE, "SYSTEM") // the user changes it afterwards
        migrateAppearancePrefs(store, legacy)
        assertEquals("SYSTEM", store.string(SettingsKeys.APPEARANCE).first())
    }

    @Test fun an_unparsable_stored_mode_is_dropped_rather_than_migrated() = runTest {
        val store = MemStore()
        migrateAppearancePrefs(store, LegacyAppearancePrefs(appearance = "PLAID"))
        assertNull(store.string(SettingsKeys.APPEARANCE).first())
    }

    @Test fun an_out_of_range_text_scale_is_clamped_on_the_way_in() = runTest {
        val store = MemStore()
        migrateAppearancePrefs(store, LegacyAppearancePrefs(textScale = 4f))
        assertEquals(1.3f, store.string(SettingsKeys.TEXT_SCALE).first()?.toFloat())
    }

    // ── the synchronous cold-start seed ───────────────────────────────────────────────────────
    //
    // `MainActivity` reads this BEFORE `setContent` and hands it to `collectAsState` as the initial
    // value, because a DataStore read is asynchronous and a hardcoded default would paint the first
    // frames of every cold start in the wrong theme. These pin that the seed IS the stored value —
    // never the default — in each of the three states the store can be in.

    @Test fun the_seed_is_the_migrated_legacy_value_on_the_first_launch_after_the_upgrade() = runTest {
        val store = MemStore()
        val seed = seedAppearancePrefs(
            store,
            LegacyAppearancePrefs(appearance = "LIGHT", textScale = 1.2f),
        )
        assertEquals(AppearanceMode.LIGHT, seed.appearance)
        assertEquals(1.2f, seed.textScale)
    }

    @Test fun the_seed_is_the_shared_store_value_once_it_holds_one() = runTest {
        val store = MemStore()
        store.putString(SettingsKeys.APPEARANCE, "DARK")
        store.putString(SettingsKeys.TEXT_SCALE, "0.9")
        // A stale legacy file must not win — the post-upgrade choice is the answer.
        val seed = seedAppearancePrefs(
            store,
            LegacyAppearancePrefs(appearance = "LIGHT", textScale = 1.3f),
        )
        assertEquals(AppearanceMode.DARK, seed.appearance)
        assertEquals(0.9f, seed.textScale)
    }

    @Test fun the_seed_falls_back_to_the_host_default_with_nothing_stored_anywhere() = runTest {
        val seed = seedAppearancePrefs(MemStore(), LegacyAppearancePrefs())
        assertEquals(AppearanceMode.SYSTEM, seed.appearance)
        assertEquals(1f, seed.textScale)
    }

    /**
     * A corrupt/unreadable DataStore must NOT take `onCreate` down with it (E7 re-review minor):
     * the seed runs under `runBlocking` before `setContent`, so an exception escaping the MIGRATION
     * — not just the reads — would be a launch crash instead of a wrong-looking theme.
     */
    @Test fun a_throwing_store_degrades_to_the_default_instead_of_crashing_onCreate() = runTest {
        val broken = object : SettingsStore {
            // Throws on COLLECTION, the way a DataStore read actually fails (its flow is built
            // eagerly by `UiPrefs`, so a constructor-time throw would not be the real shape).
            override fun string(key: String): Flow<String?> = flow { throw IllegalStateException("corrupt DataStore") }
            override suspend fun putString(key: String, value: String?) = Unit
        }
        val seed = seedAppearancePrefs(
            broken,
            LegacyAppearancePrefs(appearance = "LIGHT", textScale = 1.2f),
        )
        assertEquals(AppearanceMode.SYSTEM, seed.appearance)
        assertEquals(1f, seed.textScale)
    }
}
