package dev.supermux.desktop.session

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-trip + missing/corrupt-default + clear coverage for the launcher prefs/draft store (M4a-T2). */
class LauncherStoreTest {

    private fun tempStore(): LauncherStore {
        val dir = Files.createTempDirectory("launcher-store-test")
        return LauncherStore(dir.resolve("launcher-state.json"))
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

    @Test fun save_then_load_round_trips_prefs_across_instances() {
        val store = tempStore()
        store.savePrefs(
            LauncherPrefs(
                agent = "codex",
                models = mapOf("codex" to "gpt-5"),
                reasoningLevels = mapOf("codex" to "high"),
            ),
        )
        val again = LauncherStore(store.path)
        val loaded = again.loadPrefs()
        assertEquals("codex", loaded.agent)
        assertEquals(mapOf("codex" to "gpt-5"), loaded.models)
        assertEquals(mapOf("codex" to "high"), loaded.reasoningLevels)
    }

    @Test fun save_then_load_round_trips_draft_across_instances() {
        val store = tempStore()
        store.saveDraft(
            LauncherDraft(workdir = "/home/user/project", useWorktree = false, baseBranch = "main", text = "hello"),
        )
        val again = LauncherStore(store.path)
        val loaded = again.loadDraft()
        assertEquals("/home/user/project", loaded.workdir)
        assertEquals(false, loaded.useWorktree)
        assertEquals("main", loaded.baseBranch)
        assertEquals("hello", loaded.text)
    }

    @Test fun load_falls_back_to_defaults_on_a_corrupt_file() {
        val store = tempStore()
        Files.createDirectories(store.path.parent)
        Files.writeString(store.path, "{ not json")
        assertEquals(LauncherPrefs(), store.loadPrefs())
        assertEquals(LauncherDraft(), store.loadDraft())
    }

    @Test fun clear_draft_resets_draft_but_keeps_prefs() {
        val store = tempStore()
        store.savePrefs(LauncherPrefs(agent = "codex", models = mapOf("codex" to "gpt-5")))
        store.saveDraft(LauncherDraft(workdir = "/tmp/x", useWorktree = false, baseBranch = "dev", text = "wip"))

        store.clearDraft()

        assertEquals(LauncherDraft(), store.loadDraft())
        val prefs = store.loadPrefs()
        assertEquals("codex", prefs.agent)
        assertEquals(mapOf("codex" to "gpt-5"), prefs.models)
    }

    @Test fun temp_file_not_left_behind_after_saves() {
        val store = tempStore()
        store.savePrefs(LauncherPrefs(agent = "claude"))
        store.saveDraft(LauncherDraft(text = "draft text"))
        store.clearDraft()
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
