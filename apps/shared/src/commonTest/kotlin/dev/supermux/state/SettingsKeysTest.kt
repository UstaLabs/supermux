package dev.supermux.state

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsKeysTest {
    @Test fun draftKeyIsScopedBySession() { assertEquals("draft:abc", SettingsKeys.draft("abc")) }
    @Test fun launcherDraftIsASingleGlobalKey() { assertEquals("launcher:draft", SettingsKeys.LAUNCHER_DRAFT) }
    @Test fun launcherPrefsIsASingleGlobalKey() { assertEquals("launcher:prefs", SettingsKeys.LAUNCHER_PREFS) }

    /** Both apps' session lists collapse the same groups under ONE key (cluster F1). */
    @Test fun collapsedProjectPathsIsOneKeyForBothApps() {
        assertEquals("sessionList:collapsedPaths", SettingsKeys.SESSION_LIST_COLLAPSED_PATHS)
    }
}
