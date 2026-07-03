package dev.supermux.android.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-JVM unit tests for [LauncherPrefs]/[LauncherDraft] — the JSON wire-format contract this
 * feature shares with the web (`cmux:launcher-prefs`/`cmux:launcher-draft` localStorage) and iOS
 * (`LauncherStateStore.swift`) implementations. No Android framework/Context needed: these are
 * plain data classes encoded/decoded via kotlinx.serialization directly (mirrors
 * PushKeypairCodecTest's framework-free style). Uses the same `Json { ignoreUnknownKeys = true }`
 * config as [dev.supermux.android.AppViewModel]'s own `launcherJson`.
 */
class LauncherStateTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── LauncherPrefs ────────────────────────────────────────────────────────────

    @Test fun launcherPrefs_roundTrips_allFields() {
        val prefs = LauncherPrefs(agent = "codex", models = mapOf("claude" to "opus", "codex" to "o3"))
        val decoded = json.decodeFromString<LauncherPrefs>(json.encodeToString(prefs))
        assertEquals(prefs, decoded)
    }

    @Test fun launcherPrefs_emptyObject_yieldsDocumentedDefaults() {
        val decoded = json.decodeFromString<LauncherPrefs>("{}")
        assertEquals(LauncherPrefs(agent = "claude", models = emptyMap()), decoded)
    }

    @Test fun launcherPrefs_partialJson_fillsInMissingDefaults() {
        val agentOnly = json.decodeFromString<LauncherPrefs>("""{"agent":"cursor"}""")
        assertEquals("cursor", agentOnly.agent)
        assertEquals(emptyMap(), agentOnly.models)

        val modelsOnly = json.decodeFromString<LauncherPrefs>("""{"models":{"claude":"sonnet"}}""")
        assertEquals("claude", modelsOnly.agent)
        assertEquals(mapOf("claude" to "sonnet"), modelsOnly.models)
    }

    // ── LauncherDraft ────────────────────────────────────────────────────────────

    @Test fun launcherDraft_roundTrips_allFields() {
        val draft = LauncherDraft(
            workdir = "/home/user/project",
            useWorktree = false,
            baseBranch = "main",
            text = "fix the flaky test",
        )
        val decoded = json.decodeFromString<LauncherDraft>(json.encodeToString(draft))
        assertEquals(draft, decoded)
    }

    @Test fun launcherDraft_emptyObject_yieldsDocumentedDefaults() {
        val decoded = json.decodeFromString<LauncherDraft>("{}")
        assertEquals(LauncherDraft(workdir = null, useWorktree = true, baseBranch = "", text = ""), decoded)
        assertNull(decoded.workdir)
    }

    @Test fun launcherDraft_partialJson_fillsInMissingDefaults() {
        val workdirOnly = json.decodeFromString<LauncherDraft>("""{"workdir":"/tmp/repo"}""")
        assertEquals("/tmp/repo", workdirOnly.workdir)
        assertEquals(true, workdirOnly.useWorktree)
        assertEquals("", workdirOnly.baseBranch)
        assertEquals("", workdirOnly.text)

        val textAndFlagOnly = json.decodeFromString<LauncherDraft>("""{"useWorktree":false,"text":"hello"}""")
        assertNull(textAndFlagOnly.workdir)
        assertEquals(false, textAndFlagOnly.useWorktree)
        assertEquals("", textAndFlagOnly.baseBranch)
        assertEquals("hello", textAndFlagOnly.text)
    }
}
