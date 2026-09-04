package dev.supermux.state

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsKeysTest {
    @Test fun draftKeyIsScopedBySession() { assertEquals("draft:abc", SettingsKeys.draft("abc")) }
    @Test fun launcherDraftIsASingleGlobalKey() { assertEquals("launcher:draft", SettingsKeys.LAUNCHER_DRAFT) }
}
