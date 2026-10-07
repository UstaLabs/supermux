package dev.supermux.settings

import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.SettingsKeys
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SwiftUI → shared-store pref mapping (cluster H2.1).
 *
 * Every payload here is what the Swift app ACTUALLY writes, taken from the source: `appearance`
 * from `SupermuxApp.swift`'s `@AppStorage`, the launcher blobs from `LauncherStateStore`'s
 * `JSONEncoder`, `cmux:collapsed-paths` from `SessionsListView`, `ipad.sidebar.*` from
 * `WorkspaceLayoutModel`. A test written against what the mapping happens to accept would prove
 * nothing — the point is that an upgrading user's real stored values land intact.
 */
class SwiftPrefsMigrationTest {

    private fun migrate(legacy: LegacySwiftPrefs): Map<String, String> =
        swiftPrefMigrations(legacy).toMap()

    @Test
    fun `nothing stored migrates nothing`() {
        assertEquals(emptyList(), swiftPrefMigrations(LegacySwiftPrefs()))
    }

    @Test
    fun `appearance is uppercased for AppearanceMode name`() {
        assertEquals("SYSTEM", migrate(LegacySwiftPrefs(appearance = "system"))[SettingsKeys.APPEARANCE])
        assertEquals("LIGHT", migrate(LegacySwiftPrefs(appearance = "light"))[SettingsKeys.APPEARANCE])
        assertEquals("DARK", migrate(LegacySwiftPrefs(appearance = "dark"))[SettingsKeys.APPEARANCE])
    }

    @Test
    fun `an unrecognised appearance is dropped rather than passed through`() {
        assertNull(migrate(LegacySwiftPrefs(appearance = "sepia"))[SettingsKeys.APPEARANCE])
    }

    @Test
    fun `chat detail keeps its lowercase wire form`() {
        for (level in listOf("low", "medium", "high")) {
            assertEquals(level, migrate(LegacySwiftPrefs(chatDetailLevel = level))[SettingsKeys.CHAT_DETAIL_LEVEL])
        }
        assertNull(migrate(LegacySwiftPrefs(chatDetailLevel = "MEDIUM"))[SettingsKeys.CHAT_DETAIL_LEVEL])
    }

    @Test
    fun `drafts are re-keyed onto the shared draft namespace`() {
        val out = migrate(LegacySwiftPrefs(drafts = mapOf("s1" to "half a sentence", "s2" to "")))
        assertEquals("half a sentence", out[SettingsKeys.draft("s1")])
        // A blank draft is not a draft — Swift leaves the key behind after the composer is cleared.
        assertNull(out[SettingsKeys.draft("s2")])
    }

    @Test
    fun `editor values become the strings UiPrefs parses back`() {
        val out = migrate(LegacySwiftPrefs(editorFontSize = 17, editorLineWrap = false))
        assertEquals("17", out[SettingsKeys.EDITOR_FONT_SIZE])
        assertEquals("false", out[SettingsKeys.EDITOR_LINE_WRAP])
    }

    @Test
    fun `sidebar chrome carries over`() {
        val out = migrate(LegacySwiftPrefs(sidebarWidthDp = 384.0, sidebarCollapsed = true))
        assertEquals("384.0", out[SettingsKeys.SHELL_SIDEBAR_WIDTH])
        assertEquals("true", out[SettingsKeys.SHELL_SIDEBAR_COLLAPSED])
    }

    @Test
    fun `collapsed paths become the sorted JSON array UiPrefs writes`() {
        val out = migrate(LegacySwiftPrefs(collapsedPaths = listOf("/b", "/a", "/b", "__pas__")))
        assertEquals("""["/a","/b","__pas__"]""", out[SettingsKeys.SESSION_LIST_COLLAPSED_PATHS])
    }

    @Test
    fun `an empty host filter means no filter, not a filter on the empty string`() {
        assertNull(migrate(LegacySwiftPrefs(hostFilter = ""))[SettingsKeys.HOST_FILTER])
        assertEquals("rec-1", migrate(LegacySwiftPrefs(hostFilter = "rec-1"))[SettingsKeys.HOST_FILTER])
    }

    @Test
    fun `swift's launcher prefs JSON decodes as the kotlin model and is copied verbatim`() {
        // Exactly what Swift's JSONEncoder writes for LauncherPrefs (Sessions/LauncherStateStore.swift).
        val swift = """{"agent":"codex","models":{"codex":"gpt-5"},"reasoningLevels":{"codex":"high"}}"""
        val out = migrate(LegacySwiftPrefs(launcherPrefsJson = swift))
        assertEquals(swift, out[SettingsKeys.LAUNCHER_PREFS])
        // The claim this test actually exists to make: the Kotlin side can read it.
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<LauncherPrefs>(swift)
        assertEquals(LauncherPrefs("codex", mapOf("codex" to "gpt-5"), mapOf("codex" to "high")), decoded)
    }

    @Test
    fun `swift's launcher draft decodes even with workdir omitted`() {
        // Swift's JSONEncoder omits a nil Optional entirely rather than writing null.
        val swift = """{"useWorktree":true,"baseBranch":"main","text":"fix the flake"}"""
        val stored = migrate(LegacySwiftPrefs(launcherDraftJson = swift))[SettingsKeys.LAUNCHER_DRAFT]
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<LauncherDraft>(stored!!)
        assertEquals(LauncherDraft(workdir = null, useWorktree = true, baseBranch = "main", text = "fix the flake"), decoded)
    }

    @Test
    fun `an empty launcher draft is not migrated`() {
        val swift = """{"useWorktree":true,"baseBranch":"","text":""}"""
        assertNull(migrate(LegacySwiftPrefs(launcherDraftJson = swift))[SettingsKeys.LAUNCHER_DRAFT])
    }

    @Test
    fun `launcher JSON the kotlin decoder cannot read is dropped, not stored`() {
        // Structurally wrong (`models` as an array) — the shape guarantee failing is exactly the
        // case that must not reach the store, where it would read back as an empty launcher.
        val out = migrate(
            LegacySwiftPrefs(
                launcherPrefsJson = """{"agent":"claude","models":[]}""",
                launcherDraftJson = "not json at all",
            ),
        )
        assertNull(out[SettingsKeys.LAUNCHER_PREFS])
        assertNull(out[SettingsKeys.LAUNCHER_DRAFT])
    }

    @Test
    fun `a fully populated device migrates every key exactly once`() {
        val out = swiftPrefMigrations(
            LegacySwiftPrefs(
                appearance = "dark",
                chatDetailLevel = "high",
                drafts = mapOf("s1" to "draft"),
                editorFontSize = 13,
                editorLineWrap = true,
                sidebarWidthDp = 320.0,
                sidebarCollapsed = false,
                collapsedPaths = listOf("/repo"),
                hostFilter = "rec-1",
                launcherPrefsJson = """{"agent":"claude","models":{},"reasoningLevels":{}}""",
                launcherDraftJson = """{"workdir":"/repo","useWorktree":false,"baseBranch":"","text":""}""",
            ),
        )
        assertEquals(out.size, out.map { it.first }.distinct().size, "a key must not be written twice")
        assertTrue(out.map { it.first }.containsAll(
            listOf(
                SettingsKeys.APPEARANCE, SettingsKeys.CHAT_DETAIL_LEVEL, SettingsKeys.draft("s1"),
                SettingsKeys.EDITOR_FONT_SIZE, SettingsKeys.EDITOR_LINE_WRAP,
                SettingsKeys.SHELL_SIDEBAR_WIDTH, SettingsKeys.SHELL_SIDEBAR_COLLAPSED,
                SettingsKeys.SESSION_LIST_COLLAPSED_PATHS, SettingsKeys.HOST_FILTER,
                SettingsKeys.LAUNCHER_PREFS, SettingsKeys.LAUNCHER_DRAFT,
            ),
        ))
    }
}
