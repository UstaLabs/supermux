package dev.supermux.desktop.session

import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LauncherStore] is the LEGACY `launcher-state.json` READER since cluster F1 — the launcher's
 * prefs/draft live in the shared settings store now (`UiPrefs`, `SettingsKeys.LAUNCHER_*`), and the
 * round-trip/clear cases that used to live here are `UiPrefsLauncherTest` in `:ui`.
 *
 * What is still this file's job: whatever an existing installation's file holds must decode, and a
 * missing or corrupt file must read as defaults rather than throw — the migration runs on EVERY
 * launch, inside `AppShell`'s composition, so a corrupt file must not take the shell down with it.
 */
class LauncherStoreTest {

    private fun tempStore(): LauncherStore {
        val dir = Files.createTempDirectory("launcher-store-test")
        return LauncherStore(dir.resolve("launcher-state.json"))
    }

    private fun storeWith(json: String): LauncherStore {
        val store = tempStore()
        Files.createDirectories(store.path.parent)
        Files.writeString(store.path, json)
        return store
    }

    @Test fun load_prefs_returns_defaults_when_the_file_is_missing() {
        val store = tempStore()
        assertTrue(!store.path.exists())
        assertEquals(LauncherPrefs(agent = "claude"), store.loadPrefs())
    }

    @Test fun load_draft_returns_defaults_when_the_file_is_missing() {
        val store = tempStore()
        val draft = store.loadDraft()
        assertEquals(LauncherDraft(workdir = null, useWorktree = true, baseBranch = "", text = ""), draft)
    }

    @Test fun an_existing_file_still_decodes_every_prefs_field() {
        val store = storeWith(
            """{"prefs":{"agent":"codex","models":{"codex":"gpt-5"},"reasoningLevels":{"codex":"high"}}}""",
        )
        val loaded = store.loadPrefs()
        assertEquals("codex", loaded.agent)
        assertEquals(mapOf("codex" to "gpt-5"), loaded.models)
        assertEquals(mapOf("codex" to "high"), loaded.reasoningLevels)
    }

    @Test fun an_existing_file_still_decodes_every_draft_field() {
        val store = storeWith(
            """{"draft":{"workdir":"/home/user/project","useWorktree":false,"baseBranch":"main","text":"hello"}}""",
        )
        val loaded = store.loadDraft()
        assertEquals("/home/user/project", loaded.workdir)
        assertEquals(false, loaded.useWorktree)
        assertEquals("main", loaded.baseBranch)
        assertEquals("hello", loaded.text)
    }

    @Test fun load_falls_back_to_defaults_on_a_corrupt_file() {
        val store = storeWith("{ not json")
        assertEquals(LauncherPrefs(), store.loadPrefs())
        assertEquals(LauncherDraft(), store.loadDraft())
    }

    @Test fun nothing_writes_the_file_any_more() {
        val store = storeWith("""{"prefs":{"agent":"codex"}}""")
        store.loadPrefs()
        store.loadDraft()
        val bytes = Files.readAllBytes(store.path).decodeToString()
        assertEquals("""{"prefs":{"agent":"codex"}}""", bytes)
        val names = Files.list(store.path.parent).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("launcher-state.json"), names)
    }

    @Test fun default_path_is_under_config_dir_and_sibling_of_other_state_files() {
        val p = LauncherStore.defaultPath()
        assertTrue(p.toString().contains("supermux"), "was $p")
        assertEquals("launcher-state.json", p.fileName.toString())
    }
}
