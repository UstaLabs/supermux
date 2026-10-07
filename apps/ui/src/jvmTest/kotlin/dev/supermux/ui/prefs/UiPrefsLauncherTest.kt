// The launcher pair + the session list's collapsed groups, now that `UiPrefs` is their ONE owner
// (cluster F1). This is desktop's old `LauncherStoreTest` reborn: the round-trip, the "empty draft
// clears the key" rule and the corrupt-value fallbacks moved here with the values themselves, and
// the two one-way migrations (desktop's `launcher-state.json` / `ui-state.json`, Android's
// `cmux-session-list` SharedPreferences) are pinned in all three states a host can be in —
// legacy present, store already holds a value, nothing anywhere.
package dev.supermux.ui.prefs

import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.SettingsKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiPrefsLauncherTest {

    private fun prefs(store: FakeSettingsStore = FakeSettingsStore()) = store to UiPrefs(store)

    // ── Launcher prefs / draft ──────────────────────────────────────────────────────────────────

    @Test
    fun defaults_when_nothing_is_stored() = runTest {
        val (_, p) = prefs()
        assertEquals(LauncherPrefs(agent = "claude"), p.launcherPrefs.first())
        assertEquals(LauncherDraft(workdir = null, useWorktree = true, baseBranch = "", text = ""), p.launcherDraft.first())
        assertEquals(emptySet(), p.collapsedProjectPaths.first())
    }

    @Test
    fun launcher_prefs_round_trip_every_field() = runTest {
        val (_, p) = prefs()
        p.putLauncherPrefs(
            LauncherPrefs(
                agent = "codex",
                models = mapOf("codex" to "gpt-5"),
                reasoningLevels = mapOf("codex" to "high"),
            ),
        )
        val loaded = p.launcherPrefs.first()
        assertEquals("codex", loaded.agent)
        assertEquals(mapOf("codex" to "gpt-5"), loaded.models)
        assertEquals(mapOf("codex" to "high"), loaded.reasoningLevels)
    }

    @Test
    fun launcher_draft_round_trips_and_an_empty_draft_clears_the_key() = runTest {
        val (store, p) = prefs()
        p.putLauncherDraft(
            LauncherDraft(workdir = "/home/user/project", useWorktree = false, baseBranch = "main", text = "hello"),
        )
        val loaded = p.launcherDraft.first()
        assertEquals("/home/user/project", loaded.workdir)
        assertEquals(false, loaded.useWorktree)
        assertEquals("main", loaded.baseBranch)
        assertEquals("hello", loaded.text)

        p.putLauncherDraft(LauncherDraft())
        assertNull(store.map.value[SettingsKeys.LAUNCHER_DRAFT])
        assertEquals(LauncherDraft(), p.launcherDraft.first())
    }

    @Test
    fun clearing_the_draft_leaves_the_prefs_alone() = runTest {
        val (_, p) = prefs()
        p.putLauncherPrefs(LauncherPrefs(agent = "codex", models = mapOf("codex" to "gpt-5")))
        p.putLauncherDraft(LauncherDraft(workdir = "/tmp/x", useWorktree = false, baseBranch = "dev", text = "wip"))

        p.clearLauncherDraft()

        assertEquals(LauncherDraft(), p.launcherDraft.first())
        assertEquals("codex", p.launcherPrefs.first().agent)
        assertEquals(mapOf("codex" to "gpt-5"), p.launcherPrefs.first().models)
    }

    @Test
    fun a_corrupt_stored_value_reads_as_the_defaults() = runTest {
        val (store, p) = prefs()
        store.putString(SettingsKeys.LAUNCHER_PREFS, "{ not json")
        store.putString(SettingsKeys.LAUNCHER_DRAFT, "{ not json")
        assertEquals(LauncherPrefs(), p.launcherPrefs.first())
        assertEquals(LauncherDraft(), p.launcherDraft.first())
    }

    // ── Migration: desktop's launcher-state.json ────────────────────────────────────────────────

    @Test
    fun seed_drains_the_legacy_launcher_file_when_nothing_is_stored() = runTest {
        val (store, p) = prefs()
        p.seedLauncher(
            legacyPrefs = LauncherPrefs(agent = "codex", models = mapOf("codex" to "gpt-5")),
            legacyDraft = LauncherDraft(workdir = "/legacy", text = "half-typed"),
        )
        assertEquals("codex", p.launcherPrefs.first().agent)
        assertEquals("/legacy", p.launcherDraft.first().workdir)
        assertEquals("half-typed", p.launcherDraft.first().text)
        assertTrue(store.map.value.containsKey(SettingsKeys.LAUNCHER_PREFS))
    }

    @Test
    fun seed_never_overwrites_what_the_store_already_holds() = runTest {
        val (_, p) = prefs()
        p.putLauncherPrefs(LauncherPrefs(agent = "claude"))
        p.putLauncherDraft(LauncherDraft(text = "typed after the upgrade"))

        p.seedLauncher(
            legacyPrefs = LauncherPrefs(agent = "codex"),
            legacyDraft = LauncherDraft(text = "stale"),
        )

        assertEquals("claude", p.launcherPrefs.first().agent)
        assertEquals("typed after the upgrade", p.launcherDraft.first().text)
    }

    @Test
    fun seed_is_idempotent_and_writes_nothing_with_no_legacy_state() = runTest {
        val (store, p) = prefs()
        // Android's case: its launcher pair was ALREADY on these keys, so it seeds from nothing.
        p.seedLauncher(legacyPrefs = null, legacyDraft = null)
        assertEquals(emptyMap(), store.map.value)
        // And a legacy file that is itself empty must not write defaults through either.
        p.seedLauncher(legacyPrefs = LauncherPrefs(), legacyDraft = LauncherDraft())
        assertEquals(emptyMap(), store.map.value)
    }

    @Test
    fun seeding_twice_changes_nothing_the_second_time() = runTest {
        val (_, p) = prefs()
        p.seedLauncher(legacyPrefs = LauncherPrefs(agent = "codex"))
        p.putLauncherPrefs(LauncherPrefs(agent = "cursor"))
        p.seedLauncher(legacyPrefs = LauncherPrefs(agent = "codex"))
        assertEquals("cursor", p.launcherPrefs.first().agent)
    }

    // ── Collapsed project groups + their migration ──────────────────────────────────────────────

    @Test
    fun collapsed_paths_round_trip_and_store_sorted() = runTest {
        val (store, p) = prefs()
        p.putCollapsedProjectPaths(setOf("/z/last", "/a/first", "__pas__"))
        assertEquals(setOf("/a/first", "/z/last", "__pas__"), p.collapsedProjectPaths.first())
        assertEquals(
            """["/a/first","/z/last","__pas__"]""",
            store.map.value[SettingsKeys.SESSION_LIST_COLLAPSED_PATHS],
        )
    }

    @Test
    fun a_corrupt_collapsed_paths_value_reads_as_empty() = runTest {
        val (store, p) = prefs()
        store.putString(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS, "not json")
        assertEquals(emptySet(), p.collapsedProjectPaths.first())
    }

    @Test
    fun seed_drains_the_hosts_legacy_collapsed_paths_and_returns_them() = runTest {
        val (store, p) = prefs()
        val seeded = p.seedCollapsedProjectPaths(setOf("/home/a/proj", "/tmp/other"))
        assertEquals(setOf("/home/a/proj", "/tmp/other"), seeded)
        assertTrue(store.map.value.containsKey(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS))
    }

    @Test
    fun seed_prefers_the_stored_collapsed_paths_over_the_legacy_ones() = runTest {
        val (_, p) = prefs()
        p.putCollapsedProjectPaths(setOf("/kept"))
        assertEquals(setOf("/kept"), p.seedCollapsedProjectPaths(setOf("/legacy")))
    }

    @Test
    fun seed_with_nothing_anywhere_returns_empty_and_writes_nothing() = runTest {
        val (store, p) = prefs()
        assertEquals(emptySet(), p.seedCollapsedProjectPaths(null))
        assertEquals(emptySet(), p.seedCollapsedProjectPaths(emptySet()))
        assertEquals(emptyMap(), store.map.value)
    }

    @Test
    fun an_emptied_collapsed_set_survives_a_later_seed() = runTest {
        // Expanding the last group stores "[]", which is NOT "never chosen" — a re-run of the
        // migration must not resurrect the legacy set on top of it.
        val (_, p) = prefs()
        p.putCollapsedProjectPaths(emptySet())
        assertEquals(emptySet(), p.seedCollapsedProjectPaths(setOf("/legacy")))
    }
}
