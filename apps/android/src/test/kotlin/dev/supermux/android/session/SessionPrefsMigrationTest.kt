package dev.supermux.android.session

import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
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
 * The one-time drain of Android's `cmux-session-list` collapsed groups into the shared
 * [SettingsStore] (cluster F1), and the synchronous read `MainActivity.onCreate` does on top of it.
 *
 * The SharedPreferences read itself needs a Context, so it is not covered here — what matters is
 * that the seed is one-way, non-destructive and safe to run on every launch (which it is).
 */
class SessionPrefsMigrationTest {

    private class MemStore : SettingsStore {
        val map = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun string(key: String): Flow<String?> = map.map { it[key] }
        override suspend fun putString(key: String, value: String?) {
            map.value = if (value == null) map.value - key else map.value + (key to value)
        }
    }

    @Test fun the_legacy_collapsed_groups_survive_the_move_and_are_returned() = runTest {
        val store = MemStore()
        val seeded = seedSessionListPrefs(store, setOf("/home/u/proj", "__pas__"))
        assertEquals(setOf("/home/u/proj", "__pas__"), seeded)
        assertEquals(
            """["/home/u/proj","__pas__"]""",
            store.string(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS).first(),
        )
    }

    @Test fun a_value_already_in_the_shared_store_wins() = runTest {
        val store = MemStore()
        store.putString(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS, """["/kept"]""")
        assertEquals(setOf("/kept"), seedSessionListPrefs(store, setOf("/legacy")))
    }

    @Test fun nothing_anywhere_seeds_nothing() = runTest {
        val store = MemStore()
        assertEquals(emptySet(), seedSessionListPrefs(store, emptySet()))
        assertNull(store.string(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS).first())
    }

    @Test fun running_it_on_every_launch_never_undoes_a_later_change() = runTest {
        val store = MemStore()
        seedSessionListPrefs(store, setOf("/legacy"))
        // The user expands the group after the upgrade…
        store.putString(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS, """[]""")
        // …and the next launch's migration must not put it back.
        assertEquals(emptySet(), seedSessionListPrefs(store, setOf("/legacy")))
    }

    @Test fun a_throwing_store_degrades_to_nothing_collapsed_instead_of_crashing_onCreate() = runTest {
        val broken = object : SettingsStore {
            // Throws on COLLECTION, the way a DataStore read actually fails (its flow is built
            // eagerly by `UiPrefs`, so a constructor-time throw would not be the real shape).
            override fun string(key: String): Flow<String?> = flow { throw IllegalStateException("corrupt DataStore") }
            override suspend fun putString(key: String, value: String?) = Unit
        }
        assertEquals(emptySet(), seedSessionListPrefs(broken, setOf("/legacy")))
    }
}
